package de.skyslycer.briefkasten

import kotlin.math.*

/** Bounds are exclusive at right/bottom, in analysis-image pixels. No image data leaves the device. */
data class ContentBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Finds groups of printed marks, rejecting scanner borders, flat shadows and isolated dust. */
fun detectContentBounds(gray: IntArray, width: Int, height: Int): ContentBounds? {
    require(gray.size == width * height)
    if (width < 32 || height < 32) return null
    val histogram = IntArray(256)
    gray.forEach { histogram[it.coerceIn(0, 255)]++ }
    var total = 0
    val paper = (0..255).first { total += histogram[it]; total >= gray.size * 0.85 }
    val integral = LongArray((width + 1) * (height + 1))
    for (y in 0 until height) {
        var row = 0L
        for (x in 0 until width) {
            row += gray[y * width + x]
            integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + row
        }
    }
    // Use raw darkness before adaptive thresholding: texture inside a black scanner band
    // otherwise breaks into thousands of convincing little "glyphs".
    val darkRows = IntArray(height)
    val darkColumns = IntArray(width)
    for (y in 0 until height) for (x in 0 until width) {
        if (gray[y * width + x] < paper - 65) { darkRows[y]++; darkColumns[x]++ }
    }
    val maskedRows = BooleanArray(height)
    val maskedColumns = BooleanArray(width)
    val guard = max(3, min(width, height) / 100)
    for (y in 0 until height) if (darkRows[y] > width * .55) {
        for (near in max(0, y - guard)..min(height - 1, y + guard)) maskedRows[near] = true
    }
    for (x in 0 until width) if (darkColumns[x] > height * .55 && (x < width * .12 || x > width * .88)) {
        for (near in max(0, x - guard)..min(width - 1, x + guard)) maskedColumns[near] = true
    }
    // A broad scanner strip near the top separates the envelope from the noisy
    // scanner bed above it. Exclude that outside area as well as the strip itself.
    val upperBand = (0 until (height * .45).toInt()).filter { darkRows[it] > width * .55 }
    if (upperBand.size >= max(6, height / 30)) {
        for (y in 0..min(height - 1, upperBand.last() + guard)) maskedRows[y] = true
    }
    val radius = max(8, min(width, height) / 24)
    val ink = BooleanArray(gray.size)
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
        if (maskedRows[y] || maskedColumns[x]) continue
        val l = max(0, x - radius); val r = min(width, x + radius + 1)
        val t = max(0, y - radius); val b = min(height, y + radius + 1)
        val mean = (integral[b * (width + 1) + r] - integral[t * (width + 1) + r] -
            integral[b * (width + 1) + l] + integral[t * (width + 1) + l]) / ((r - l) * (b - t))
        ink[y * width + x] = gray[y * width + x] < min(paper - 28, mean.toInt() - 14)
    }
    data class Mark(val bounds: ContentBounds, val pixels: Int)
    val marks = mutableListOf<Mark>()
    val queue = IntArray(gray.size)
    for (start in ink.indices) {
        if (!ink[start]) continue
        var head = 0; var tail = 1
        queue[0] = start; ink[start] = false
        var l = width; var t = height; var r = 0; var b = 0
        while (head < tail) {
            val pos = queue[head++]; val x = pos % width; val y = pos / width
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (ink[next]) { ink[next] = false; queue[tail++] = next }
            }
        }
        val w = r - l + 1; val h = b - t + 1
        // Border-connected scanner artefacts and long envelope seams are not printed content.
        val edge = l <= 2 || t <= 2 || r >= width - 3 || b >= height - 3
        val line = (w >= max(16, width / 30) && w > h * 8) ||
            (h >= max(16, height / 30) && h > w * 8)
        val huge = w > width * .65 || h > height * .65
        // Broken window outlines can form L-shaped components with a large box,
        // bypassing the straight-line filter. Printed glyphs and matrix codes
        // occupy much more of their box than these thin, empty corners.
        val outline = (w > width * .15 || h > height * .20) && tail < w.toLong() * h * .12
        if (tail >= 3 && !edge && !line && !huge && !outline) marks += Mark(ContentBounds(l, t, r + 1, b + 1), tail)
    }
    if (marks.isEmpty() || marks.size > 4000) return null
    // Nearby glyphs join into text blocks; isolated speckles never define a crop.
    val gap = max(8, min(width, height) / 25)
    val used = BooleanArray(marks.size)
    val candidates = mutableListOf<Pair<ContentBounds, Int>>()
    for (i in marks.indices) {
        if (used[i]) continue
        used[i] = true
        val pending = java.util.ArrayDeque<Int>(); pending.add(i)
        var l = width; var t = height; var r = 0; var b = 0; var count = 0; var pixels = 0
        while (pending.isNotEmpty()) {
            val mark = marks[pending.removeFirst()]; val box = mark.bounds
            l = min(l, box.left); t = min(t, box.top); r = max(r, box.right); b = max(b, box.bottom)
            count++; pixels += mark.pixels
            for (j in marks.indices) {
                if (used[j]) continue
                val other = marks[j].bounds
                if (other.left <= box.right + gap && other.right >= box.left - gap &&
                    other.top <= box.bottom + gap && other.bottom >= box.top - gap) {
                    used[j] = true; pending.add(j)
                }
            }
        }
        val sparse = pixels < (r - l).toLong() * (b - t) * .10
        val borderTexture = sparse && ((r - l > width * .70 && (t < height * .08 || b > height * .92)) ||
            (b - t > height * .70 && (l < width * .08 || r > width * .92)))
        if (!borderTexture && ((count >= 4 && pixels >= 30) || (pixels >= 100 && r - l >= 12 && b - t >= 12))) {
            candidates += ContentBounds(l, t, r, b) to pixels
        }
    }
    if (candidates.isEmpty()) return null
    // A few broken seam/dust marks can cluster like a word. Require meaningful
    // ink compared with the strongest text block before letting them expand it.
    val minimumInk = candidates.maxOf { it.second } * .02
    val regions = candidates.filter { it.second >= minimumInk }.map { it.first }
    val padding = max(8, (min(width, height) * .035).roundToInt())
    val result = ContentBounds(
        (regions.minOf { it.left } - padding).coerceAtLeast(0),
        (regions.minOf { it.top } - padding).coerceAtLeast(0),
        (regions.maxOf { it.right } + padding).coerceAtMost(width),
        (regions.maxOf { it.bottom } + padding).coerceAtMost(height))
    // Cropping must make a useful difference. A null result means show the original.
    if ((result.right - result.left).toLong() * (result.bottom - result.top) > width.toLong() * height * .9) return null
    return result
}
