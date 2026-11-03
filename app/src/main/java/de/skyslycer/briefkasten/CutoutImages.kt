package de.skyslycer.briefkasten

import android.graphics.*
import java.io.ByteArrayOutputStream
import kotlin.math.*

/** Only cutouts are corrected. Full views and archive exports always use the original. */
class CutoutImages(private val vault: Vault) {
    @Synchronized fun bytes(letter: Letter): ByteArray {
        val name = "${letter.id}.cutout-v1"
        vault.read(name)?.let { return it }
        val source = vault.read(letter.fileName) ?: error("Archived image missing")
        val original = BitmapFactory.decodeByteArray(source, 0, source.size) ?: error("Unreadable image")
        try {
            val cutout = deskewedCutout(original)
            try {
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(cutout.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
                vault.write(name, bytes)
                vault.remove("${letter.id}.deskew-v1")
                return bytes
            } finally { if (cutout !== original) cutout.recycle() }
        } finally { original.recycle() }
    }
}

/** Detect the text slope after cropping, then correct only the full-resolution cutout. */
fun deskewedCutout(original: Bitmap): Bitmap {
    val crop = envelopeCutout(original)
    try {
        val ratio = min(1.0, 900.0 / max(crop.width, crop.height))
        val analysis = if (ratio < 1) Bitmap.createScaledBitmap(crop,
            max(1, (crop.width * ratio).roundToInt()), max(1, (crop.height * ratio).roundToInt()), true) else crop
        val angle = try {
            val gray = IntArray(analysis.width * analysis.height)
            analysis.getPixels(gray, 0, analysis.width, 0, 0, analysis.width, analysis.height)
            for (i in gray.indices) {
                val p = gray[i]
                gray[i] = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
            }
            deskewAngle(gray, analysis.width, analysis.height)
        } finally { if (analysis !== crop) analysis.recycle() }
        if (angle == 0.0) return crop
        val rotated = rotateEnvelope(crop, angle)
        if (crop !== original) crop.recycle()
        try {
            val trimmed = envelopeCutout(rotated)
            if (trimmed !== rotated) rotated.recycle()
            return trimmed
        } catch (e: Exception) { rotated.recycle(); throw e }
    } catch (e: Exception) { if (crop !== original && !crop.isRecycled) crop.recycle(); throw e }
}

/** Rotate original pixels once onto an expanded white canvas, preserving every corner. */
fun rotateEnvelope(original: Bitmap, angle: Double): Bitmap {
    val radians = Math.toRadians(angle)
    val width = ceil(original.width * abs(cos(radians)) + original.height * abs(sin(radians))).toInt()
    val height = ceil(original.height * abs(cos(radians)) + original.width * abs(sin(radians))).toInt()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
        Canvas(result).apply {
            drawColor(Color.WHITE)
            translate(width / 2f, height / 2f)
            rotate(angle.toFloat())
            drawBitmap(original, -original.width / 2f, -original.height / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
}
