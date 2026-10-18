package com.kar.reader.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Renders pages of one PDF with Android's built-in PdfRenderer.
 * PdfRenderer can only have one page open at a time, so all access goes through a mutex on a single
 * background thread. Rendered bitmaps live in a size-bounded cache, never the whole document.
 */
class PdfDocumentSession private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<Size>,
) : Closeable {

    private val mutex = Mutex()
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var closed = false

    val pageCount: Int get() = pageSizes.size

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Renders [pageIndex] at [widthPx] pixels wide (capped), using the cache when possible. */
    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        val width = widthPx.coerceIn(64, MAX_RENDER_WIDTH)
        val key = "$pageIndex@$width"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock null
            val size = pageSizes[pageIndex]
            val height = (width * size.height.toFloat() / size.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_RENDER_WIDTH * 2)
            val bitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
                return@withLock null
            }
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(pageIndex).use { page -> page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            cache.put(key, bitmap)
            bitmap
        }
    }

    /** Renders an uncached bitmap for export or text recognition. The caller owns and recycles it. */
    suspend fun renderForExport(pageIndex: Int, widthPx: Int): Bitmap = withContext(dispatcher) {
        mutex.withLock {
            check(!closed)
            val size = pageSizes[pageIndex]
            val width = widthPx.coerceIn(64, MAX_RENDER_WIDTH)
            val height = (width * size.height.toFloat() / size.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(pageIndex).use { page -> page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) }
            bitmap
        }
    }

    fun trimCache() = cache.trimToSize(cache.maxSize() / 2)

    override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        const val MAX_RENDER_WIDTH = 2400

        private fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(256L shl 20).toInt()

        /** Opens a PDF. Throws [IOException] with a user-readable message on failure. */
        suspend fun open(file: File): PdfDocumentSession = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close(); throw IOException("Password-protected PDFs aren't supported yet")
            } catch (e: IOException) {
                pfd.close(); throw IOException("This PDF is damaged and can't be opened")
            }
            val sizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { Size(it.width, it.height) } }
            PdfDocumentSession(pfd, renderer, sizes)
        }
    }
}
