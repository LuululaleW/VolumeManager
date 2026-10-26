package moe.chensi.volume.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.R
import moe.chensi.volume.data.App

@Composable
fun VolumeKeyPopup(
    apps: Collection<App>,
    showAppVolumes: Boolean,
    selectedApp: App?,
    hooksEnabled: Boolean,
    maxHeightPercent: Int,
    opacity: Float,
    onShowAppVolumes: () -> Unit,
    onShowSystemVolume: () -> Unit,
    onSelectApp: (App) -> Unit,
    onChange: () -> Unit,
    systemVolumeContent: @Composable (Modifier) -> Unit
) {
    val maxHeight = (
        LocalConfiguration.current.screenHeightDp * maxHeightPercent / 100f
        ).dp
    val systemHeight = maxHeight.coerceAtLeast(280.dp)
    val selected = selectedApp?.takeUnless { it.hidden }
    val playingApps = apps
        .filter { it.isPlaying && !it.hidden }
        .sortedByDescending { it.lastPlayedAt }
    val recentApps = apps
        .filter {
            !it.isPlaying && (it.hasBeenPlayingRecently || it.players.isNotEmpty()) && !it.hidden
        }
        .sortedByDescending { it.lastPlayedAt }
    val listedPackages = (playingApps + recentApps).mapTo(mutableSetOf()) { it.packageName }
    val selectedOnly = selected?.takeUnless { it.packageName in listedPackages }
    val fallbackApps = if (
        selectedOnly == null && playingApps.isEmpty() && recentApps.isEmpty()
    ) {
        apps.filter { it.shouldShowInAppPicker && !it.hidden }
            .sortedWith(App.comparator)
    } else {
        emptyList()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showAppVolumes) {
                    Modifier.heightIn(max = maxHeight)
                } else {
                    Modifier.height(systemHeight)
                }
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = opacity),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp
    ) {
        if (showAppVolumes) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppVolumesHeader(onShowSystemVolume = onShowSystemVolume)
                AppVolumeGroups(
                    selected = selected,
                    selectedOnly = selectedOnly,
                    playingApps = playingApps,
                    recentApps = recentApps,
                    fallbackApps = fallbackApps,
                    hooksEnabled = hooksEnabled,
                    onSelectApp = onSelectApp,
                    onChange = onChange
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SystemVolumeHeader(onShowAppVolumes = onShowAppVolumes)
                systemVolumeContent(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SystemVolumeHeader(onShowAppVolumes: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.system_volume_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium
        )
        IconButton(onClick = onShowAppVolumes) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.app_volumes_title)
            )
        }
    }
}

@Composable
private fun AppVolumesHeader(onShowSystemVolume: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShowSystemVolume) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back_to_system_volume)
            )
        }
        Text(
            text = stringResource(R.string.app_volumes_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun AppVolumeGroups(
    selected: App?,
    selectedOnly: App?,
    playingApps: List<App>,
    recentApps: List<App>,
    fallbackApps: List<App>,
    hooksEnabled: Boolean,
    onSelectApp: (App) -> Unit,
    onChange: () -> Unit
) {
    val selectedTitle = if (selectedOnly != null) {
        stringResource(R.string.selected_app)
    } else {
        null
    }
    val playingTitle = if (playingApps.isNotEmpty()) {
        stringResource(R.string.playing_now)
    } else {
        null
    }
    val recentTitle = if (recentApps.isNotEmpty()) {
        stringResource(R.string.recently_paused)
    } else {
        null
    }
    val fallbackTitle = if (fallbackApps.isNotEmpty()) {
        stringResource(R.string.all_apps)
    } else {
        null
    }

    AppVolumeGroup(
        title = selectedTitle,
        apps = listOfNotNull(selectedOnly),
        selected = selected,
        hooksEnabled = hooksEnabled,
        onSelectApp = onSelectApp,
        onChange = onChange
    )
    AppVolumeGroup(
        title = playingTitle,
        apps = playingApps,
        selected = selected,
        hooksEnabled = hooksEnabled,
        onSelectApp = onSelectApp,
        onChange = onChange
    )
    AppVolumeGroup(
        title = recentTitle,
        apps = recentApps,
        selected = selected,
        hooksEnabled = hooksEnabled,
        onSelectApp = onSelectApp,
        onChange = onChange
    )
    AppVolumeGroup(
        title = fallbackTitle,
        apps = fallbackApps,
        selected = selected,
        hooksEnabled = hooksEnabled,
        onSelectApp = onSelectApp,
        onChange = onChange
    )
}

@Composable
private fun AppVolumeGroup(
    title: String?,
    apps: List<App>,
    selected: App?,
    hooksEnabled: Boolean,
    onSelectApp: (App) -> Unit,
    onChange: () -> Unit
) {
    if (apps.isEmpty()) {
        return
    }

    if (title != null) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    apps.forEach { app ->
        AppVolumeSlider(
            app = app,
            showOptions = false,
            showVolumeButtonToggle = false,
            selectedForVolumeKeys = hooksEnabled && selected?.packageName == app.packageName,
            onSelectForVolumeKeys = if (hooksEnabled) {
                { onSelectApp(app) }
            } else {
                null
            },
            onChange = onChange
        )
    }
}
