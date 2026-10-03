package com.example.ui.screens.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.screens.folder.CardSummary
import com.example.ui.theme.OlloTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePreviewScreen(
    folderName: String,
    cards: List<CardSummary>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OlloTheme.colors

    var currentIndex by remember { mutableIntStateOf(0) }
    var isFrontSide by remember { mutableStateOf(true) }

    val currentCard = cards.getOrNull(currentIndex)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "$folderName Preview",
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Card Counter
            Text(
                text = if (cards.isNotEmpty()) "Card ${currentIndex + 1} of ${cards.size}" else "No cards",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted
            )

            if (currentCard != null) {
                // Interactive Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.outline, RoundedCornerShape(24.dp))
                        .clickable { isFrontSide = !isFrontSide }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isFrontSide) "FRONT" else "BACK",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        AnimatedContent(
                            targetState = if (isFrontSide) currentCard.frontText else currentCard.backText,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "flip_text"
                        ) { text ->
                            Text(
                                text = text.ifEmpty { if (isFrontSide) "[Image]" else "(Empty)" },
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.onBackground,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tap to flip",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted
                            )
                        }
                    }
                }

                // Navigation controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (currentIndex > 0) {
                                currentIndex--
                                isFrontSide = true
                            }
                        },
                        enabled = currentIndex > 0,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous card",
                            tint = if (currentIndex > 0) colors.onBackground else colors.textFaint
                        )
                    }

                    IconButton(
                        onClick = {
                            if (currentIndex < cards.size - 1) {
                                currentIndex++
                                isFrontSide = true
                            }
                        },
                        enabled = currentIndex < cards.size - 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next card",
                            tint = if (currentIndex < cards.size - 1) colors.onBackground else colors.textFaint
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No cards to preview in this folder.", color = colors.textMuted)
                }
            }
        }
    }
}
