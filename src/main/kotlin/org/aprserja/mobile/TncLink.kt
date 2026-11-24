// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aprsfoundation.core.AprsMessage
import org.aprsfoundation.core.AprsMessageTransport
import org.aprsfoundation.core.AprsMessages
import org.aprsfoundation.core.AprsTnc
import org.aprsfoundation.core.RadioPacket
import java.io.Closeable
import java.io.IOException

internal class TncLink(
    private val account: String,
    private val codec: AprsTnc,
    private val path: List<String>,
    private val transmit: Boolean, private val channel: Int,
    private val report: (String) -> Unit
) : AprsMessageTransport, Closeable {
    private val incoming = Channel<AprsMessage>(64)
    override val messages = incoming.receiveAsFlow()
    private val writes = Mutex()
    private var resource: Closeable? = null
    private var closed = false
    @Volatile private var io: TncIo? = null

    private val received = java.util.concurrent.atomic.AtomicLong()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    fun diagnostics() = codec.protocol.name + " · channel " + channel + " · RX " + received.get() + " · TX " + sent.get()
    suspend fun beacon(position: org.aprsfoundation.core.BeaconPosition) {
        val line = org.aprsfoundation.core.BeaconPackets.position(account, position, org.aprsfoundation.core.BeaconFormat())
        send(account, RadioPacket(account, "APRS", path, line.substringAfter(':').removeSuffix("\r\n")))
    }

    suspend fun querySetup(report: (String) -> Unit) = withContext(Dispatchers.IO) {
        writes.withLock {
            val active = checkNotNull(io) { "Connect a Mobilinkd KISS TNC first" }
            codec.diagnostic = report
            codec.mobilinkdSetupQueries().forEach(active::write)
        }
    }

    override fun canSend() = transmit && io != null

    @Synchronized
    fun own(resource: Closeable) {
        if (closed) {
            resource.close()
            throw IOException("TNC disconnected")
        }
        this.resource = resource
    }

    suspend fun run(open: (TncLink) -> TncIo, connected: () -> Unit, receive: suspend (RadioPacket) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val active = open(this@TncLink)
            codec.openCommand()?.let(active::write)
            synchronized(this@TncLink) {
                check(!closed) { "TNC disconnected" }
                io = active
            }
            withContext(Dispatchers.Main) { connected() }
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = active.read(buffer)
                if (count < 0) throw IOException("TNC connection closed")
                if (count == 0) continue
                for (packet in codec.receive(buffer.copyOf(count))) {
                    received.incrementAndGet()
                    receive(packet)
                    val message = packet.content(receiveOnly = true)?.let { AprsMessages.parse(it.line()) }
                    if (message != null && message.recipient == account) incoming.send(message)
                }
            }
        }

    override suspend fun sendMessage(account: String, recipient: String, text: String, id: String) =
        send(account, AprsTnc.message(account, recipient, text, id, path))

    override suspend fun acknowledge(account: String, recipient: String, id: String) =
        send(account, AprsTnc.acknowledge(account, recipient, id, path))

    private suspend fun send(account: String, packet: RadioPacket) = withContext(Dispatchers.IO) {
        require(account == this@TncLink.account)
        writes.withLock {
            val active = io
            check(transmit && active != null) { "TNC transmit is unavailable" }
            active.write(codec.encode(packet))
            sent.incrementAndGet()
            report(diagnostics())
            Unit
        }
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            io = null
            resource.also { resource = null }
        }
        incoming.close()
        runCatching { active?.close() }
    }
}
