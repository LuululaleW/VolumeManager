package moe.chensi.volume.system

import android.media.AudioPlaybackConfiguration
import android.util.Log
import org.joor.Reflect

class AudioPlaybackConfigurationProxy(raw: AudioPlaybackConfiguration) {
    enum class PlayerState(val value: Int) {
        Unknown(-1), Released(0), Idle(1), Started(2), Paused(3), Stopped(4);
    }

    fun Int.toPlayerState(): PlayerState {
        for (state in PlayerState.entries) {
            if (state.value == this) {
                return state
            }
        }
        return PlayerState.Unknown
    }

    companion object {
        private const val TAG = "AudioPlaybackConfig"

        private val classReflect: Reflect = Reflect.onClass(AudioPlaybackConfiguration::class.java)
    }

    private val reflect = Reflect.on(raw)

    private val iPlayer by lazy {
        runCatching { reflect.call("getIPlayer") }
            .onFailure { Log.w(TAG, "IPlayer is unavailable", it) }
            .getOrNull()
    }

    private val playerProxy by lazy {
        runCatching { reflect.call("getPlayerProxy") }
            .onFailure { Log.w(TAG, "PlayerProxy is unavailable", it) }
            .getOrNull()
    }

    val hasPlayer
        get() = runCatching {
            iPlayer?.get<Any>() != null || playerProxy?.get<Any>() != null
        }.getOrDefault(false)

    val clientPid: Int
        get() = runCatching { reflect.get<Int>("mClientPid") }.getOrDefault(-1)

    val clientUid: Int
        get() = runCatching { reflect.get<Int>("mClientUid") }.getOrDefault(-1)

    val playerType: Int
        get() = runCatching { reflect.get<Int>("mPlayerType") }.getOrDefault(-1)

    val playerTypeName: String by lazy {
        runCatching {
            classReflect.call("toLogFriendlyPlayerType", playerType).get<String>()
        }.getOrDefault("Unknown")
    }

    val playerState
        get() = runCatching {
            reflect.get<Int>("mPlayerState").toPlayerState()
        }.getOrDefault(PlayerState.Unknown)

    val playerStateName: String by lazy {
        runCatching {
            classReflect.call("playerStateToString", playerState.value).get<String>()
        }.getOrDefault(PlayerState.Unknown.name)
    }

    val isPlaying: Boolean
        get() {
            runCatching { reflect.call("isActive").get<Boolean>() }
                .getOrNull()
                ?.let { return it }

            if (playerType == 3) {
                return true
            }

            return playerState == PlayerState.Started
        }

    private fun setVolumeOn(target: Reflect?, value: Float): Boolean {
        if (target == null) {
            return false
        }

        return try {
            if (target.get<Any>() == null) {
                false
            } else {
                target.call("setVolume", value)
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to set player volume", error)
            false
        }
    }

    fun setVolume(value: Float): Boolean {
        return setVolumeOn(iPlayer, value) || setVolumeOn(playerProxy, value)
    }
}
