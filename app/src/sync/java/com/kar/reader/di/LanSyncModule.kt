package com.kar.reader.di

import android.content.Context
import com.kar.reader.core.domain.PairingCodes
import com.kar.reader.data.transfer.LanHostSession
import com.kar.reader.data.transfer.LanSync
import com.kar.reader.data.transfer.MergeReport
import com.kar.reader.data.transfer.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Singleton

/**
 * Direct Wi-Fi sync (sync build only). One device hosts and shows its local address and a six-digit code;
 * the other enters both. Each side sends its snapshot with an HMAC made from the code, verifies the other's,
 * and merges. Only private-network addresses are accepted, and nothing is sent to the internet.
 * Limitation: the payload is authenticated but not encrypted, so use it on networks you trust.
 */
@Module
@InstallIn(SingletonComponent::class)
object LanSyncModule {
    @Provides
    @Singleton
    fun provideLanSync(@ApplicationContext context: Context, sync: SyncManager): LanSync = WifiLanSync(sync)
}

private class WifiLanSync(private val sync: SyncManager) : LanSync {
    override val isAvailable = true

    override suspend fun host(onStatus: (String) -> Unit): LanHostSession = withContext(Dispatchers.IO) {
        val ip = localAddress() ?: throw IOException("Connect to Wi-Fi first")
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(ip, 0))
        server.soTimeout = 5 * 60_000
        val pairing = PairingCodes.generate()
        onStatus("Waiting for your other device")
        object : LanHostSession {
            override val address = "${ip.hostAddress}:${server.localPort}"
            override val code = pairing
            override suspend fun awaitPeer(): MergeReport = withContext(Dispatchers.IO) {
                server.use { s ->
                    s.accept().use { socket ->
                        socket.soTimeout = 60_000
                        if (!isPrivate(socket.inetAddress)) throw IOException("Refused a connection from outside the local network")
                        val input = DataInputStream(socket.getInputStream().buffered())
                        val output = DataOutputStream(socket.getOutputStream().buffered())
                        val theirs = readFrame(input, pairing)
                        writeFrame(output, sync.snapshotBytes(), pairing)
                        sync.mergeSnapshot(theirs)
                    }
                }
            }
            override fun cancel() { runCatching { server.close() } }
        }
    }

    override suspend fun join(address: String, code: String, onStatus: (String) -> Unit): MergeReport = withContext(Dispatchers.IO) {
        val host = address.substringBeforeLast(':').trim()
        val port = address.substringAfterLast(':').trim().toIntOrNull() ?: throw IOException("Enter the address shown on the other device")
        val inet = java.net.InetAddress.getByName(host)
        if (!isPrivate(inet)) throw IOException("Only devices on your local network can be used")
        onStatus("Connecting")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(inet, port), 15_000)
            socket.soTimeout = 60_000
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())
            onStatus("Sending your changes")
            writeFrame(output, sync.snapshotBytes(), code)
            onStatus("Receiving changes")
            val theirs = readFrame(input, code)
            sync.mergeSnapshot(theirs)
        }
    }

    private fun writeFrame(out: DataOutputStream, payload: ByteArray, code: String) {
        out.writeUTF("KARSYNC1")
        out.writeUTF(PairingCodes.mac(code, payload))
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream, code: String): ByteArray {
        if (input.readUTF() != "KARSYNC1") throw IOException("The other device isn't KAR READER Sync")
        val mac = input.readUTF()
        val size = input.readInt()
        if (size !in 0..(128 shl 20)) throw IOException("Sync data is too large")
        val payload = ByteArray(size)
        input.readFully(payload)
        if (!PairingCodes.verify(code, payload, mac)) throw IOException("Wrong pairing code")
        return payload
    }

    private fun localAddress(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { isPrivate(it) }

    private fun isPrivate(a: java.net.InetAddress): Boolean = a.isSiteLocalAddress || a.isLinkLocalAddress
}
