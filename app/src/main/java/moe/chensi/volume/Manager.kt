package moe.chensi.volume

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import moe.chensi.volume.data.App
import moe.chensi.volume.data.AppPreferencesStore
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import moe.chensi.volume.system.NotificationManagerProxy
import moe.chensi.volume.system.PackageManagerProxy
import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

@SuppressLint("PrivateApi")
class Manager(context: Context, dataStore: DataStore<Preferences>) {
    companion object {
        private const val TAG = "VolumeManager.Manager"

        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    }

    enum class ShizukuStatus {
        Uninstalled, Disconnected, PermissionDenied, Connected
    }

    private var _shizukuStatus by mutableStateOf(ShizukuStatus.Disconnected)
    val shizukuStatus
        get() = _shizukuStatus

    val audioManager = context.getSystemService(AudioManager::class.java)!!.also {
        runCatching {
            val service = Reflect.onClass(AudioManager::class.java).call("getService").get<Any>()
            ToggleableBinderProxy.wrap(service)
        }.onFailure { error ->
            Log.e(TAG, "Failed to wrap AudioManager service", error)
        }
    }

    val activityManager = context.getSystemService(ActivityManager::class.java)!!.also {
        runCatching {
            val service = Reflect.onClass(ActivityManager::class.java).call("getService").get<Any>()
            ToggleableBinderProxy.wrap(service)
        }.onFailure { error ->
            Log.e(TAG, "Failed to wrap ActivityManager service", error)
        }
    }
    private val packageManager by lazy { PackageManagerProxy.get(context) }
    val notificationManagerProxy = NotificationManagerProxy(context)

    private val appPreferencesStore = AppPreferencesStore(dataStore)
    private val _systemSliderVisibility = mutableStateMapOf<String, Boolean>()
    val systemSliderVisibility: Map<String, Boolean>
        get() = _systemSliderVisibility

    fun isSystemSliderVisible(id: String): Boolean {
        return _systemSliderVisibility[id] ?: true
    }

    fun setSystemSliderVisible(id: String, visible: Boolean) {
        if ((_systemSliderVisibility[id] ?: true) == visible) {
            return
        }

        _systemSliderVisibility[id] = visible
        appPreferencesStore.setSystemSliderVisible(id, visible)
    }

    val apps = mutableStateMapOf<String, App>()

    private fun reloadApps() {
        for (packageInfo in packageManager.getInstalledPackagesForAllUsers()) {
            val appInfo = packageInfo.applicationInfo ?: continue
            if (!apps.containsKey(packageInfo.packageName)) {
                apps[packageInfo.packageName] = App(
                    packageManager,
                    packageInfo,
                    packageManager.loadLabel(appInfo),
                    appPreferencesStore.getOrCreate(packageInfo.packageName),
                    appPreferencesStore::save
                )
            }
        }
    }

    fun ensureAppsLoaded() {
        if (apps.isNotEmpty()) {
            return
        }

        runCatching { reloadApps() }
            .onFailure { Log.w(TAG, "Failed to ensure apps are loaded", it) }
    }

    private fun getApp(packageName: String): App? {
        val app = apps[packageName]
        if (app != null) {
            return app
        }

        // Maybe just installed?
        reloadApps()
        return apps[packageName]
    }

