package com.kar.reader.feature.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.kar.reader.core.domain.InkCodec
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.InkStrokeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * "Save a copy with annotations": writes a NEW PDF in which each page is the original page rendered as
 * an image with ink and highlight areas drawn on top. The original document is never modified.
 * Limitation (shown to the user): text in the copy is not selectable, because pages are images.
 */
object PdfExporter {

    private const val EXPORT_WIDTH = 1700

    suspend fun exportFlattened(
        context: Context,
        session: PdfDocumentSession,
        strokes: List<InkStrokeEntity>,
        highlights: List<HighlightEntity>,
        workDir: File,
        destination: Uri,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tmp = File(workDir, "export-${System.nanoTime()}.pdf")
        val doc = PdfDocument()
        try {
            val byPage = strokes.groupBy { it.pageIndex }
            val hlByPage = highlights.groupBy { it.position }
            for (i in 0 until session.pageCount) {
                onProgress(i + 1, session.pageCount)
                val size = session.pageSizes[i]
                val bmp = session.renderForExport(i, EXPORT_WIDTH)
                try {
                    val canvas = Canvas(bmp)
                    hlByPage[i].orEmpty().forEach { drawHighlight(canvas, it, bmp.width, bmp.height) }
                    byPage[i].orEmpty().forEach { drawStroke(canvas, it, bmp.width, bmp.height) }
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(size.width, size.height, i + 1).create())
                    page.canvas.drawBitmap(bmp, null, Rect(0, 0, size.width, size.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                } finally {
                    bmp.recycle()
                }
            }
            tmp.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        // Verify the copy opens before handing it to the user's chosen location.
        val reopened = PdfDocumentSession.open(tmp).use { it.pageCount }
        if (reopened != session.pageCount) {
            tmp.delete(); throw IOException("The exported copy failed verification")
        }
        val out = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Can't write to that location")
        out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        tmp.delete()
    }

    fun drawStroke(canvas: Canvas, stroke: InkStrokeEntity, width: Int, height: Int) {
        val pts = InkCodec.decode(stroke.points)
        if (pts.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = stroke.colorArgb
            strokeWidth = (stroke.widthFraction * width).coerceAtLeast(1f)
            if (stroke.tool == "HIGHLIGHTER") alpha = 110
        }
        val path = Path()
        path.moveTo(pts[0].x * width, pts[0].y * height)
        for (k in 1 until pts.size) path.lineTo(pts[k].x * width, pts[k].y * height)
        if (pts.size == 1) path.lineTo(pts[0].x * width + 0.5f, pts[0].y * height)
        canvas.drawPath(path, paint)
    }

    fun drawHighlight(canvas: Canvas, h: HighlightEntity, width: Int, height: Int) {
        val r = parseRect(h.locator) ?: return
        val paint = Paint().apply { color = HighlightColor.fromKey(h.colorKey).argb.toInt(); alpha = 100 }
        canvas.drawRect(RectF(r.left * width, r.top * height, r.right * width, r.bottom * height), paint)
    }

    /** PDF highlight locators are "l,t,r,b" in 0..1 page coordinates. */
    fun parseRect(locator: String): RectF? {
        val p = locator.split(',').mapNotNull { it.toFloatOrNull() }
        if (p.size != 4) return null
        return RectF(minOf(p[0], p[2]), minOf(p[1], p[3]), maxOf(p[0], p[2]), maxOf(p[1], p[3]))
    }

    fun rectLocator(r: RectF): String = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, r.left, r.top, r.right, r.bottom)

    @Suppress("unused")
    private fun Bitmap.safeRecycle() { if (!isRecycled) recycle() }
}
