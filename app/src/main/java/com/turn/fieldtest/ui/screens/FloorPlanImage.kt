package com.turn.fieldtest.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FloorPlanImage(
    contentUri: String,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val resolver = LocalContext.current.contentResolver
    var bitmap by remember(contentUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(contentUri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(contentUri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Imported floor-plan background",
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            alpha = alpha,
        )
    }
}
