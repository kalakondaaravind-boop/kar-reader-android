package com.kar.reader.core.domain

/** Anything that can be synced: a stable id, a last-modified time and a soft-delete marker. */
interface Syncable {
    val syncId: String
    val modifiedAt: Long
}

data class MergeResult<T>(val upserts: List<T>, val conflicts: List<Pair<T, T>>)

/**
 * Merge rules used by folder and Wi-Fi sync. Nothing is ever dropped silently:
 * the newer record wins, and when both sides edited content the losing side is reported as a
 * conflict so the caller can keep it in note history.
 */
object SyncMerge {

    /** Last-writer-wins. Returns remote records that should overwrite or be added locally. */
    fun <T : Syncable> lastWriterWins(local: List<T>, remote: List<T>): MergeResult<T> {
        val localById = local.associateBy { it.syncId }
        val upserts = remote.filter { r ->
            val l = localById[r.syncId]
            l == null || r.modifiedAt > l.modifiedAt
        }
        return MergeResult(upserts, emptyList())
    }

    /**
     * Like [lastWriterWins], but when both versions exist with different content the older one is
     * returned as a conflict (pair of winner to loser) so it can be saved as a previous version.
     */
    fun <T : Syncable> withConflicts(local: List<T>, remote: List<T>, sameContent: (T, T) -> Boolean): MergeResult<T> {
        val localById = local.associateBy { it.syncId }
        val upserts = mutableListOf<T>()
        val conflicts = mutableListOf<Pair<T, T>>()
        for (r in remote) {
            val l = localById[r.syncId]
            when {
                l == null -> upserts += r
                sameContent(l, r) -> if (r.modifiedAt > l.modifiedAt) upserts += r
                r.modifiedAt > l.modifiedAt -> { upserts += r; conflicts += r to l }
                else -> conflicts += l to r
            }
        }
        return MergeResult(upserts, conflicts)
    }
}

/** Six-digit pairing codes and message authentication for Wi-Fi sync. */
object PairingCodes {
    fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        (random.nextInt(900_000) + 100_000).toString()

    fun mac(code: String, payload: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(("kar-reader-sync:" + code).toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    fun verify(code: String, payload: ByteArray, expected: String): Boolean =
        java.security.MessageDigest.isEqual(mac(code, payload).toByteArray(), expected.toByteArray())
}

/** Salted PBKDF2 hashes for optional profile PINs. */
object PinHasher {
    private const val ITERATIONS = 120_000

    fun hash(pin: String, salt: ByteArray = newSalt()): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return b64(salt) + ":" + b64(key)
    }

    fun verify(pin: String, stored: String): Boolean {
        val salt = runCatching { java.util.Base64.getDecoder().decode(stored.substringBefore(':')) }.getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    private fun newSalt() = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
}
