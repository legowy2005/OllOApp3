package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Provided once in OlloAppNavigation so any screen can show stored image previews. */
val LocalImagePreviewLoader = compositionLocalOf<suspend (Long) -> ImageBitmap?> { { null } }

/** Shows what the glasses will display for the given stored image id. */
@Composable
fun ImageThumbnail(
    imageId: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val loader = LocalImagePreviewLoader.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imageId) {
        value = loader(imageId)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Image preview",
                contentScale = contentScale,
                filterQuality = FilterQuality.None,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

