package com.kar.reader.feature.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.kar.reader.core.domain.MonoResampler
import com.kar.reader.core.domain.WavFormat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder

const val SPEECH_RATE = 16_000

/** Appends 16-bit mono PCM to a WAV file and keeps its header correct. Crash-safe: see [WavRepair]. */
class WavWriter(private val file: File, private val sampleRate: Int = SPEECH_RATE, append: Boolean = false) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    var dataBytes: Long = 0
        private set

    init {
        if (append && raf.length() >= WavFormat.HEADER_BYTES) {
            dataBytes = raf.length() - WavFormat.HEADER_BYTES
            dataBytes -= dataBytes % 2
            raf.setLength(WavFormat.HEADER_BYTES + dataBytes)
            raf.seek(raf.length())
        } else {
            raf.setLength(0)
            raf.write(WavFormat.header(sampleRate, 0))
        }
    }

    val durationMs: Long get() = dataBytes / 2 * 1000 / sampleRate

    fun write(pcm: ByteArray, count: Int = pcm.size) {
        raf.write(pcm, 0, count)
        dataBytes += count
    }

    fun writeFloats(samples: FloatArray) {
        if (samples.isEmpty()) return
        write(WavFormat.floatToPcm16(samples))
    }

    /** Rewrites the header with the current length; called periodically so a crash loses at most seconds. */
    fun sync() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(WavFormat.header(sampleRate, dataBytes))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun close() {
        sync()
        raf.close()
    }
}

object WavRepair {
    /** Fixes the header of a recording interrupted before it was closed. Returns its duration, or null if unreadable. */
    fun repair(file: File): Long? {
        if (!file.isFile || file.length() < WavFormat.HEADER_BYTES) return null
        return runCatching { WavWriter(file, append = true).use { it.durationMs } }.getOrNull()
    }
}

/** Random-access reader for the 16 kHz mono WAV files this app writes. */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val info: WavFormat.Info

    init {
        val head = ByteArray(minOf(4096L, raf.length()).toInt())
        raf.readFully(head)
        info = WavFormat.parse(head, raf.length()) ?: run { raf.close(); throw IOException("This recording file is damaged.") }
        if (info.sampleRate != SPEECH_RATE || info.channels != 1) { raf.close(); throw IOException("Unexpected recording format.") }
    }

    val durationSec: Float get() = info.durationSec

    /** Samples from [startSec] to [endSec] as floats in −1..1. */
    fun read(startSec: Float, endSec: Float): FloatArray {
        val first = (startSec * SPEECH_RATE).toLong().coerceIn(0, info.frames)
        val last = (endSec * SPEECH_RATE).toLong().coerceIn(first, info.frames)
        val count = (last - first).toInt()
        if (count <= 0) return FloatArray(0)
        val bytes = ByteArray(count * 2)
        raf.seek(info.dataOffset + first * 2)
        raf.readFully(bytes)
        return WavFormat.pcm16ToFloat(bytes, count)
    }

    override fun close() = raf.close()
}

/**
 * Converts any audio file Android can decode (MP3, M4A/AAC, OGG/Opus, FLAC, WAV, 3GP, AMR…) to a 16 kHz mono
 * WAV. Runs on the calling thread; check [isCancelled] to stop early.
 */
object AudioConverter {
    class Unsupported(message: String) : IOException(message)

    fun toSpeechWav(context: Context, source: Uri, out: File, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Long {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(source, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: throw IOException("Couldn't open that file.")
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw Unsupported("That file has no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw Unsupported("This phone can't decode $mime audio.") }
            WavWriter(out).use { writer ->
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    decodeLoop(extractor, codec, writer, durationUs, onProgress, isCancelled, format)
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
                return writer.durationMs
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeLoop(
        extractor: MediaExtractor, codec: MediaCodec, writer: WavWriter, durationUs: Long,
        onProgress: (Float) -> Unit, isCancelled: () -> Boolean, inputFormat: MediaFormat,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var resampler: MonoResampler? = null
        var channels = inputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        var rate = inputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        var floatPcm = false
        var lastSync = 0L
        while (true) {
            if (isCancelled()) throw java.util.concurrent.CancellationException()
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    rate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                    floatPcm = f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = null
                }
                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val samples = if (floatPcm) {
                            FloatArray(info.size / 4).also { buf.asFloatBuffer().get(it) }
                        } else {
                            val shorts = ShortArray(info.size / 2).also { buf.asShortBuffer().get(it) }
                            FloatArray(shorts.size) { shorts[it] / 32768f }
                        }
                        val r = resampler ?: MonoResampler(rate, channels.coerceAtLeast(1)).also { resampler = it }
                        writer.writeFloats(r.feed(samples))
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        if (writer.dataBytes - lastSync > SPEECH_RATE * 2L * 30) { writer.sync(); lastSync = writer.dataBytes }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}
