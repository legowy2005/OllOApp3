package com.example.ui.screens.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.theme.OlloTheme

enum class SyncPhase {
    IDLE,
    CHECKING_STORAGE,
    SYNCING_CARDS,
    SYNCING_IMAGES,
    FINALIZING,
    COMPLETED,
    FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    isConnected: Boolean,
    deviceName: String?,
    rssi: Int?,
    usedStorageBytes: Long, // Synced deck size in bytes
    totalStorageBytes: Long, // Total flash bytes (e.g. 1.4 MB)
    freeStorageBytes: Long?,
    includedFolders: List<String>,
    totalCardsCount: Int,
    syncPhase: SyncPhase,
    progressFraction: Float,
    statusMessage: String,
    errorMessage: String? = null,
    onBackClick: () -> Unit,
    onStartSync: () -> Unit,
    onRetrySync: () -> Unit,
    onPairNewDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors
    val isSyncing = syncPhase in listOf(
        SyncPhase.CHECKING_STORAGE,
        SyncPhase.SYNCING_CARDS,
        SyncPhase.SYNCING_IMAGES,
        SyncPhase.FINALIZING
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sync",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Large Connection Badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
                    .padding(20.dp)
                    .testTag("connection_badge")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) colors.accentMuted else colors.surfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = if (isConnected) colors.accent else colors.textMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isConnected) (deviceName ?: "Ollo glasses") else "Disconnected",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onBackground
                            )
                            if (isConnected && rssi != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$rssi dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.accent
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isConnected) "Connected & ready to sync" else "Not connected to smart glasses",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected) colors.accent else colors.textMuted
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) colors.accent else colors.textMuted)
                    )
                }
            }

            // Segmented Storage Bar & Exact Numbers (Section 8)
            val syncedKb = (usedStorageBytes + 1023) / 1024
            val freeBytesCalc = freeStorageBytes ?: (totalStorageBytes - usedStorageBytes).coerceAtLeast(0L)
            val freeMbFormatted = String.format("%.1f MB", freeBytesCalc / (1024.0 * 1024.0))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Glasses Storage",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onBackground
                        )
                        Text(
                            text = "Synced: $syncedKb KB • Free on glasses: $freeMbFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent
                        )
                    }

                    // Segmented storage visualization
                    val fraction = (usedStorageBytes.toFloat() / totalStorageBytes.coerceAtLeast(1L)).coerceIn(0.01f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accent)
                        )
                    }
                }
            }

            // Sync Payload Summary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Deck to Sync",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total cards", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        Text("$totalCardsCount cards", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Folders included", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        Text("${includedFolders.size} folders", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        includedFolders.forEach { folderName ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(folderName, style = MaterialTheme.typography.bodySmall, color = colors.onBackground)
                            }
                        }
                    }
                }
            }

            // Prominent Error Banner (Section 8)
            if (syncPhase == SyncPhase.FAILED && errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.error, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                        .testTag("sync_error_banner")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Sync Failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.error
                            )
                        }
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground
                        )
                        OlloPrimaryButton(
                            text = "Retry Sync",
                            onClick = onRetrySync,
                            icon = Icons.Default.Refresh,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Sync Status / Progress Section (Section 8)
            if (isSyncing || syncPhase == SyncPhase.COMPLETED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceElevated)
                        .padding(16.dp)
                        .testTag("sync_progress_box")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (syncPhase == SyncPhase.COMPLETED) colors.accent else colors.onBackground
                            )
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent
                                )
                            } else if (syncPhase == SyncPhase.COMPLETED) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = colors.accent,
                            trackColor = colors.surface
                        )
                    }
                }
            }

            // Big Primary Button: "Sync now" / Progress indicator
            if (!isSyncing && syncPhase != SyncPhase.FAILED) {
                OlloPrimaryButton(
                    text = if (syncPhase == SyncPhase.COMPLETED) "Sync Again" else "Sync now",
                    onClick = onStartSync,
                    icon = Icons.Default.Sync,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "sync_now_button"
                )
            }

            // Pair New Glasses Button
            OlloSecondaryButton(
                text = "Pair New Glasses",
                onClick = onPairNewDevice,
                icon = Icons.Default.Bluetooth,
                modifier = Modifier.fillMaxWidth(),
                testTag = "pair_new_glasses_button"
            )
        }
    }
}
