package com.kar.reader.core.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecognizedLine(val text: String, val box: Rect)

data class RecognizedPage(val text: String, val lines: List<RecognizedLine>)

/**
 * On-device printed-text recognition using ML Kit's bundled Latin-script model. The model ships inside
 * the app, so recognition works with no network and nothing is sent anywhere.
 * Limitation: Latin-script languages only; handwriting is not supported by this model.
 */
object OnDeviceOcr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): RecognizedPage {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line -> line.boundingBox?.let { RecognizedLine(line.text, it) } }
        }
        return RecognizedPage(result.text, lines)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
