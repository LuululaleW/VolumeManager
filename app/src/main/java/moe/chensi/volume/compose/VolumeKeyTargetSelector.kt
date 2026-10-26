package moe.chensi.volume.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.R
import moe.chensi.volume.data.App

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeKeyTargetSelector(
    selectedApp: App?,
    hasActiveApps: Boolean,
    onSelectApp: () -> Unit,
    onSelectMedia: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.volume_key_target),
            style = MaterialTheme.typography.labelLarge
        )

        FilterChip(
            selected = selectedApp != null,
            onClick = onSelectApp,
            enabled = hasActiveApps,
            label = { Text(stringResource(R.string.volume_key_target_app)) }
        )

        FilterChip(
            selected = selectedApp == null,
            onClick = onSelectMedia,
            label = { Text(stringResource(R.string.stream_media)) }
        )
    }
}
