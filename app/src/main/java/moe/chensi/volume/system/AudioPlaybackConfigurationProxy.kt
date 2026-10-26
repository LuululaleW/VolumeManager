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

    private val player by lazy {
        runCatching { reflect.call("getIPlayer") }
            .onFailure { Log.w(TAG, "Player proxy is unavailable", it) }
            .getOrNull()
    }

    val hasPlayer
        get() = runCatching { player?.get<Any>() != null }.getOrDefault(false)

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

    fun setVolume(value: Float): Boolean {
        return try {
            val playerReflect = player
            if (playerReflect == null || playerReflect.get<Any>() == null) {
                false
            } else {
                playerReflect.call("setVolume", value)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set player volume", e)
            false
        }
    }
}
