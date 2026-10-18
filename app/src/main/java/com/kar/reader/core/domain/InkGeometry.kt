package com.kar.reader.core.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in normalized page coordinates (0..1 of page width/height). */
data class InkPoint(val x: Float, val y: Float)

/** Compact text encoding for strokes: "x,y;x,y" with 4-decimal precision. */
object InkCodec {
    fun encode(points: List<InkPoint>): String =
        points.joinToString(";") { "${fmt(it.x)},${fmt(it.y)}" }

    fun decode(encoded: String): List<InkPoint> =
        encoded.split(';').mapNotNull { pair ->
            val comma = pair.indexOf(',')
            if (comma <= 0) return@mapNotNull null
            val x = pair.substring(0, comma).toFloatOrNull() ?: return@mapNotNull null
            val y = pair.substring(comma + 1).toFloatOrNull() ?: return@mapNotNull null
            InkPoint(x, y)
        }

    private fun fmt(v: Float): String {
        val scaled = Math.round(v.coerceIn(-1f, 2f) * 10000f)
        val sign = if (scaled < 0) "-" else ""
        val a = abs(scaled)
        return "$sign${a / 10000}.${(a % 10000).toString().padStart(4, '0')}"
    }
}

object InkGeometry {

    /** Shortest distance from [p] to the polyline [stroke]. */
    fun distanceToStroke(p: InkPoint, stroke: List<InkPoint>): Float {
        if (stroke.isEmpty()) return Float.MAX_VALUE
        if (stroke.size == 1) return hypot(p.x - stroke[0].x, p.y - stroke[0].y)
        var best = Float.MAX_VALUE
        for (i in 0 until stroke.size - 1) best = min(best, distanceToSegment(p, stroke[i], stroke[i + 1]))
        return best
    }

    fun distanceToSegment(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Ray-casting point-in-polygon test used by the lasso tool. */
    fun insidePolygon(p: InkPoint, polygon: List<InkPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** A stroke is selected by a lasso when most of its points fall inside it. */
    fun lassoSelects(stroke: List<InkPoint>, lasso: List<InkPoint>): Boolean {
        if (stroke.isEmpty()) return false
        val inside = stroke.count { insidePolygon(it, lasso) }
        return inside * 2 >= stroke.size
    }

    fun translate(stroke: List<InkPoint>, dx: Float, dy: Float): List<InkPoint> = stroke.map { InkPoint(it.x + dx, it.y + dy) }

    fun pathLength(points: List<InkPoint>): Float {
        var len = 0f
        for (i in 1 until points.size) len += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        return len
    }
}

enum class ShapeKind { LINE, RECTANGLE, ELLIPSE }

data class RecognizedShape(val kind: ShapeKind, val points: List<InkPoint>)

/**
 * Turns a hand-drawn stroke into a clean line, rectangle or ellipse when it is clearly one of them.
 * Returns null when the stroke should stay freehand. Works on any coordinate system.
 */
object ShapeRecognizer {

    fun recognize(points: List<InkPoint>): RecognizedShape? {
        if (points.size < 6) return null
        val length = InkGeometry.pathLength(points)
        if (length <= 0f) return null
        val first = points.first()
        val last = points.last()
        val chord = hypot(last.x - first.x, last.y - first.y)

        // Line: the straight distance is almost the whole path length.
        if (chord / length > 0.94f) return RecognizedShape(ShapeKind.LINE, listOf(first, last))

        val closed = chord < length * 0.18f
        if (!closed) return null

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0f || h <= 0f) return null

        // How far points sit from the bounding box edges (rectangle) vs from an inscribed ellipse.
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val rx = w / 2
        val ry = h / 2
        var rectErr = 0f
        var ellipseErr = 0f
        for (p in points) {
            val edge = minOf(abs(p.x - minX), abs(p.x - maxX), abs(p.y - minY), abs(p.y - maxY))
            rectErr += edge / max(w, h)
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            ellipseErr += abs(sqrt(nx * nx + ny * ny) - 1f)
        }
        rectErr /= points.size
        ellipseErr /= points.size

        return when {
            rectErr < 0.05f && rectErr < ellipseErr -> RecognizedShape(
                ShapeKind.RECTANGLE,
                listOf(InkPoint(minX, minY), InkPoint(maxX, minY), InkPoint(maxX, maxY), InkPoint(minX, maxY), InkPoint(minX, minY)),
            )
            ellipseErr < 0.12f -> RecognizedShape(
                ShapeKind.ELLIPSE,
                (0..48).map { i ->
                    val a = (i / 48.0) * 2 * Math.PI
                    InkPoint((cx + rx * cos(a)).toFloat(), (cy + ry * sin(a)).toFloat())
                },
            )
            else -> null
        }
    }

    @Suppress("unused")
    private fun angle(a: InkPoint, b: InkPoint) = atan2(b.y - a.y, b.x - a.x)
}
