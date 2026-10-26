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
        runCatching { reloadApps() }
            .onFailure { Log.e(TAG, "Failed to load apps", it) }

        runCatching {
            val playbackConfigurations = audioManager.activePlaybackConfigurations
            processAudioPlaybackConfigurations(playbackConfigurations)
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

    @EnableBinderProxy
    fun refreshPlaybackConfigurations() {
        runCatching {
            processAudioPlaybackConfigurations(audioManager.activePlaybackConfigurations)
        }.onFailure {
            Log.w(TAG, "Failed to refresh playback configurations", it)
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    @EnableBinderProxy
    fun processAudioPlaybackConfigurations(configs: List<AudioPlaybackConfiguration>) {
        val runningProcesses = runCatching { activityManager.runningAppProcesses }
            .onFailure { Log.e(TAG, "Failed to read running processes", it) }
            .getOrNull()
            .orEmpty()

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
                ?: continue

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
