package com.example.ui.screens.card

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.AsciiTransliteration
import com.example.core.DeviceLimits
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.theme.OlloTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditorScreen(
    initialFrontText: String = "",
    initialBackText: String = "",
    hasFrontImage: Boolean = false,
    hasBackImage: Boolean = false,
    onSave: (frontText: String, backText: String) -> Unit,
    onBackClick: () -> Unit,
    onOpenImagePicker: (isFront: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    var frontRawText by remember { mutableStateOf(initialFrontText) }
    var backRawText by remember { mutableStateOf(initialBackText) }

    val frontCheck by remember(frontRawText) {
        derivedStateOf { AsciiTransliteration.sanitizeForGlasses(frontRawText) }
    }
    val backCheck by remember(backRawText) {
        derivedStateOf { AsciiTransliteration.sanitizeForGlasses(backRawText) }
    }

    val maxBytes = DeviceLimits.maxTextBytes

    val isFrontOverLimit = frontCheck.byteLength > maxBytes
    val isBackOverLimit = backCheck.byteLength > maxBytes

    // Validation: empty front text is allowed only if front has image
    val isFrontValid = (frontCheck.byteLength > 0 || hasFrontImage) && !isFrontOverLimit
    val isBackValid = !isBackOverLimit
    val canSave = isFrontValid && isBackValid

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Card Editor",
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
                actions = {
                    IconButton(
                        onClick = {
                            if (canSave) {
                                onSave(frontCheck.cleanText, backCheck.cleanText)
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.size(48.dp).testTag("save_card_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Save Card",
                            tint = if (canSave) colors.accent else colors.textFaint
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // FRONT SIDE CARD
            SideEditorBox(
                title = "Front Side",
                text = frontRawText,
                onTextChange = { frontRawText = it },
                byteCount = frontCheck.byteLength,
                maxBytes = maxBytes,
                hasModifications = frontCheck.hasModifications,
                isOverLimit = isFrontOverLimit,
                hasImage = hasFrontImage,
                onAddImageClick = { onOpenImagePicker(true) },
                testTag = "front_text_field"
            )

            // BACK SIDE CARD
            SideEditorBox(
                title = "Back Side (Optional)",
                text = backRawText,
                onTextChange = { backRawText = it },
                byteCount = backCheck.byteLength,
                maxBytes = maxBytes,
                hasModifications = backCheck.hasModifications,
                isOverLimit = isBackOverLimit,
                hasImage = hasBackImage,
                onAddImageClick = { onOpenImagePicker(false) },
                testTag = "back_text_field"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Save and Cancel buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OlloSecondaryButton(
                    text = "Cancel",
                    onClick = onBackClick,
                    modifier = Modifier.weight(1f)
                )
                OlloPrimaryButton(
                    text = "Save Card",
                    onClick = {
                        if (canSave) {
                            onSave(frontCheck.cleanText, backCheck.cleanText)
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    testTag = "save_card_button_main"
                )
            }
        }
    }
}

@Composable
fun SideEditorBox(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    byteCount: Int,
    maxBytes: Int,
    hasModifications: Boolean,
    isOverLimit: Boolean,
    hasImage: Boolean,
    onAddImageClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(
                1.dp,
                if (isOverLimit) colors.error else colors.outline,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground
                )

                // Live byte counter: e.g. "37 / 100"
                Text(
                    text = "$byteCount / $maxBytes bytes",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOverLimit) colors.error else colors.textMuted
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .testTag(testTag),
                placeholder = {
                    Text(
                        text = "Enter text (ASCII only for smart glasses)...",
                        color = colors.textFaint
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceElevated,
                    unfocusedContainerColor = colors.surfaceElevated,
                    focusedBorderColor = if (isOverLimit) colors.error else colors.accent,
                    unfocusedBorderColor = if (isOverLimit) colors.error else colors.outline,
                    focusedTextColor = colors.onBackground,
                    unfocusedTextColor = colors.onBackground
                )
            )

            // Warning if non-ASCII diacritics were transliterated
            if (hasModifications) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = colors.warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Characters with accents will be transliterated to ASCII for glasses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }

            // Image attachment preview / pick button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasImage) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "1-bit Image Attached",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent
                        )
                    }
                } else {
                    Text(
                        text = "Optional 1-bit display image",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                }

                OlloSecondaryButton(
                    text = if (hasImage) "Edit Image" else "Attach Image",
                    onClick = onAddImageClick
                )
            }
        }
    }
}
