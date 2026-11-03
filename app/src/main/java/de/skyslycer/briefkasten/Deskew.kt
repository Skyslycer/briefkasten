package de.skyslycer.briefkasten

import kotlin.math.*

/** Correction angle in screen coordinates; uncertain or already straight scans stay unchanged. */
fun deskewAngle(gray: IntArray, width: Int, height: Int): Double {
    require(gray.size == width * height)
    if (width < 32 || height < 32) return 0.0
    val histogram = IntArray(256)
    gray.forEach { histogram[it.coerceIn(0, 255)]++ }
    var cumulative = 0
    val paper = (0..255).first { cumulative += histogram[it]; cumulative >= gray.size * .85 }
    val ink = BooleanArray(gray.size) { gray[it] < paper - 50 }
    val points = ArrayList<Int>()
    val queue = IntArray(gray.size)
    var glyphs = 0
    for (start in ink.indices) {
        if (!ink[start]) continue
        var head = 0; var tail = 1
        queue[0] = start; ink[start] = false
        var l = width; var r = 0; var t = height; var b = 0
        while (head < tail) {
            val p = queue[head++]; val x = p % width; val y = p / width
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (ink[next]) { ink[next] = false; queue[tail++] = next }
            }
        }
        val w = r - l + 1; val h = b - t + 1
        // Estimate from glyph-sized marks, excluding scanner bands, seams and matrix codes.
        if (tail < 3 || w > width * .12 || h > height * .15 ||
            w > h * 8 || h > w * 12 || l <= 1 || t <= 1 || r >= width - 2 || b >= height - 2) continue
        glyphs++
        for (i in 0 until tail) points.add(queue[i])
    }
    if (glyphs < 20 || points.size < 150) return 0.0
    val margin = width / 5 + 4
    fun score(angle: Double): Double {
        val slope = tan(Math.toRadians(angle))
        val rows = DoubleArray(height + margin * 2)
        for (p in points) {
            val y = p / width + (p % width - width / 2.0) * slope + margin
            val row = floor(y).toInt(); val fraction = y - row
            if (row >= 0 && row + 1 < rows.size) {
                rows[row] += 1 - fraction; rows[row + 1] += fraction
            }
        }
        return (1 until rows.size).sumOf { (rows[it] - rows[it - 1]).pow(2) }
    }
    val straight = score(0.0)
    var best = 0.0; var quality = straight
    for (step in -24..24) {
        val angle = step * .25; val value = score(angle)
        if (value > quality) { best = angle; quality = value }
    }
    val coarse = best
    for (step in -4..4) {
        val angle = coarse + step * .05; val value = score(angle)
        if (value > quality) { best = angle; quality = value }
    }
    return if (abs(best) < .2 || abs(best) >= 5.9 || quality < straight * 1.10) 0.0 else best
}
