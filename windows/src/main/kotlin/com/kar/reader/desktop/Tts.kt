package com.kar.reader.desktop

import java.io.File

/** Read-aloud through the voices Windows already has (System.Speech). Works offline; does nothing on other systems. */
object Tts {
    private var process: Process? = null
    val available: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Synchronized
    fun speak(text: String, rate: Int) {
        stop()
        if (!available || text.isBlank()) return
        // The text goes through a file, never through the command line.
        val textFile = File.createTempFile("kar-speak", ".txt").apply { writeText(text, Charsets.UTF_8); deleteOnExit() }
        val script = "Add-Type -AssemblyName System.Speech; " +
            "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Rate = ${rate.coerceIn(-10, 10)}; " +
            "\$s.Speak([System.IO.File]::ReadAllText('${textFile.absolutePath.replace("'", "''")}', [System.Text.Encoding]::UTF8))"
        process = runCatching { ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start() }.getOrNull()
    }

    @Synchronized
    fun stop() { process?.destroyForcibly(); process = null }

    val speaking: Boolean @Synchronized get() = process?.isAlive == true
}
