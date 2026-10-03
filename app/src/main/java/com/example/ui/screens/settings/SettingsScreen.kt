package com.example.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import com.example.ble.ScannedDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.theme.OlloTheme

data class BleLogEntry(
    val timestamp: String,
    val direction: String, // "APP -> GLASSES" or "GLASSES -> APP"
    val packetName: String,
    val hexData: String,
    val notes: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    storageBudgetKb: Int,
    autoReconnect: Boolean,
    useSimulatedGlasses: Boolean,
    packetLogs: List<BleLogEntry>,
    isConnected: Boolean,
    connectedDeviceName: String?,
    scanResults: List<ScannedDevice>,
    isScanning: Boolean,
    requiredPermissions: Array<String>,
    hasPermissions: () -> Boolean,
    isBluetoothOn: () -> Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onUpdateBudgetKb: (Int) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleSimulatedGlasses: (Boolean) -> Unit,
    onForgetGlasses: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    var showBudgetDialog by remember { mutableStateOf(false) }
    var budgetInput by remember { mutableStateOf(storageBudgetKb.toString()) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    fun beginScan() {
        if (!isBluetoothOn()) {
            scanMessage = "Turn Bluetooth on first"
            return
        }
        scanMessage = null
        showDevicesDialog = true
        onStartScan()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) beginScan()
        else scanMessage = "Bluetooth permission is required to find the glasses"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
            // Section: Storage Budget
            SettingsGroup(title = "Storage Estimation") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Offline Storage Budget", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                        Text(
                            text = "Used for estimation when disconnected: $storageBudgetKb KB (~${storageBudgetKb / 1024} MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    }
                    OlloSecondaryButton(
                        text = "Edit",
                        onClick = {
                            budgetInput = storageBudgetKb.toString()
                            showBudgetDialog = true
                        }
                    )
                }
            }

            // Section: Connection
            SettingsGroup(title = "Glasses Connection") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Reconnect", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                        Text(
                            text = "Reconnect immediately when glasses are discovered",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = onToggleAutoReconnect,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.primaryButtonText,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surface
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isConnected) "Connected: ${connectedDeviceName ?: "Ollo"}" else "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isConnected) colors.accent else colors.textMuted
                )
                scanMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning)
                }

                OlloPrimaryButton(
                    text = "Add device",
                    onClick = {
                        if (hasPermissions()) beginScan() else permissionLauncher.launch(requiredPermissions)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "add_device_button"
                )

                if (isConnected) {
                    OlloSecondaryButton(
                        text = "Disconnect",
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OlloSecondaryButton(
                    text = "Forget Paired Glasses",
                    onClick = onForgetGlasses,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "forget_glasses_button"
                )
            }

            // Section: Developer
            SettingsGroup(title = "Developer & Diagnostics") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use Simulated Glasses", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                        Text(
                            text = "Emulate ESP32-S3 firmware locally for testing without physical glasses",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    }
                    Switch(
                        checked = useSimulatedGlasses,
                        onCheckedChange = onToggleSimulatedGlasses,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.primaryButtonText,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surface
                        ),
                        modifier = Modifier.testTag("simulate_glasses_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OlloSecondaryButton(
                    text = "View BLE Packet Log (${packetLogs.size}/500)",
                    onClick = { showLogsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "view_packet_logs_button"
                )
            }
        }
    }

    // Available devices dialog
    if (showDevicesDialog) {
        AlertDialog(
            onDismissRequest = {
                onStopScan()
                showDevicesDialog = false
            },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Available devices", color = colors.onBackground) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                        Text(
                            text = if (isScanning) "Scanning..." else "Scan finished",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    }
                    Box(modifier = Modifier.height(260.dp)) {
                        if (scanResults.isEmpty()) {
                            Text(
                                text = if (isScanning) "Looking for glasses nearby. Make sure they are powered on." else "No devices found. Tap Rescan.",
                                color = colors.textMuted
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(scanResults) { device ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.surface)
                                            .border(1.dp, if (device.isOllo) colors.accent else colors.outline, RoundedCornerShape(10.dp))
                                            .clickable {
                                                onStopScan()
                                                showDevicesDialog = false
                                                onConnectDevice(device.address)
                                            }
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(device.name, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                                                Text(device.address, style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
                                            }
                                            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OlloPrimaryButton(text = "Rescan", onClick = { onStartScan() })
            },
            dismissButton = {
                OlloSecondaryButton(text = "Close", onClick = {
                    onStopScan()
                    showDevicesDialog = false
                })
            }
        )
    }

    // Budget Dialog
    if (showBudgetDialog) {
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Storage Budget (KB)", color = colors.onBackground) },
            text = {
                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = { budgetInput = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground
                    )
                )
            },
            confirmButton = {
                OlloPrimaryButton(
                    text = "Save",
                    onClick = {
                        val parsed = budgetInput.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            onUpdateBudgetKb(parsed)
                            showBudgetDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                OlloSecondaryButton(
                    text = "Cancel",
                    onClick = { showBudgetDialog = false }
                )
            }
        )
    }

    // Packet Logs Dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("BLE Packet Log (${packetLogs.size})", color = colors.onBackground) },
            text = {
                Box(modifier = Modifier.height(300.dp)) {
                    if (packetLogs.isEmpty()) {
                        Text(
                            text = "No packets recorded yet. Connect or sync to view GATT traffic.",
                            color = colors.textMuted
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(packetLogs) { log ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surface)
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(log.direction, style = MaterialTheme.typography.labelSmall, color = colors.accent)
                                            Text(log.timestamp, style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
                                        }
                                        Text(log.packetName, style = MaterialTheme.typography.labelMedium, color = colors.onBackground)
                                        Text(log.hexData, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OlloPrimaryButton(text = "Close", onClick = { showLogsDialog = false })
            }
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = OlloTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent
        )
        content()
    }
}
