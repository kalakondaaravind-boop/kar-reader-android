package com.kar.reader.feature.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.kar.reader.core.ocr.OnDeviceOcr
import com.kar.reader.core.ocr.RecognizedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

/** A captured page: the photo plus the four corners (normalized 0..1) the user confirmed. */
data class ScannedPage(val photo: Bitmap, val corners: List<PointF>)

/**
 * Turns photos of paper pages into a PDF with a hidden text layer, entirely on the device:
 * perspective correction with a 4-point transform, printed-text recognition with the bundled ML Kit
 * model, then a PDF where each page is the corrected image with nearly invisible text placed over the
 * recognized lines (so the text can be found and copied in PDF apps that support it).
 */
object ScanProcessor {

    private const val PAGE_WIDTH_PT = 595 // A4 width in points

    fun defaultCorners(): List<PointF> = listOf(PointF(0.06f, 0.06f), PointF(0.94f, 0.06f), PointF(0.94f, 0.94f), PointF(0.06f, 0.94f))

    /** Warps the quadrilateral given by [corners] (TL, TR, BR, BL; normalized) into an upright rectangle. */
    fun deskew(photo: Bitmap, corners: List<PointF>, maxWidth: Int = 1800): Bitmap {
        val pts = corners.map { PointF(it.x * photo.width, it.y * photo.height) }
        val topW = hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
        val botW = hypot(pts[2].x - pts[3].x, pts[2].y - pts[3].y)
        val leftH = hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y)
        val rightH = hypot(pts[2].x - pts[1].x, pts[2].y - pts[1].y)
        var w = max(topW, botW)
        var h = max(leftH, rightH)
        val scale = (maxWidth / w).coerceAtMost(1f)
        w *= scale
        h *= scale
        val outW = w.toInt().coerceAtLeast(16)
        val outH = h.toInt().coerceAtLeast(16)
        val src = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** Recognizes text on each page and writes the searchable PDF to [target]. Returns each page's text. */
    suspend fun buildPdf(pages: List<Bitmap>, target: File, onProgress: (Int, Int) -> Unit): List<String> = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        val texts = mutableListOf<String>()
        try {
            pages.forEachIndexed { index, bmp ->
                onProgress(index + 1, pages.size)
                val recognized: RecognizedPage = runCatching { OnDeviceOcr.recognize(bmp) }.getOrElse { RecognizedPage("", emptyList()) }
                texts += recognized.text
                val pageH = (PAGE_WIDTH_PT * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, pageH, index + 1).create())
                val canvas = page.canvas
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, PAGE_WIDTH_PT.toFloat(), pageH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                val sx = PAGE_WIDTH_PT.toFloat() / bmp.width
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 1 }
                for (line in recognized.lines) {
                    val box = line.box
                    val height = box.height() * sx
                    if (height <= 0f || line.text.isBlank()) continue
                    textPaint.textSize = height * 0.8f
                    val measured = textPaint.measureText(line.text).coerceAtLeast(1f)
                    textPaint.textScaleX = ((box.width() * sx) / measured).coerceIn(0.3f, 3f)
                    canvas.drawText(line.text, box.left * sx, box.bottom * sx - height * 0.15f, textPaint)
                    textPaint.textScaleX = 1f
                }
                doc.finishPage(page)
            }
            withContext(Dispatchers.IO) { target.outputStream().use { doc.writeTo(it) } }
        } finally {
            doc.close()
        }
        texts
    }
}