    @EnableBinderProxy
    private fun initialize() {
        val shizukuGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        Log.i(TAG, "initialize shizuku=$shizukuGranted")
        ToggleableBinderProxy.withEnabled {
            runCatching { reloadApps() }
                .onFailure { Log.e(TAG, "Failed to load apps", it) }

            runCatching {
                val configs = readPlaybackConfigurations()
                Log.i(TAG, "Initial playback configurations: ${configs.size}")
                processAudioPlaybackConfigurations(configs)
            }.onFailure { Log.e(TAG, "Failed to read playback configurations", it) }

            runCatching {
                audioManager.registerAudioPlaybackCallback(
                    object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                            processAudioPlaybackConfigurations(configs)
                        }
                    }, null
                )
            }.onFailure { Log.e(TAG, "Failed to register playback callback", it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun setRingerMode(mode: Int) {
        try {
            audioManager.ringerMode = mode
        } catch (error: SecurityException) {
            Log.w(TAG, "Direct ringer mode change was denied, retrying with Shizuku", error)
            setRingerModeViaShizuku(mode)
        }
    }

    @EnableBinderProxy
    private fun setRingerModeViaShizuku(mode: Int) {
        audioManager.ringerMode = mode
    }

    @EnableBinderProxy
    fun refreshPlaybackConfigurations() {
        ToggleableBinderProxy.withEnabled {
            runCatching {
                processAudioPlaybackConfigurations(readPlaybackConfigurations())
            }.onFailure {
                Log.w(TAG, "Failed to refresh playback configurations", it)
            }
        }
    }

    private fun playerCount(configs: List<AudioPlaybackConfiguration>): Int {
        return configs.count { AudioPlaybackConfigurationProxy(it).hasPlayer }
    }

    private fun readPlaybackConfigurations(): List<AudioPlaybackConfiguration> {
        val proxied = runCatching { audioManager.activePlaybackConfigurations }
            .onFailure { Log.w(TAG, "Failed to read proxied playback configurations", it) }
            .getOrDefault(emptyList())
        val proxiedPlayers = playerCount(proxied)
        Log.i(
            TAG,
            "Playback configurations via Shizuku: total=${proxied.size}, " +
                "players=$proxiedPlayers, enabled=${ToggleableBinderProxy.enabled}"
        )
        if (proxied.isNotEmpty() && proxiedPlayers > 0) {
            return proxied
        }

        val previous = ToggleableBinderProxy.enabled
        return try {
            ToggleableBinderProxy.enabled = false
            val direct = audioManager.activePlaybackConfigurations
            val directPlayers = playerCount(direct)
            Log.i(
                TAG,
                "Playback configurations direct: total=${direct.size}, players=$directPlayers"
            )
            if (direct.isNotEmpty() && directPlayers > 0 || proxied.isEmpty()) direct else proxied
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read direct playback configurations", error)
            proxied
        } finally {
            ToggleableBinderProxy.enabled = previous
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    @EnableBinderProxy
    fun processAudioPlaybackConfigurations(configs: List<AudioPlaybackConfiguration>) {
        val runningProcesses = ToggleableBinderProxy.withEnabled {
            runCatching { activityManager.runningAppProcesses }
                .onFailure { Log.e(TAG, "Failed to read running processes", it) }
                .getOrNull()
                .orEmpty()
        }

        Log.i(TAG, "Processing playback configurations: count=${configs.size}")

        val packagesByPid = mutableMapOf<Int, MutableList<String>>()
        for (process in runningProcesses) {
            val packageNames = process.pkgList ?: continue
            packagesByPid.getOrPut(process.pid) { mutableListOf() }.addAll(packageNames.toList())
        }

        val packagesByUid = apps.values
            .groupBy { it.applicationInfo.uid }
            .mapValues { (_, uidApps) -> uidApps.map { it.packageName } }

        val playersByPackage = mutableMapOf<String, MutableList<AudioPlaybackConfigurationProxy>>()

        for (config in configs) {
            val proxy = AudioPlaybackConfigurationProxy(config)
            val packageName = resolveAudioPackage(proxy, packagesByPid, packagesByUid)
            Log.i(
                TAG,
                "Config uid=${proxy.clientUid} pid=${proxy.clientPid} " +
                    "type=${proxy.playerTypeName} state=${proxy.playerStateName} " +
                    "hasPlayer=${proxy.hasPlayer} playing=${proxy.isPlaying} package=$packageName"
            )
            if (packageName == null) {
                continue
            }

            runCatching { getApp(packageName) }
                .onFailure { Log.w(TAG, "Failed to resolve app $packageName", it) }
                .getOrNull() ?: continue

            playersByPackage.getOrPut(packageName) { mutableListOf() }.add(proxy)
        }

        for (app in apps.values) {
            val players = playersByPackage[app.packageName]
            if (players != null) {
                app.replacePlayers(players)
            } else {
                app.clearPlayers()
            }
        }
    }

    private fun resolveAudioPackage(
        proxy: AudioPlaybackConfigurationProxy,
        packagesByPid: Map<Int, List<String>>,
        packagesByUid: Map<Int, List<String>>
    ): String? {
        val candidates = buildList {
            packagesByPid[proxy.clientPid]?.let { addAll(it) }
            packagesByUid[proxy.clientUid]?.let { addAll(it) }
        }

        candidates.firstOrNull { apps.containsKey(it) }?.let { return it }
        candidates.firstOrNull()?.let { return it }

        return runCatching { packageManager.getPackagesForUid(proxy.clientUid) }
            .getOrNull()
            ?.firstOrNull { apps.containsKey(it) }
    }

    init {
        val isShizukuInstalled = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isShizukuInstalled) {
            _shizukuStatus = ShizukuStatus.Uninstalled
        } else if (!Shizuku.pingBinder()) {
            _shizukuStatus = ShizukuStatus.Disconnected
        }

        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.isPreV11()) {
                return@addBinderReceivedListenerSticky
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            } else {
                _shizukuStatus = ShizukuStatus.PermissionDenied
            }
        }

        Shizuku.addBinderDeadListener {
            _shizukuStatus = ShizukuStatus.Disconnected
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            }
        }

        ShizukuProvider.requestBinderForNonProviderProcess(context)
    }

    private fun start() {
        appPreferencesStore.track {
            _systemSliderVisibility.clear()
            _systemSliderVisibility.putAll(appPreferencesStore.systemSliderVisibility)
            initialize()
        }
    }
}
