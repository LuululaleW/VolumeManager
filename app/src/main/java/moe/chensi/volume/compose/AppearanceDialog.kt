package moe.chensi.volume.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.MyApplication
import moe.chensi.volume.PopupAppearance
import moe.chensi.volume.PopupSide
import moe.chensi.volume.PopupVertical
import moe.chensi.volume.R
import moe.chensi.volume.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceDialog(
    themeMode: ThemeMode,
    popupAppearance: PopupAppearance,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPopupAppearanceChange: ((PopupAppearance) -> PopupAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.theme_mode),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(stringResource(mode.labelRes)) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.popup_appearance),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(
                        R.string.popup_width,
                        popupAppearance.widthDp
                    )
                )
                Slider(
                    value = popupAppearance.widthDp.toFloat(),
                    onValueChange = { value ->
                        val width = (value / 10f).roundToInt() * 10
                        onPopupAppearanceChange { it.copy(widthDp = width) }
                    },
                    valueRange = MyApplication.MIN_POPUP_WIDTH_DP.toFloat()..
                        MyApplication.MAX_POPUP_WIDTH_DP.toFloat(),
                    steps = (MyApplication.MAX_POPUP_WIDTH_DP -
                        MyApplication.MIN_POPUP_WIDTH_DP) / 10 - 1
                )

                Text(
                    text = stringResource(
                        R.string.popup_height,
                        popupAppearance.maxHeightPercent
                    )
                )
                Slider(
                    value = popupAppearance.maxHeightPercent.toFloat(),
                    onValueChange = { value ->
                        onPopupAppearanceChange {
                            it.copy(maxHeightPercent = value.roundToInt())
                        }
                    },
                    valueRange = MyApplication.MIN_POPUP_HEIGHT_PERCENT.toFloat()..
                        MyApplication.MAX_POPUP_HEIGHT_PERCENT.toFloat()
                )

                Text(
                    text = stringResource(
                        R.string.popup_opacity,
                        (popupAppearance.opacity * 100).roundToInt()
                    )
                )
                Slider(
                    value = popupAppearance.opacity,
                    onValueChange = { value ->
                        onPopupAppearanceChange {
                            it.copy(opacity = (value * 100).roundToInt() / 100f)
                        }
                    },
                    valueRange = MyApplication.MIN_POPUP_OPACITY..
                        MyApplication.MAX_POPUP_OPACITY
                )

                Text(
                    text = stringResource(R.string.popup_side),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PopupSide.entries.forEach { side ->
                        FilterChip(
                            selected = popupAppearance.side == side,
                            onClick = {
                                onPopupAppearanceChange { it.copy(side = side) }
                            },
                            label = { Text(stringResource(side.labelRes)) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.popup_vertical),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PopupVertical.entries.forEach { vertical ->
                        FilterChip(
                            selected = popupAppearance.vertical == vertical,
                            onClick = {
                                onPopupAppearanceChange { it.copy(vertical = vertical) }
                            },
                            label = { Text(stringResource(vertical.labelRes)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
    }

private val PopupSide.labelRes: Int
    get() = when (this) {
        PopupSide.Left -> R.string.popup_left
        PopupSide.Right -> R.string.popup_right
    }

private val PopupVertical.labelRes: Int
    get() = when (this) {
        PopupVertical.Top -> R.string.popup_top
        PopupVertical.Center -> R.string.popup_center
        PopupVertical.Bottom -> R.string.popup_bottom
    }
