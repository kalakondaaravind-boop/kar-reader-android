package com.kar.reader.feature.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.Citations
import com.kar.reader.core.domain.ExportBook
import com.kar.reader.core.domain.YearRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

enum class QuoteCardStyle(val label: String, val top: Int, val bottom: Int, val text: Int, val accent: Int, val sub: Int) {
    NAVY("Navy", 0xFF2F5688.toInt(), 0xFF0D1B30.toInt(), 0xFFFFF6E4.toInt(), 0xFFF4C268.toInt(), 0xFFB8C6DA.toInt()),
    PAPER("Paper", 0xFFFFFDF7.toInt(), 0xFFE9D9B4.toInt(), 0xFF2E2418.toInt(), 0xFF8A5A12.toInt(), 0xFF6B5A44.toInt()),
    FOREST("Forest", 0xFF56704F.toInt(), 0xFF1E2A20.toInt(), 0xFFEDE8D6.toInt(), 0xFFF4C268.toInt(), 0xFFB9C9B4.toInt()),
    INK("Ink", 0xFF3A3F48.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFF4C268.toInt(), 0xFFB0B0B0.toInt()),
}

/** Builds images and PDFs on the device. Sharing uses Android's share menu; the app itself never goes online. */
object DocumentRenderers {

    // ---- Quote card ------------------------------------------------------------------------------

