package com.kar.reader.data.transfer

/** Direct Wi-Fi sync. Only the "sync" build provides a working implementation; the standard build has none. */
interface LanSync {
    /** False in the standard (offline) build, which has no network permission. */
    val isAvailable: Boolean

    /** Starts listening on the local network. Returns "address:port" to show, plus the pairing code. */
    suspend fun host(onStatus: (String) -> Unit): LanHostSession

    /** Connects to [address] ("ip:port") with [code], exchanges snapshots and merges. */
    suspend fun join(address: String, code: String, onStatus: (String) -> Unit): MergeReport
}

interface LanHostSession {
    val address: String
    val code: String

    /** Waits for one device to connect, exchanges snapshots, merges, and closes. */
    suspend fun awaitPeer(): MergeReport
    fun cancel()
}
