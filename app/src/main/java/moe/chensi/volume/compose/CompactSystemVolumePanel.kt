package moe.chensi.volume.compose

import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.chensi.volume.R
import moe.chensi.volume.system.NotificationManagerProxy
import kotlin.math.roundToInt

private fun isCallMode(mode: Int): Boolean {
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactSystemVolumePanel(
    audioManager: AudioManager,
    notificationManagerProxy: NotificationManagerProxy,
    setRingerMode: (Int) -> Unit,
    isSliderVisible: (String) -> Boolean,
    onChange: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var inCallMode by remember { mutableStateOf(isCallMode(audioManager.mode)) }

    DisposableEffect(audioManager) {
        val listener = AudioManager.OnModeChangedListener { mode ->
            inCallMode = isCallMode(mode)
        }
        audioManager.addOnModeChangedListener(executor, listener)
        onDispose {
            audioManager.removeOnModeChangedListener(listener)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (inCallMode && isSliderVisible(SystemSliderIds.Call)) {
                VerticalVolumeSlider(
                    streamType = AudioManager.STREAM_VOICE_CALL,
                    icon = Icons.Default.PhoneInTalk,
                    name = stringResource(R.string.stream_call),
                    audioManager = audioManager,
                    onChange = onChange
                )
            }
            if (isSliderVisible(SystemSliderIds.Media)) {
                VerticalVolumeSlider(
                    streamType = AudioManager.STREAM_MUSIC,
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    name = stringResource(R.string.stream_media),
                    audioManager = audioManager,
                    onChange = onChange
                )
            }
            if (isSliderVisible(SystemSliderIds.Ring)) {
                VerticalVolumeSlider(
                    streamType = AudioManager.STREAM_RING,
                    icon = Icons.Default.RingVolume,
                    name = stringResource(R.string.stream_ring),
                    audioManager = audioManager,
                    onChange = onChange
                )
            }
            if (isSliderVisible(SystemSliderIds.Alarm)) {
                VerticalVolumeSlider(
                    streamType = AudioManager.STREAM_ALARM,
                    icon = Icons.Default.Alarm,
                    name = stringResource(R.string.stream_alarm),
                    audioManager = audioManager,
                    onChange = onChange
                )
            }
            if (isSliderVisible(SystemSliderIds.Notification)) {
                VerticalVolumeSlider(
                    streamType = AudioManager.STREAM_NOTIFICATION,
                    icon = Icons.Default.NotificationsActive,
                    name = stringResource(R.string.stream_notification),
                    audioManager = audioManager,
                    onChange = onChange
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            RingFooter(
                audioManager = audioManager,
                notificationManagerProxy = notificationManagerProxy,
                setRingerMode = setRingerMode,
                sliderVisible = isSliderVisible(SystemSliderIds.Ring),
                allowVisibilityConfig = false,
                onSliderVisibilityChange = { _, _ -> },
                onChange = onChange
            )
        }
    }
}

@Composable
private fun VerticalVolumeSlider(
    streamType: Int,
    icon: ImageVector,
    name: String,
    audioManager: AudioManager,
    onChange: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var volume by remember {
        mutableIntStateOf(runCatching { audioManager.getStreamVolume(streamType) }.getOrDefault(0))
    }
    var maxVolume by remember {
        mutableIntStateOf(
            runCatching { audioManager.getStreamMaxVolume(streamType) }
                .getOrDefault(1)
                .coerceAtLeast(1)
        )
    }

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose { VolumeChangeObserver.stopObserving() }
    }

    LaunchedEffect(streamType) {
        maxVolume = runCatching { audioManager.getStreamMaxVolume(streamType) }
            .getOrDefault(maxVolume)
            .coerceAtLeast(1)
        volume = volume.coerceIn(0, maxVolume)
    }

    val volumeChangedCount = VolumeChangeObserver.volumeChangedCount
    LaunchedEffect(volumeChangedCount) {
        volume = runCatching { audioManager.getStreamVolume(streamType) }
            .getOrDefault(volume)
            .coerceIn(0, maxVolume)
    }

    val sliderHeight = 120.dp
    val sliderWidth = 28.dp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary

    fun applyVolume(target: Int) {
        val clamped = target.coerceIn(0, maxVolume)
        if (volume == clamped) return
        runCatching { audioManager.setStreamVolume(streamType, clamped, 0) }
            .onSuccess {
                volume = clamped
                onChange?.invoke()
            }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(
            modifier = Modifier
                .width(sliderWidth)
                .height(sliderHeight)
                .progressSemantics(
                    value = volume.toFloat(),
                    valueRange = 0f..maxVolume.toFloat()
                )
                .semantics { contentDescription = name }
                .pointerInput(streamType, maxVolume) {
                    fun volumeAt(y: Float): Int {
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        return ((1f - y / height) * maxVolume).roundToInt()
                    }

                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            applyVolume(volumeAt(offset.y))
                        }
                    ) { change, _ ->
                        change.consume()
                        applyVolume(volumeAt(change.position.y))
                    }
                }
        ) {
            val thumbRadius = 7.dp.toPx()
            val trackWidth = 4.dp.toPx()
            val top = thumbRadius
            val bottom = size.height - thumbRadius
            val usable = (bottom - top).coerceAtLeast(1f)
            val fraction = (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
            val centerX = size.width / 2f
            val trackX = centerX - trackWidth / 2f
            val thumbY = bottom - usable * fraction

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(trackX, top),
                size = Size(trackWidth, usable),
                cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
            )
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(trackX, thumbY),
                size = Size(trackWidth, bottom - thumbY),
                cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadius,
                center = Offset(centerX, thumbY)
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
