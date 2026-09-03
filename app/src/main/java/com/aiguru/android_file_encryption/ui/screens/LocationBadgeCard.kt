package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiguru.android_file_encryption.storage.LocType
import com.aiguru.android_file_encryption.storage.LocationInfo

/** Cloud-vs-local badge with provider icon glyph + typed recommendation card. */
@Composable
fun LocationBadgeCard(info: LocationInfo, modifier: Modifier = Modifier) {
    if (info.type == LocType.NONE) return

    val glyph = when (info.type) {
        LocType.LOCAL -> "📱"
        LocType.GOOGLE_DRIVE -> "🟡"
        LocType.ONEDRIVE -> "🔵"
        LocType.DROPBOX -> "📦"
        LocType.OTHER_CLOUD -> "☁"
        LocType.NONE -> ""
    }

    Column(modifier = modifier) {
        AssistChip(
            onClick = {},
            label = { Text("${glyph} ${info.label()}") },
            enabled = false
        )
        Spacer(Modifier.height(6.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                info.recommendation(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}