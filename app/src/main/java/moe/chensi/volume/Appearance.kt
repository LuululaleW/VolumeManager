package moe.chensi.volume

enum class ThemeMode {
    System,
    Light,
    Dark
}

enum class PopupSide {
    Left,
    Right
}

enum class PopupVertical {
    Top,
    Center,
    Bottom
}

data class PopupAppearance(
    val widthDp: Int = MyApplication.DEFAULT_POPUP_WIDTH_DP,
    val maxHeightPercent: Int = MyApplication.DEFAULT_POPUP_HEIGHT_PERCENT,
    val opacity: Float = MyApplication.DEFAULT_POPUP_OPACITY,
    val side: PopupSide = PopupSide.Right,
    val vertical: PopupVertical = PopupVertical.Center
)
