package moe.chensi.volume.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.icu.text.Collator
import android.icu.text.Transliterator
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import moe.chensi.volume.system.PackageManagerProxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class App(
    val packageManager: PackageManagerProxy,
    val packageInfo: PackageInfo,
    val name: String,
    private val preferences: AppPreferences,
    private val savePreferences: () -> Unit
) {
    companion object {
        private const val HAS_BEEN_PLAYING_RECENTLY_TIMEOUT_MILLIS = 10_000L
        private const val VOLUME_SAVE_DEBOUNCE_MILLIS = 250L

        val collator: Collator by lazy {
            Collator.getInstance().apply {
                strength = Collator.PRIMARY
                decomposition = Collator.CANONICAL_DECOMPOSITION
            }
        }

        val transliterator: Transliterator by lazy { Transliterator.getInstance("Han-Latin") }

        val defaultComparator: Comparator<App> by lazy {
            compareBy(collator) { it.name }
        }

        val chineseComparator: Comparator<App> by lazy {
            compareBy(Comparator { a, b ->
                for ((aChar, bChar) in a.zip(b)) {
                    if (aChar == bChar) {
                        continue
                    }

                    val aIsChinese =
                        Character.UnicodeScript.of(aChar.code) == Character.UnicodeScript.HAN
                    val bIsChinese =
                        Character.UnicodeScript.of(bChar.code) == Character.UnicodeScript.HAN

                    // Both are Chinese or both are not Chinese
                    if (aIsChinese == bIsChinese) {
                        val result = collator.compare(aChar.toString(), bChar.toString())
                        if (result != 0) {
                            return@Comparator result
                        }
                    }

                    if (aIsChinese) {
                        val transliteration = transliterator.transliterate(aChar.toString())
                        val result = collator.compare(transliteration, bChar.toString())
                        if (result != 0) {
                            return@Comparator result
                        }
                    } else {
                        val transliteration = transliterator.transliterate(bChar.toString())
                        val result = collator.compare(aChar.toString(), transliteration)
                        if (result != 0) {
                            return@Comparator result
                        }
                    }
                }

                return@Comparator a.length - b.length
            }) { it.name }
        }

        val comparator: Comparator<App>
            get() {
                if (Locale.getDefault().language == "zh") {
                    return chineseComparator
                }
                return defaultComparator
            }

        val scope = CoroutineScope(Dispatchers.IO)
    }

    val packageName: String
        get() = packageInfo.packageName

    val applicationInfo
        get() = packageInfo.applicationInfo!!

    private val isSystemApp: Boolean
        get() = (applicationInfo.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    val shouldShowInAppPicker: Boolean
        get() = !isSystemApp

    private var _icon by mutableStateOf<ImageBitmap?>(null)
    private var _iconLoading = false
    val icon: ImageBitmap?
        get() {
            if (!_iconLoading) {
                _iconLoading = true
                scope.launch {
                    _icon = packageManager.getDrawable(
                        packageName,
                        applicationInfo.icon,
                        applicationInfo
                    )?.toBitmap(128, 128)?.asImageBitmap()
                        ?: packageManager.defaultActivityIconImageBitmap
                }
            }

            return _icon
        }

    private val _players: MutableList<AudioPlaybackConfigurationProxy> = mutableStateListOf()
    val players: List<AudioPlaybackConfigurationProxy> = _players

    fun clearPlayers() {
        if (_players.isEmpty() && !isPlaying) {
            return
        }

        _players.clear()
        isPlaying = false
    }

    fun replacePlayers(configs: List<AudioPlaybackConfigurationProxy>) {
        val validPlayers = mutableListOf<AudioPlaybackConfigurationProxy>()
        var playing = false

        for (config in configs) {
            // Set app as player even if the player is released
            isPlayer = true

            if (config.isPlaying) {
                playing = true
            }

            if (!config.hasPlayer) {
                continue
            }

            Log.i(
                "AppVolManager",
                "replace player $packageName ${config.clientPid} ${config.playerTypeName} ${config.playerStateName}"
            )

            // Apply volume to potentially new player
            if (!config.setVolume(_volume)) {
                // Player is dead, don't add it
                continue
            }

            validPlayers.add(config)
        }

        Log.i(
            "AppVolManager",
            "replace players $packageName configs=${configs.size} valid=${validPlayers.size} " +
                "playing=$playing volume=$_volume"
        )
        _players.clear()
        _players.addAll(validPlayers)
        isPlaying = playing
    }

    fun applyVolume(value: Float) {
        val deadPlayers = mutableListOf<AudioPlaybackConfigurationProxy>()
        for (player in players) {
            if (!player.setVolume(value)) {
                deadPlayers.add(player)
            }
        }
        // Remove dead players
        _players.removeAll(deadPlayers)
        Log.i(
            "AppVolManager",
            "apply volume $packageName=$value players=${players.size} " +
                "dead=${deadPlayers.size}"
        )
    }

    private var _isPlayer by mutableStateOf(preferences.isPlayer)
    var isPlayer: Boolean
        get() = _isPlayer
        private set(value) {
            if (value == _isPlayer) {
                return
            }

            _isPlayer = value
            preferences.isPlayer = value
            savePreferences()
        }

    private var _isPlaying by mutableStateOf(false)
    var isPlaying: Boolean
        get() = _isPlaying
        private set(value) {
            if (value == _isPlaying) {
                return
            }

            _isPlaying = value
            val version = hasBeenPlayingRecentlyVersion.incrementAndGet()

            if (value) {
                lastPlayedAt = SystemClock.elapsedRealtime()
                Snapshot.withMutableSnapshot {
                    _hasBeenPlayingRecently = true
                }
                hasBeenPlayingRecentlyResetJob?.cancel()
                hasBeenPlayingRecentlyResetJob = null
            } else {
                hasBeenPlayingRecentlyResetJob?.cancel()
                hasBeenPlayingRecentlyResetJob = scope.launch {
                    delay(HAS_BEEN_PLAYING_RECENTLY_TIMEOUT_MILLIS)
                    if (hasBeenPlayingRecentlyVersion.get() == version) {
                        Snapshot.withMutableSnapshot {
                            _hasBeenPlayingRecently = false
                        }
                    }
                }
            }
        }

    private val hasBeenPlayingRecentlyVersion = AtomicLong(0)
    @Volatile
    private var hasBeenPlayingRecentlyResetJob: Job? = null

    private var _hasBeenPlayingRecently by mutableStateOf(false)
    val hasBeenPlayingRecently: Boolean
        get() = _hasBeenPlayingRecently

    var lastPlayedAt: Long = 0L
        private set

    private var volumeSaveJob: Job? = null

    private var _volume by mutableFloatStateOf(preferences.volume)
    var volume
        get() = _volume
        set(value) {
            if (value == _volume) {
                return
            }

            applyVolume(value)

            _volume = value
            preferences.volume = value
            volumeSaveJob?.cancel()
            volumeSaveJob = scope.launch {
                delay(VOLUME_SAVE_DEBOUNCE_MILLIS)
                savePreferences()
            }
        }

    private var _hidden by mutableStateOf(preferences.hidden)
    var hidden: Boolean
        get() = _hidden
        set(value) {
            if (value == _hidden) {
                return
            }

            _hidden = value
            preferences.hidden = value
            savePreferences()
        }

    private var _disableVolumeButtons by mutableStateOf(preferences.disableVolumeButtons)
    var disableVolumeButtons: Boolean
        get() = _disableVolumeButtons
        set(value) {
            if (value == _disableVolumeButtons) {
                return
            }

            _disableVolumeButtons = value
            preferences.disableVolumeButtons = value
            savePreferences()
        }
}
