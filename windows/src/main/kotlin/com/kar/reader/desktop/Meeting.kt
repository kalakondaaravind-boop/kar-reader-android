package com.kar.reader.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** Combines the microphone and the computer's own audio into one 16 kHz mono recording. */
object MeetingMix {
    /** Sums [a] and [b] sample by sample (the longer one sets the length) and writes [out]. [b] may be null. */
    fun mix(a: File, b: File?, out: File) {
        if (b == null || !b.isFile || b.length() < 100) { if (a != out) a.copyTo(out, overwrite = true); return }
        WavReader(a).use { ra ->
            WavReader(b).use { rb ->
                val total = maxOf(ra.durationSec, rb.durationSec)
                WavWriter(out).use { w ->
                    var t = 0f
                    while (t < total) {
                        val end = minOf(total, t + 30f)
                        val x = ra.read(t, end)
                        val y = rb.read(t, end)
                        val n = maxOf(x.size, y.size)
                        val mixed = FloatArray(n) { i ->
                            val v = (if (i < x.size) x[i] else 0f) + (if (i < y.size) y[i] else 0f)
                            (v * 0.9f).coerceIn(-1f, 1f)
                        }
                        w.writeFloats(mixed)
                        t = end
                    }
                }
            }
        }
    }
}

/**
 * Records a desktop meeting: the microphone (your voice) plus what the computer is playing (the other people on the call),
 * through a small helper that uses Windows' own loopback capture. If the helper isn't available the microphone still records,
 * and [note] says so plainly.
 */
class MeetingRecorder(private val out: File) {
    private val base = out.nameWithoutExtension
    private val micFile = File(out.parentFile, "$base-mic.wav")
    private val sysFile = File(out.parentFile, "$base-sys.wav")
    private val mic = MicRecorder(micFile)
    private var proc: Process? = null

    /** True once the computer's audio is really being captured. */
    @Volatile var systemAudio = false
        private set
    /** A message to show the person, e.g. why only the microphone is being recorded. */
    @Volatile var note: String? = null
        private set
    val level get() = mic.level
    val elapsedMs get() = mic.elapsedMs
    val error get() = mic.error

    private fun helper(): File? {
        val dirs = listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getenv("KAR_TOOLS_DIR")?.let { File(it).parent })
        return dirs.map { File(it, "tools/kar-loopback.exe") }.firstOrNull { it.isFile }
    }

    fun start() {
        mic.start()
        val exe = helper()
        if (exe == null) { note = "Only your microphone is being recorded: the system-audio helper wasn't found."; return }
        try {
            val p = ProcessBuilder(exe.absolutePath, sysFile.absolutePath).redirectErrorStream(true).start()
            proc = p
            val first = arrayOfNulls<String>(1)
            val reader = Thread({ first[0] = runCatching { p.inputStream.bufferedReader().readLine() }.getOrNull() }, "loopback-start").apply { isDaemon = true; start() }
            reader.join(4000)
            if (first[0] == "READY") systemAudio = true
            else {
                note = "Only your microphone is being recorded: Windows wouldn't share the computer's audio (${first[0]?.removePrefix("ERROR: ") ?: "no answer from the helper"})."
                runCatching { p.destroy() }
                proc = null
            }
        } catch (e: Exception) {
            note = "Only your microphone is being recorded: the system-audio helper couldn't start (${e.message})."
        }
    }

    /** Stops both recorders and writes the combined file. Returns its length in milliseconds. */
    fun stop(): Long {
        val micMs = mic.stop()
        proc?.let { p ->
            runCatching { p.outputStream.bufferedWriter().apply { write("stop\n"); flush() } }
            if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        proc = null
        val ok = runCatching { MeetingMix.mix(micFile, if (systemAudio) sysFile else null, out) }.isSuccess
        if (!ok && micFile.isFile) runCatching { micFile.copyTo(out, overwrite = true); note = "The computer's audio couldn't be combined, so only your microphone was kept." }
        micFile.delete(); sysFile.delete()
        return runCatching { WavReader(out).use { (it.durationSec * 1000).toLong() } }.getOrDefault(micMs)
    }

    fun discard() {
        runCatching { mic.stop() }
        proc?.let { runCatching { it.destroyForcibly() } }
        proc = null
        micFile.delete(); sysFile.delete(); out.delete()
    }
}
