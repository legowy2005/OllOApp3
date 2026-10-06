package com.example.ui.screens.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.DeviceLimits
import com.example.core.ImageProcessor
import com.example.core.ProcessedImageResult
import com.example.ui.components.OlloPrimaryButton
import com.example.ui.components.OlloSecondaryButton
import com.example.ui.theme.OlloTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    onBackClick: () -> Unit,
    onSaveImage: (ProcessedImageResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = OlloTheme.colors

    // Source Bitmap (default sample graphic, or user chosen photo)
    var currentSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val baseBitmap = remember(currentSourceBitmap) {
        currentSourceBitmap ?: ImageProcessor.createSampleGraphic(320, 240)
    }

    // Photo picker launcher (Android standard PickVisualMedia)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = ImageProcessor.decodeSampledBitmapFromUri(context, uri)
            if (decoded != null) {
                currentSourceBitmap = decoded
            }
        }
    }

    // Resolution selection (supports 320x240 up to 640x480 as requested)
    var selectedWidth by remember { mutableIntStateOf(640) }
    var selectedHeight by remember { mutableIntStateOf(480) }
    var isAspectLocked by remember { mutableStateOf(true) }

    // Transformations
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }
    var flipVertical by remember { mutableStateOf(false) }
    var useDithering by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(128f) }
    var isInverted by remember { mutableStateOf(false) }
    var isColorMode by remember { mutableStateOf(false) }

    // Cropping controls (normalized 0f to 1f)
    var showCropControls by remember { mutableStateOf(false) }
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(1f) }
    var cropBottom by remember { mutableFloatStateOf(1f) }

    // Run conversion pipeline
    val processedResult by remember(
        baseBitmap,
        selectedWidth,
        selectedHeight,
        rotationDegrees,
        flipHorizontal,
        flipVertical,
        cropLeft,
        cropTop,
        cropRight,
        cropBottom,
        useDithering,
        threshold,
        isInverted,
        isColorMode
    ) {
        derivedStateOf {
            ImageProcessor.processImage(
                sourceBitmap = baseBitmap,
                targetWidth = selectedWidth,
                targetHeight = selectedHeight,
                rotationDegrees = rotationDegrees,
                flipHorizontal = flipHorizontal,
                flipVertical = flipVertical,
                cropLeftRatio = cropLeft,
                cropTopRatio = cropTop,
                cropRightRatio = cropRight,
                cropBottomRatio = cropBottom,
                useDithering = useDithering,
                threshold = threshold.toInt(),
                invert = isInverted,
                isColorMode = isColorMode
            )
        }
    }

    // Unpack 1-bit packed bytes into exact OLED preview image bitmap (white on black)
    val oledPreviewImageBitmap by remember(processedResult) {
        derivedStateOf {
            ImageProcessor.createPreviewBitmap(
                processedResult.deviceData,
                processedResult.deviceWidth,
                processedResult.deviceHeight,
                processedResult.format
            ).asImageBitmap()
        }
    }

    val isAboveDeviceLimit = if (isColorMode) {
        selectedWidth > DeviceLimits.COLOR_MAX_W || selectedHeight > DeviceLimits.COLOR_MAX_H
    } else {
        selectedWidth > DeviceLimits.deviceMaxW || selectedHeight > DeviceLimits.deviceMaxH
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Image Editor",
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
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.size(48.dp).testTag("pick_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Pick Photo",
                            tint = colors.accent
                        )
                    }

                    IconButton(
                        onClick = { onSaveImage(processedResult) },
                        modifier = Modifier.size(48.dp).testTag("save_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Apply Image",
                            tint = colors.accent
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
            // Live "Glasses" Display Preview Panel (OLED 1-bit: lit pixels white on black background)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Glasses Live Preview (1-bit OLED)",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground
                )

                Text(
                    text = "${processedResult.deviceWidth}x${processedResult.deviceHeight}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent
                )
            }

            // OLED display screen (lit pixels white, unlit black)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .border(2.dp, colors.outline, RoundedCornerShape(16.dp))
                    .testTag("glasses_preview_panel"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = oledPreviewImageBitmap,
                    contentDescription = "1-bit OLED preview rendered directly from packed bytes",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None // Pixelated sharp rendering matching hardware
                )
            }

            // Status details as specified in Section 7:
            // "Glasses: 320x240, 1-bit, 9,600 / 10,240 bytes"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceElevated)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Glasses: ${processedResult.deviceWidth}x${processedResult.deviceHeight}, ${when (processedResult.format) { 1 -> "RGB565 color"; 2 -> "2-bit gray"; else -> "1-bit" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted
                    )
                    Text(
                        text = "${processedResult.deviceData.size} / ${DeviceLimits.maxImageBytes} bytes",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (processedResult.deviceData.size <= DeviceLimits.maxImageBytes) colors.accent else colors.warning
                    )
                }
            }

            // Warning if chosen size is above device limit (Section 7)
            if (isAboveDeviceLimit) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.warning, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Target resolution (${selectedWidth}x${selectedHeight}) exceeds the limit for this mode (${if (isColorMode) "${DeviceLimits.COLOR_MAX_W}x${DeviceLimits.COLOR_MAX_H} color" else "${DeviceLimits.deviceMaxW}x${DeviceLimits.deviceMaxH} gray"}). The glasses will receive a version scaled down to ${processedResult.deviceWidth}x${processedResult.deviceHeight} (aspect ratio preserved), while the phone stores the high-resolution copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning
                        )
                    }
                }
            }

            // Choose Image Source (Photo Picker button or Preset Graphic)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OlloSecondaryButton(
                    text = "Pick From Photos",
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "pick_photo_action_btn"
                )

                OlloSecondaryButton(
                    text = "Sample Glyph",
                    onClick = { currentSourceBitmap = null },
                    modifier = Modifier.weight(1f)
                )
            }

            // Resolution Presets Selector (320x240 to 640x480)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Resolution (320x240 min to 640x480 max)",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ImageProcessor.RESOLUTION_PRESETS.forEach { preset ->
                        val isSelected = selectedWidth == preset.width && selectedHeight == preset.height
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedWidth = preset.width
                                selectedHeight = preset.height
                            },
                            label = { Text(preset.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accentMuted,
                                selectedLabelColor = colors.accent
                            )
                        )
                    }
                }
            }

            // Mode: Black and White vs Color
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !isColorMode,
                    onClick = { isColorMode = false },
                    label = { Text("Gray (2-bit)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accentMuted,
                        selectedLabelColor = colors.accent
                    )
                )
                FilterChip(
                    selected = isColorMode,
                    onClick = { isColorMode = true },
                    label = { Text("Color RGB565 (experimental)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accentMuted,
                        selectedLabelColor = colors.accent
                    )
                )
            }

            // Custom Resolution Sliders
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dimensions (${selectedWidth}x${selectedHeight})", style = MaterialTheme.typography.labelMedium, color = colors.onBackground)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Lock Ratio", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            IconButton(onClick = { isAspectLocked = !isAspectLocked }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = if (isAspectLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock ratio",
                                    tint = if (isAspectLocked) colors.accent else colors.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Width: $selectedWidth px", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            Slider(
                                value = selectedWidth.toFloat(),
                                onValueChange = {
                                    val newW = it.toInt()
                                    if (isAspectLocked) {
                                        val ratio = selectedHeight.toFloat() / selectedWidth.toFloat()
                                        selectedHeight = (newW * ratio).toInt().coerceIn(DeviceLimits.EDITOR_MIN_H, DeviceLimits.EDITOR_MAX_H)
                                    }
                                    selectedWidth = newW
                                },
                                valueRange = 320f..640f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent
                                )
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Height: $selectedHeight px", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            Slider(
                                value = selectedHeight.toFloat(),
                                onValueChange = {
                                    val newH = it.toInt()
                                    if (isAspectLocked) {
                                        val ratio = selectedWidth.toFloat() / selectedHeight.toFloat()
                                        selectedWidth = (newH * ratio).toInt().coerceIn(DeviceLimits.EDITOR_MIN_W, DeviceLimits.EDITOR_MAX_W)
                                    }
                                    selectedHeight = newH
                                },
                                valueRange = 240f..480f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent
                                )
                            )
                        }
                    }

                    OlloSecondaryButton(
                        text = "Reset to 320x240 (min)",
                        onClick = {
                            selectedWidth = 320
                            selectedHeight = 240
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Transform Actions Row (Rotate 90, Flip, Invert, Crop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = { rotationDegrees = (rotationDegrees - 90f) % 360f },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.RotateLeft, contentDescription = "Rotate Left 90", tint = colors.onBackground)
                }
                IconButton(
                    onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate Right 90", tint = colors.onBackground)
                }
                IconButton(
                    onClick = { flipHorizontal = !flipHorizontal },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Flip, contentDescription = "Flip Horizontal", tint = if (flipHorizontal) colors.accent else colors.onBackground)
                }
                IconButton(
                    onClick = { isInverted = !isInverted },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.InvertColors,
                        contentDescription = "Invert Pixels",
                        tint = if (isInverted) colors.accent else colors.onBackground
                    )
                }
                IconButton(
                    onClick = { showCropControls = !showCropControls },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Crop,
                        contentDescription = "Toggle Crop Controls",
                        tint = if (showCropControls) colors.accent else colors.onBackground
                    )
                }
            }

            // Crop Sliders
            AnimatedVisibility(visible = showCropControls) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceElevated)
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Crop Insets", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = cropLeft == 0f && cropTop == 0f && cropRight == 1f && cropBottom == 1f,
                                onClick = {
                                    cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f
                                },
                                label = { Text("Reset Crop") }
                            )
                            FilterChip(
                                selected = cropLeft == 0.1f && cropRight == 0.9f,
                                onClick = {
                                    cropLeft = 0.1f; cropTop = 0.1f; cropRight = 0.9f; cropBottom = 0.9f
                                },
                                label = { Text("Inset 10%") }
                            )
                        }

                        Text("Horizontal Crop: ${(cropLeft * 100).toInt()}% to ${(cropRight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Slider(
                            value = cropLeft,
                            onValueChange = { cropLeft = it.coerceAtMost(cropRight - 0.2f) },
                            valueRange = 0f..0.8f,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
                        )

                        Text("Vertical Crop: ${(cropTop * 100).toInt()}% to ${(cropBottom * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Slider(
                            value = cropTop,
                            onValueChange = { cropTop = it.coerceAtMost(cropBottom - 0.2f) },
                            valueRange = 0f..0.8f,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
                        )
                    }
                }
            }

            // Dithering vs Threshold
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Floyd-Steinberg Dithering", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    Text("Smooth grayscale gradations for glasses display", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
                Switch(
                    checked = useDithering,
                    onCheckedChange = { useDithering = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primaryButtonText,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textMuted,
                        uncheckedTrackColor = colors.surface
                    )
                )
            }

            // Threshold control (when dithering is off)
            if (!useDithering) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Threshold", style = MaterialTheme.typography.labelMedium, color = colors.onBackground)
                        Text("${threshold.toInt()}", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                    }
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
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
                    text = "Save Image",
                    onClick = { onSaveImage(processedResult) },
                    modifier = Modifier.weight(1f),
                    testTag = "save_image_action_button"
                )
            }
        }
    }
}
