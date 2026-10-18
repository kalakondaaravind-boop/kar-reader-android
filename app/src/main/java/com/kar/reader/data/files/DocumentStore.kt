package com.kar.reader.data.files

import android.content.Context
import com.kar.reader.core.model.DocumentFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layout of app-managed storage (internal, private to the app, not backed up to the cloud):
 *
 *  files/library/<bookId>.<ext>   imported documents (never modified after import)
 *  files/covers/<bookId>.png      generated cover thumbnails (rebuildable)
 *  files/staging/<jobId>/         in-flight imports; anything here at startup is an interrupted job
 *  cache/export/                  temporary export output before it is copied to the user's chosen file
 */
@Singleton
class DocumentStore @Inject constructor(@ApplicationContext private val context: Context) {

    val libraryDir: File get() = File(context.filesDir, "library").apply { mkdirs() }
    val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }
    private val stagingRoot: File get() = File(context.filesDir, "staging").apply { mkdirs() }
    val exportDir: File get() = File(context.cacheDir, "export").apply { mkdirs() }

    fun libraryFile(storedFileName: String): File = File(libraryDir, storedFileName)

    fun storedNameFor(bookId: String, format: DocumentFormat): String = "$bookId.${format.extension}"

    fun coverFile(bookId: String): File = File(coversDir, "$bookId.png")

    fun newStagingDir(): File = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    /** Removes leftovers of imports interrupted by process death. Returns the number of jobs cleaned. */
    fun cleanInterruptedStaging(): Int {
        val leftovers = stagingRoot.listFiles().orEmpty()
        leftovers.forEach { it.deleteRecursively() }
        return leftovers.size
    }

    fun usableSpaceBytes(): Long = context.filesDir.usableSpace

    fun libraryBytes(): Long = libraryDir.listFiles().orEmpty().sumOf { it.length() }
}
