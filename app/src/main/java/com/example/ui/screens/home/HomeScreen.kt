package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.repository.CardWithFolderName
import com.example.ui.components.EmptyState
import com.example.ui.components.OlloAddFab
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.components.OlloSyncButton
import com.example.ui.components.StorageBar
import com.example.ui.theme.OlloTheme

data class FolderSummary(
    val id: Long,
    val name: String,
    val cardCount: Int,
    val includeInSync: Boolean,
    val updatedAtFormatted: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    folders: List<FolderSummary>,
    searchQuery: String,
    searchResults: List<CardWithFolderName>,
    isConnected: Boolean,
    usedStorageBytes: Long,
    totalStorageBytes: Long,
    onSearchQueryChange: (String) -> Unit,
    onFolderClick: (Long, String) -> Unit,
    onSearchResultClick: (Long, Long, String) -> Unit, // cardId, folderId, folderName
    onAddFolder: (String) -> Unit,
    onImportCsvNewFolder: (folderName: String, csvContent: String) -> Unit,
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var importFolderName by remember { mutableStateOf("") }
    var importCsvText by remember { mutableStateOf("") }

    val isSearching = searchQuery.isNotBlank()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "OllO",
                        style = MaterialTheme.typography.displayLarge,
                        color = colors.onBackground
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            importFolderName = "Imported Deck"
                            importCsvText = "front,back\nBonjour,Hello\nMerci,Thank you"
                            showImportDialog = true
                        },
                        modifier = Modifier.size(48.dp).testTag("import_csv_home_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Import CSV",
                            tint = colors.onBackground
                        )
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = colors.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Storage Bar
                StorageBar(
                    isConnected = isConnected,
                    usedBytes = usedStorageBytes,
                    totalBytes = totalStorageBytes,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Search field (searches all cards across folders)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag("search_field"),
                    placeholder = {
                        Text(
                            text = "Search all cards...",
                            color = colors.textMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = colors.textMuted
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = colors.textMuted
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground
                    )
                )

                if (isSearching) {
                    // Global Search Results View
                    Text(
                        text = "Search Results (${searchResults.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (searchResults.isEmpty()) {
                        EmptyState(
                            title = "No cards found",
                            subtitle = "No cards matched \"$searchQuery\"",
                            icon = Icons.Default.Search,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(searchResults, key = { it.card.id }) { item ->
                                SearchResultCard(
                                    item = item,
                                    onClick = {
                                        onSearchResultClick(item.card.id, item.card.folderId, item.folderName)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Standard Folders List
                    if (folders.isEmpty()) {
                        EmptyState(
                            title = "No folders yet",
                            subtitle = "Create a folder to start organizing your flashcards for smart glasses.",
                            icon = Icons.Default.Folder,
                            modifier = Modifier.weight(1f),
                            actionButton = {
                                OlloPrimaryButton(
                                    text = "Create Folder",
                                    onClick = { showAddFolderDialog = true },
                                    icon = Icons.Default.Add
                                )
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(folders, key = { it.id }) { folder ->
                                FolderItemCard(
                                    folder = folder,
                                    onClick = { onFolderClick(folder.id, folder.name) }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-left: Sync button with connection dot
            OlloSyncButton(
                isConnected = isConnected,
                onClick = onSyncClick,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
            )

            // Bottom-right: Add folder button
            OlloAddFab(
                contentDescription = "Add folder",
                onClick = {
                    newFolderName = ""
                    showAddFolderDialog = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                testTag = "add_folder_fab"
            )
        }
    }

    // Add folder dialog
    if (showAddFolderDialog) {
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "New Folder",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground
                )
            },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name", color = colors.textMuted) },
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
                    text = "Create",
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onAddFolder(newFolderName.trim())
                            showAddFolderDialog = false
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                )
            },
            dismissButton = {
                OlloSecondaryButton(
                    text = "Cancel",
                    onClick = { showAddFolderDialog = false }
                )
            }
        )
    }

    // Import CSV Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Import Flashcards CSV", color = colors.onBackground) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = importFolderName,
                        onValueChange = { importFolderName = it },
                        label = { Text("Destination Folder Name", color = colors.textMuted) },
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
                    OutlinedTextField(
                        value = importCsvText,
                        onValueChange = { importCsvText = it },
                        label = { Text("CSV (front,back per line)", color = colors.textMuted) },
                        modifier = Modifier.height(140.dp),
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
                }
            },
            confirmButton = {
                OlloPrimaryButton(
                    text = "Import",
                    onClick = {
                        if (importFolderName.isNotBlank() && importCsvText.isNotBlank()) {
                            onImportCsvNewFolder(importFolderName.trim(), importCsvText.trim())
                            showImportDialog = false
                        }
                    },
                    enabled = importFolderName.isNotBlank() && importCsvText.isNotBlank()
                )
            },
            dismissButton = {
                OlloSecondaryButton(text = "Cancel", onClick = { showImportDialog = false })
            }
        )
    }
}

@Composable
fun SearchResultCard(
    item: CardWithFolderName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("search_result_${item.card.id}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.card.frontText.ifEmpty { "[Front Image]" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.folderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent
                    )
                }
            }
            Text(
                text = item.card.backText.ifEmpty { "(Empty back)" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FolderItemCard(
    folder: FolderSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("folder_card_${folder.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${folder.cardCount} cards • Updated ${folder.updatedAtFormatted}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                }
            }

            // Sync inclusion indicator
            if (folder.includeInSync) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Included in sync",
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
