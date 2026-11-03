package de.skyslycer.briefkasten

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

/** Draw the SVG's paths directly into the PDF canvas, without a raster drawable cache. */
internal class PdfLogo(context: Context) {
    private val paths = mutableListOf<Pair<Path, Paint>>()
    init {
        context.resources.openRawResource(R.raw.briefkasten_logo).use { input ->
            val xml = Xml.newPullParser().apply { setInput(input, null) }
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                    val path = PathParser.createPathFromPathData(xml.getAttributeValue(null, "d"))!!
                    if (xml.getAttributeValue(null, "transform") != null) {
                        val matrix = android.graphics.Matrix().apply { setRotate(-12f, 54f, 47f) }
                        path.transform(matrix)
                    }
                    val stroke = xml.getAttributeValue(null, "stroke")
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor(stroke ?: xml.getAttributeValue(null, "fill"))
                        style = if (stroke == null) Paint.Style.FILL else Paint.Style.STROKE
                        strokeWidth = xml.getAttributeValue(null, "stroke-width")?.toFloat() ?: 0f
                        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    }
                    paths.add(path to paint)
                }
                xml.next()
            }
        }
    }
    fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(495f, 0f)
        canvas.scale(88f / 108f, 88f / 108f)
        canvas.drawCircle(54f, 51f, 37f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 101, 75)
        })
        paths.forEach { (path, paint) -> canvas.drawPath(path, paint) }
        canvas.restore()
    }
}
