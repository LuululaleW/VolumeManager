package moe.chensi.volume

sealed interface VolumeKeyTarget {
    data object Media : VolumeKeyTarget

    data class App(val packageName: String) : VolumeKeyTarget
}
