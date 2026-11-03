package de.skyslycer.briefkasten

/** Native-pixel regions; overlap keeps characters near tile boundaries available. */
fun ocrTiles(width: Int, height: Int): List<ContentBounds> {
    require(width > 0 && height > 0)
    val result = mutableListOf<ContentBounds>()
    var top = 0
    while (true) {
        val bottom = minOf(height, top + 1600)
        var left = 0
        while (true) {
            val right = minOf(width, left + 1600)
            result += ContentBounds(left, top, right, bottom)
            if (right == width) break
            left = right - 192
        }
        if (bottom == height) break
        top = bottom - 192
    }
    return result
}
