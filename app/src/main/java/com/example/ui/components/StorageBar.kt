package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.OlloTheme

/**
 * Storage bar displaying connected glasses storage (used KB / total KB)
 * or disconnected offline estimate vs budget.
 */
@Composable
fun StorageBar(
    isConnected: Boolean,
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors
    val total = if (totalBytes > 0) totalBytes else 1_441_792L // Default ~1.4MB budget
    val ratio = (usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(targetValue = ratio, label = "storage_ratio")

    val usedKb = usedBytes / 1024
    val totalKb = total / 1024

    val isNearFull = ratio >= 0.90f
    val barColor = when {
        isNearFull -> colors.warning
        isConnected -> colors.accent
        else -> colors.textMuted
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("storage_bar")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isConnected) "Glasses storage left" else "Storage (estimate)",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted
                )
                Text(
                    text = if (isConnected) "${(totalKb - usedKb).coerceAtLeast(0)} KB left of $totalKb KB" else "$usedKb KB / $totalKb KB (${(ratio * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isNearFull) colors.warning else colors.onBackground
                )
            }

            LinearProgressIndicator(
                progress = { animatedRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = colors.surfaceElevated,
                strokeCap = StrokeCap.Round
            )
        }
    }
}
