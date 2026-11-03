package de.skyslycer.briefkasten

import android.graphics.Bitmap
import kotlin.math.*

/** Analyse a bounded-size copy, then crop the original resolution. Originals in the vault are untouched. */
fun envelopeCutout(original: Bitmap): Bitmap {
    val ratio = min(1.0, 900.0 / max(original.width, original.height))
    val width = max(1, (original.width * ratio).roundToInt())
    val height = max(1, (original.height * ratio).roundToInt())
    val analysis = if (ratio < 1) Bitmap.createScaledBitmap(original, width, height, true) else original
    val pixels = IntArray(width * height)
    analysis.getPixels(pixels, 0, width, 0, 0, width, height)
    if (analysis !== original) analysis.recycle()
    for (i in pixels.indices) {
        val p = pixels[i]
        pixels[i] = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
    }
    val bounds = detectContentBounds(pixels, width, height) ?: return original
    val left = floor(bounds.left * original.width.toDouble() / width).toInt()
    val top = floor(bounds.top * original.height.toDouble() / height).toInt()
    val right = ceil(bounds.right * original.width.toDouble() / width).toInt().coerceAtMost(original.width)
    val bottom = ceil(bounds.bottom * original.height.toDouble() / height).toInt().coerceAtMost(original.height)
    return Bitmap.createBitmap(original, left, top, right - left, bottom - top)
}