    fun quoteCard(quote: String, author: String, source: String, style: QuoteCardStyle): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), style.top, style.bottom, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val sheen = Paint().apply { shader = RadialGradient(w * 0.2f, h * 0.05f, w * 0.8f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sheen)

        val serif = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accent; textSize = 260f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        c.drawText("“", 80f, 300f, mark)

        val margin = 110
        var size = 64f
        var layout: StaticLayout
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.text; typeface = serif }
        do {
            tp.textSize = size
            layout = staticLayout(quote.trim(), tp, w - margin * 2, 1.25f)
            size -= 4f
        } while (layout.height > h - 640 && size > 30f)
        c.save()
        c.translate(margin.toFloat(), 340f)
        layout.draw(c)
        c.restore()

        val line = Paint().apply { color = style.sub; alpha = 90; strokeWidth = 3f }
        c.drawLine(margin.toFloat(), h - 250f, (w - margin).toFloat(), h - 250f, line)
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.text; textSize = 46f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        c.drawText(ellipsize(author, authorPaint, w - margin * 2f), margin.toFloat(), h - 175f, authorPaint)
        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.sub; textSize = 38f }
        c.drawText(ellipsize(source, srcPaint, w - margin * 2f), margin.toFloat(), h - 115f, srcPaint)
        return bmp
    }

    // ---- Year in reading ---------------------------------------------------------------------------

    fun yearRecapImage(recap: YearRecap, soFar: Boolean): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF4A2E5E.toInt(), 0xFF0B0812.toInt(), Shader.TileMode.CLAMP) })
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4C268.toInt(); textSize = 36f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); letterSpacing = 0.15f }
        c.drawText("MY YEAR IN READING", 90f, 150f, label)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF6E4.toInt(); textSize = 96f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        c.drawText(if (soFar) "${recap.year}, so far" else "${recap.year}", 90f, 270f, title)
        val tiles = listOf(
            Triple(recap.booksFinished.toString(), "books finished", intArrayOf(0xFFFCE19A.toInt(), 0xFFA86C15.toInt())),
            Triple("%.0f".format(recap.hoursRead), "hours read", intArrayOf(0xFF9CC2F2.toInt(), 0xFF142A47.toInt())),
            Triple(recap.bestStreakDays.toString(), "day best streak", intArrayOf(0xFFA9DDB4.toInt(), 0xFF1E5A33.toInt())),
            Triple(recap.highlightsMade.toString(), "highlights made", intArrayOf(0xFFE3B6F0.toInt(), 0xFF3E1F52.toInt())),
        )
        val tw = (w - 90 * 2 - 40) / 2f
        val th = 260f
        tiles.forEachIndexed { i, (value, text, colors) ->
            val x = 90f + (i % 2) * (tw + 40)
            val y = 340f + (i / 2) * (th + 40)
            val r = RectF(x, y, x + tw, y + th)
            c.drawRoundRect(r, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(x, y, x + tw, y + th, colors[0], colors[1], Shader.TileMode.CLAMP) })
            c.drawRoundRect(RectF(x, y, x + tw, y + th / 2), 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, y, 0f, y + th / 2, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) })
            val dark = i == 0 || i == 2
            val vp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF1A1206.toInt() else 0xFFFFFFFF.toInt(); textSize = 96f; typeface = Typeface.DEFAULT_BOLD }
            c.drawText(value, x + 36f, y + 130f, vp)
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = vp.color; alpha = 210; textSize = 38f }
            c.drawText(text, x + 36f, y + 200f, lp)
        }
        var y = 1020f
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4C268.toInt(); textSize = 34f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f }
        if (recap.topBooks.isNotEmpty()) {
            c.drawText("MOST HIGHLIGHTED", 90f, y, head)
            val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF6E4.toInt(); textSize = 42f }
            recap.topBooks.forEach { (t, n) ->
                y += 70f
                c.drawText(ellipsize("$t · $n", bp, w - 180f), 90f, y, bp)
            }
        }
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCBB8DA.toInt(); textSize = 32f }
        c.drawText("Made on my device with KAR READER", 90f, h - 80f, foot)
        return bmp
    }

    // ---- Printable notes summary ---------------------------------------------------------------------

    /** A4 PDF of a book's highlights grouped by colour meaning, then notes, with locations. */
    suspend fun notesSummaryPdf(context: Context, book: ExportBook, style: CitationStyle, destination: Uri) = withContext(Dispatchers.IO) {
        val pageW = 595
        val pageH = 842
        val margin = 56
        val contentW = pageW - margin * 2
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); color = 0xFF111111.toInt() }
        val metaP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF555555.toInt() }
        val headP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF8A5A12.toInt(); letterSpacing = 0.08f }
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; typeface = Typeface.SERIF; color = 0xFF1A1A1A.toInt() }
        val noteP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = 0xFF2E3747.toInt() }
        val footP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = 0xFF888888.toInt() }

        fun finishPage() {
            page?.let {
                it.canvas.drawText("${book.title} — notes summary · page $pageNo", margin.toFloat(), pageH - 28f, footP)
                doc.finishPage(it)
            }
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }
        fun draw(text: String, paint: TextPaint, spacingAfter: Float, indent: Int = 0) {
            val layout = staticLayout(text, paint, contentW - indent, 1.25f)
            if (page == null || y + layout.height > pageH - margin) newPage()
            val c = page!!.canvas
            c.save(); c.translate((margin + indent).toFloat(), y); layout.draw(c); c.restore()
            y += layout.height + spacingAfter
        }

        newPage()
        draw("${book.title} — Notes summary", titleP, 4f)
        draw(
            "${book.highlights.size} highlights · ${book.notes.size} notes · printed ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())}",
            metaP, 4f,
        )
        draw(Citations.bibliography(book.citation, style), metaP, 16f)
        book.highlights.groupBy { it.meaning ?: "Highlights" }.forEach { (meaning, items) ->
            draw(meaning.uppercase(), headP, 6f)
            items.forEach { hl ->
                val loc = hl.location?.let { if (it.all(Char::isDigit)) "p. $it — " else "$it — " }.orEmpty()
                draw(loc + hl.text.trim(), bodyP, if (hl.note.isNullOrBlank()) 8f else 2f)
                if (!hl.note.isNullOrBlank()) draw("Note: ${hl.note.trim()}", noteP, 8f, indent = 14)
            }
            y += 6f
        }
        if (book.notes.isNotEmpty()) {
            draw("NOTES", headP, 6f)
            book.notes.forEach { n ->
                draw(n.title.ifBlank { "Untitled note" } + (n.location?.let { " ($it)" } ?: ""), bodyP.apply { isFakeBoldText = true }, 2f)
                bodyP.isFakeBoldText = false
                draw(n.body.trim(), noteP, 10f)
            }
        }
        finishPage()
        val out = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Can't write to that location")
        out.use { doc.writeTo(it) }
        doc.close()
    }

    // ---- Sharing -----------------------------------------------------------------------------

    /** Saves [bitmap] to the private share folder and opens Android's share menu for it. */
    suspend fun shareImage(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
            File(dir, fileName).also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    private fun ellipsize(text: String, paint: TextPaint, max: Float): String =
        android.text.TextUtils.ellipsize(text, paint, max, android.text.TextUtils.TruncateAt.END).toString()
}
