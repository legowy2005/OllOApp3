package com.example.ui.screens.folder

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.EmptyState
import com.example.ui.components.OlloAddFab
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.screens.home.FolderSummary
import com.example.ui.theme.OlloTheme

data class CardSummary(
    val id: Long,
    val folderId: Long,
    val sortOrder: Int,
    val frontText: String,
    val backText: String,
    val hasFrontImage: Boolean = false,
    val hasBackImage: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    folderId: Long,
    folderName: String,
    cards: List<CardSummary>,
    allFolders: List<FolderSummary>,
    includeInSync: Boolean,
    onBackClick: () -> Unit,
    onAddCardClick: () -> Unit,
    onEditCardClick: (Long) -> Unit,
    onDuplicateCard: (Long) -> Unit,
    onDeleteCard: (Long) -> Unit,
    onMoveCard: (cardId: Long, targetFolderId: Long) -> Unit,
    onReorderCards: (List<Long>) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: () -> Unit,
    onToggleIncludeInSync: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: (String) -> Unit,
    onPreviewClick: () -> Unit,
    exportedCsvContent: String?,
    onDismissExportDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf(folderName) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importCsvText by remember { mutableStateOf("") }

    // Card Move Dialog state
    var cardToMove by remember { mutableStateOf<CardSummary?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                actions = {
                    if (cards.isNotEmpty()) {
                        IconButton(
                            onClick = onPreviewClick,
                            modifier = Modifier.size(48.dp).testTag("preview_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Preview cards",
                                tint = colors.onBackground
                            )
                        }
                    }
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(48.dp).testTag("folder_options_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Folder options",
                            tint = colors.onBackground
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(colors.surfaceElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = colors.onBackground) },
                            onClick = {
                                showMenu = false
                                newFolderName = folderName
                                showRenameDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = colors.onBackground)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Include in sync", color = colors.onBackground)
                                    Switch(
                                        checked = includeInSync,
                                        onCheckedChange = {
                                            onToggleIncludeInSync(it)
                                            showMenu = false
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = colors.primaryButtonText,
                                            checkedTrackColor = colors.accent,
                                            uncheckedThumbColor = colors.textMuted,
                                            uncheckedTrackColor = colors.surface
                                        )
                                    )
                                }
                            },
                            onClick = {
                                onToggleIncludeInSync(!includeInSync)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = colors.accent)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export CSV", color = colors.onBackground) },
                            onClick = {
                                showMenu = false
                                onExportCsv()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, contentDescription = null, tint = colors.onBackground)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import CSV into this folder", color = colors.onBackground) },
                            onClick = {
                                showMenu = false
                                importCsvText = ""
                                showImportDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FileUpload, contentDescription = null, tint = colors.onBackground)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete folder", color = colors.error) },
                            onClick = {
                                showMenu = false
                                showDeleteConfirmDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (cards.isEmpty()) {
                EmptyState(
                    title = "No cards in this folder",
                    subtitle = "Tap Add Card to create your first flashcard for this folder.",
                    icon = Icons.Default.Style,
                    modifier = Modifier.fillMaxSize(),
                    actionButton = {
                        OlloPrimaryButton(
                            text = "Add Card",
                            onClick = onAddCardClick,
                            icon = Icons.Default.Add
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
                        CardRowItemWithActions(
                            index = index + 1,
                            card = card,
                            isFirst = index == 0,
                            isLast = index == cards.size - 1,
                            onMoveUp = {
                                if (index > 0) {
                                    val mutable = cards.map { it.id }.toMutableList()
                                    val temp = mutable[index]
                                    mutable[index] = mutable[index - 1]
                                    mutable[index - 1] = temp
                                    onReorderCards(mutable)
                                }
                            },
                            onMoveDown = {
                                if (index < cards.size - 1) {
                                    val mutable = cards.map { it.id }.toMutableList()
                                    val temp = mutable[index]
                                    mutable[index] = mutable[index + 1]
                                    mutable[index + 1] = temp
                                    onReorderCards(mutable)
                                }
                            },
                            onClick = { onEditCardClick(card.id) },
                            onEdit = { onEditCardClick(card.id) },
                            onDuplicate = { onDuplicateCard(card.id) },
                            onMove = { cardToMove = card },
                            onDelete = { onDeleteCard(card.id) }
                        )
                    }
                }
            }

            // Bottom-right Add button: contentDescription and icon changes to "Add card"
            OlloAddFab(
                contentDescription = "Add card",
                onClick = onAddCardClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                testTag = "add_card_fab"
            )
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Rename Folder", color = colors.onBackground) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
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
                        if (newFolderName.isNotBlank()) {
                            onRenameFolder(newFolderName.trim())
                            showRenameDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                OlloSecondaryButton(text = "Cancel", onClick = { showRenameDialog = false })
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Delete \"$folderName\"?", color = colors.onBackground) },
            text = {
                Text(
                    text = "This will permanently delete this folder and all ${cards.size} cards inside it.",
                    color = colors.textMuted
                )
            },
            confirmButton = {
                OlloPrimaryButton(
                    text = "Delete",
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteFolder()
                    }
                )
            },
            dismissButton = {
                OlloSecondaryButton(text = "Cancel", onClick = { showDeleteConfirmDialog = false })
            }
        )
    }

    // Move Card Dialog
    if (cardToMove != null) {
        val targetFolders = allFolders.filter { it.id != folderId }
        AlertDialog(
            onDismissRequest = { cardToMove = null },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Move Card To...", color = colors.onBackground) },
            text = {
                if (targetFolders.isEmpty()) {
                    Text("No other folders available to move this card into.", color = colors.textMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetFolders.forEach { target ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surface)
                                    .clickable {
                                        onMoveCard(cardToMove!!.id, target.id)
                                        cardToMove = null
                                    }
                                    .padding(14.dp)
                            ) {
                                Text(target.name, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OlloSecondaryButton(text = "Cancel", onClick = { cardToMove = null })
            }
        )
    }

    // Exported CSV dialog
    if (exportedCsvContent != null) {
        AlertDialog(
            onDismissRequest = onDismissExportDialog,
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Exported CSV", color = colors.onBackground) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CSV format (front,back):", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    OutlinedTextField(
                        value = exportedCsvContent,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedTextColor = colors.onBackground,
                            unfocusedTextColor = colors.onBackground
                        )
                    )
                }
            },
            confirmButton = {
                OlloPrimaryButton(text = "Done", onClick = onDismissExportDialog)
            }
        )
    }

    // Import CSV into this folder dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = colors.surfaceElevated,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Import CSV into $folderName", color = colors.onBackground) },
            text = {
                OutlinedTextField(
                    value = importCsvText,
                    onValueChange = { importCsvText = it },
                    label = { Text("Paste front,back lines", color = colors.textMuted) },
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
            },
            confirmButton = {
                OlloPrimaryButton(
                    text = "Import",
                    onClick = {
                        if (importCsvText.isNotBlank()) {
                            onImportCsv(importCsvText.trim())
                            showImportDialog = false
                        }
                    },
                    enabled = importCsvText.isNotBlank()
                )
            },
            dismissButton = {
                OlloSecondaryButton(text = "Cancel", onClick = { showImportDialog = false })
            }
        )
    }
}

