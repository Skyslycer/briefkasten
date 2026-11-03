package de.skyslycer.briefkasten

/** Prefer the tested 2x pass; keep 3x as independent matching evidence, never a fractional resize. */
fun ocrScales(width: Int, height: Int): List<Int> {
    require(width > 0 && height > 0)
    return listOf(2, 3).filter { scale ->
        maxOf(width, height).toLong() * scale <= 6000 &&
            width.toLong() * height <= 16_000_000L / (scale * scale)
    }
}
