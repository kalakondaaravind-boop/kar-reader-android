package com.kar.reader.desktop

import com.kar.reader.core.domain.MonoResampler
import com.kar.reader.core.domain.WavFormat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

const val SPEECH_RATE = 16_000

/** Appends 16-bit mono PCM to a WAV file and keeps its header correct, so a crash loses at most a few seconds. */
class WavWriter(file: File, private val sampleRate: Int = SPEECH_RATE) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    var dataBytes: Long = 0
        private set

    init {
        raf.setLength(0)
        raf.write(WavFormat.header(sampleRate, 0))
    }

    val durationMs: Long get() = dataBytes / 2 * 1000 / sampleRate

    fun writeFloats(samples: FloatArray) {
        if (samples.isEmpty()) return
        val pcm = WavFormat.floatToPcm16(samples)
        raf.write(pcm, 0, pcm.size)
        dataBytes += pcm.size
    }

    fun sync() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(WavFormat.header(sampleRate, dataBytes))
        raf.seek(pos)
    }

    override fun close() { sync(); raf.close() }
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

/** Imports audio files into the 16 kHz mono WAV the speech engine reads. Supports WAV, AIFF, AU and MP3. */
object AudioImport {
    class Unsupported(message: String) : IOException(message)

    fun toSpeechWav(source: File, out: File, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Long {
        val raw = try {
            AudioSystem.getAudioInputStream(source)
        } catch (e: Exception) {
            throw Unsupported("KAR READER can import WAV and MP3 recordings. Convert “${source.name}” to WAV or MP3 first (M4A, FLAC and OGG aren't supported yet).")
        }
        raw.use { base ->
            val fmt = base.format
            val rate = if (fmt.sampleRate > 0) fmt.sampleRate else 44_100f
            val channels = if (fmt.channels > 0) fmt.channels else 2
            val pcmFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
            val pcm = try { AudioSystem.getAudioInputStream(pcmFormat, base) } catch (e: Exception) { throw Unsupported("This audio format can't be decoded.") }
            val total = base.frameLength
            WavWriter(out).use { writer ->
                val resampler = MonoResampler(rate.toInt(), channels)
                val buffer = ByteArray(64 * 1024)
                var frames = 0L
                var lastSync = 0L
                while (true) {
                    if (isCancelled()) throw CancellationException()
                    val n = pcm.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    val samples = WavFormat.pcm16ToFloat(buffer, n / 2)
                    writer.writeFloats(resampler.feed(samples))
                    frames += n / (2 * channels)
                    if (total > 0) onProgress((frames.toFloat() / total).coerceIn(0f, 1f))
                    if (writer.dataBytes - lastSync > SPEECH_RATE * 2L * 30) { writer.sync(); lastSync = writer.dataBytes }
                }
                return writer.durationMs
            }
        }
    }
}

/** Records the microphone to a 16 kHz mono WAV. */
class MicRecorder(private val out: File) {
    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var level = 0f
        private set
    @Volatile var elapsedMs = 0L
        private set
    @Volatile var error: String? = null
        private set
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "mic-recorder").apply { isDaemon = true; start() }
    }

    private fun run() {
        var line: TargetDataLine? = null
        try {
            var rate = SPEECH_RATE
            var format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            if (!AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))) {
                rate = 44_100
                format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            }
            line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
            line.open(format)
            line.start()
            val resampler = if (rate == SPEECH_RATE) null else MonoResampler(rate, 1)
            val buffer = ByteArray(4096)
            WavWriter(out).use { writer ->
                var lastSync = 0L
                while (running) {
                    val n = line.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = WavFormat.pcm16ToFloat(buffer, n / 2)
                    var sum = 0.0
                    for (s in samples) sum += (s * s).toDouble()
                    level = kotlin.math.sqrt(sum / samples.size.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
                    if (paused) continue
                    writer.writeFloats(resampler?.feed(samples) ?: samples)
                    elapsedMs = writer.durationMs
                    if (writer.dataBytes - lastSync > SPEECH_RATE * 2L * 10) { writer.sync(); lastSync = writer.dataBytes }
                }
            }
        } catch (e: Exception) {
            error = "Couldn't use the microphone: ${e.message ?: "no input device found"}. Check Windows Settings › Privacy › Microphone."
        } finally {
            runCatching { line?.stop(); line?.close() }
            running = false
        }
    }

    fun stop(): Long {
        running = false
        thread?.join(3000)
        return elapsedMs
    }
}
