package de.skyslycer.briefkasten

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** Original pixels, no paper-shaped container or image padding. Zoom stays anchored to the fingers. */
@Composable
fun ZoomableEnvelope(bitmap: Bitmap, description: String, fit: Boolean = false) {
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    fun transform(centroid: Offset, pan: Offset, nextScale: Float) {
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val next = (offset + center - centroid) * (nextScale / scale) + centroid - center + pan
        scale = nextScale
        offset = next
    }
    Box(Modifier.fillMaxSize().clipToBounds()
        .onSizeChanged { viewport = it }
        .pointerInput(bitmap) {
            detectTapGestures(onDoubleTap = { tap ->
                if (scale != 1f || offset != Offset.Zero) { scale = 1f; offset = Offset.Zero }
                else transform(tap, Offset.Zero, 3f)
            })
        }
        .pointerInput(bitmap) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                transform(centroid, pan, (scale * zoom).coerceIn(0.25f, 16f))
            }
        }) {
        Image(bitmap.asImageBitmap(), contentDescription = "$description. Pinch to zoom; double-tap to zoom or reset.",
            contentScale = if (fit) ContentScale.Fit else ContentScale.Inside,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            })
    }
}
