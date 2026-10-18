package com.kar.reader.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.kar.reader.core.epub.EpubParser
import com.kar.reader.core.model.DocumentFormat
import com.kar.reader.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Makes small cover thumbnails. Covers are a rebuildable cache, so failures are silently skipped. */
@Singleton
class CoverGenerator @Inject constructor(private val store: DocumentStore) {

    companion object {
        private const val WIDTH = 360
        private const val MAX_COVER_BYTES = 20L shl 20
        private const val MAX_DECODE_PIXELS = 40_000_000L
    }

    suspend fun generate(book: BookEntity, file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = when (DocumentFormat.fromNameOrNull(book.format)) {
                DocumentFormat.PDF -> renderPdfCover(file)
                DocumentFormat.EPUB -> epubCover(file)
                null -> null
            } ?: return@runCatching
            save(bitmap, store.coverFile(book.id))
        }
    }

    suspend fun fromImage(image: File, target: File) = withContext(Dispatchers.IO) {
        runCatching { decodeBounded(image.readBytes())?.let { save(it, target) } }
    }

    private fun renderPdfCover(file: File): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (WIDTH * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceIn(1, WIDTH * 3)
                    val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    private fun epubCover(file: File): Bitmap? = ZipFile(file).use { zip ->
        val pub = EpubParser.parse(zip)
        val href = pub.coverHref ?: return null
        val entry = zip.getEntry(href) ?: return null
        if (entry.size > MAX_COVER_BYTES) return null
        decodeBounded(zip.getInputStream(entry).use { it.readBytes() })
    }

    /** Decodes with a sample size so a hostile or huge image can't exhaust memory. */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_DECODE_PIXELS * 4) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (decoded.width <= WIDTH) return decoded
        val h = (WIDTH * decoded.height.toFloat() / decoded.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, WIDTH, h, true)
    }

    private fun save(bitmap: Bitmap, target: File) {
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(target)
    }
}