@Composable
fun CardRowItemWithActions(
    index: Int,
    card: CardSummary,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors
    var showCardMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("card_item_${card.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (card.frontText.isNotBlank()) card.frontText else "[Front image]",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (card.hasFrontImage) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Front has image",
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (card.backText.isNotBlank()) card.backText else if (card.hasBackImage) "[Back image]" else "(Empty back)",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (card.hasBackImage) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Back has image",
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Quick reordering arrows (sync order inside folder)
            IconButton(
                onClick = onMoveUp,
                enabled = !isFirst,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Move card up",
                    tint = if (!isFirst) colors.textMuted else colors.surfaceElevated,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onMoveDown,
                enabled = !isLast,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Move card down",
                    tint = if (!isLast) colors.textMuted else colors.surfaceElevated,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Card Menu
            Box {
                IconButton(
                    onClick = { showCardMenu = true },
                    modifier = Modifier.size(36.dp).testTag("card_menu_${card.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Card options",
                        tint = colors.onBackground,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showCardMenu,
                    onDismissRequest = { showCardMenu = false },
                    modifier = Modifier.background(colors.surfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit card", color = colors.onBackground) },
                        onClick = {
                            showCardMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = colors.onBackground) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate card", color = colors.onBackground) },
                        onClick = {
                            showCardMenu = false
                            onDuplicate()
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.onBackground) }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to another folder", color = colors.onBackground) },
                        onClick = {
                            showCardMenu = false
                            onMove()
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, tint = colors.onBackground) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete card", color = colors.error) },
                        onClick = {
                            showCardMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error) }
                    )
                }
            }
        }
    }
}
