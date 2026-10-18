package com.kar.reader.core.io

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    /** Lower-case hex SHA-256 of a stream. The stream is consumed but not closed. */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }
}
