package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class InkGeometryTest {

    @Test
    fun codecRoundTrip() {
        val pts = listOf(InkPoint(0.1234f, 0.5f), InkPoint(1f, 0f), InkPoint(0.00005f, 0.99999f))
        val decoded = InkCodec.decode(InkCodec.encode(pts))
        assertEquals(3, decoded.size)
        decoded.zip(pts).forEach { (a, b) ->
            assertEquals(b.x, a.x, 0.0001f)
            assertEquals(b.y, a.y, 0.0001f)
        }
        assertEquals("0.1234,0.5000", InkCodec.encode(listOf(InkPoint(0.1234f, 0.5f))))
        assertTrue(InkCodec.decode("garbage;1,x;0.5,0.5").size == 1)
    }

    @Test
    fun distanceAndLasso() {
        val stroke = listOf(InkPoint(0f, 0f), InkPoint(1f, 0f))
        assertEquals(0.5f, InkGeometry.distanceToStroke(InkPoint(0.5f, 0.5f), stroke), 0.0001f)
        val square = listOf(InkPoint(0f, 0f), InkPoint(1f, 0f), InkPoint(1f, 1f), InkPoint(0f, 1f))
        assertTrue(InkGeometry.insidePolygon(InkPoint(0.5f, 0.5f), square))
        assertFalse(InkGeometry.insidePolygon(InkPoint(1.5f, 0.5f), square))
        assertTrue(InkGeometry.lassoSelects(listOf(InkPoint(0.2f, 0.2f), InkPoint(0.8f, 0.8f)), square))
        assertFalse(InkGeometry.lassoSelects(listOf(InkPoint(2f, 2f), InkPoint(3f, 3f)), square))
    }

    @Test
    fun recognizesShapes() {
        val line = (0..20).map { InkPoint(it * 5f, it * 2f + if (it % 2 == 0) 0.3f else -0.3f) }
        assertEquals(ShapeKind.LINE, ShapeRecognizer.recognize(line)?.kind)

        val rect = mutableListOf<InkPoint>()
        for (i in 0..10) rect += InkPoint(i * 10f, 0f)
        for (i in 0..10) rect += InkPoint(100f, i * 6f)
        for (i in 0..10) rect += InkPoint(100f - i * 10f, 60f)
        for (i in 0..10) rect += InkPoint(0f, 60f - i * 6f)
        assertEquals(ShapeKind.RECTANGLE, ShapeRecognizer.recognize(rect)?.kind)

        val ellipse = (0..60).map { val a = it / 60.0 * 2 * Math.PI; InkPoint((50 + 40 * cos(a)).toFloat(), (50 + 25 * sin(a)).toFloat()) }
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognize(ellipse)?.kind)

        val scribble = listOf(InkPoint(0f, 0f), InkPoint(10f, 10f), InkPoint(0f, 20f), InkPoint(15f, 5f), InkPoint(30f, 30f), InkPoint(5f, 40f), InkPoint(60f, 0f))
        assertNull(ShapeRecognizer.recognize(scribble))
    }
}
