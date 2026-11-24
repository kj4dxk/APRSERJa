// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import android.content.Context
import org.aprsfoundation.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aprsfoundation.core.AprsConnectionState
import org.aprsfoundation.core.AprsIsClient
import org.aprsfoundation.core.AprsIsConfig
import org.aprsfoundation.core.AprsMessages
import org.aprsfoundation.core.AprsProtocol
import org.aprsfoundation.core.AprsStation
import org.aprsfoundation.core.AprsStationStore
import org.aprsfoundation.core.AprsTnc
import org.aprsfoundation.core.RadioPacket
import org.aprsfoundation.core.ChatSession
import org.aprsfoundation.core.ImportedChat
import org.json.JSONArray
import org.json.JSONObject

/** Application-scoped adapter from the bundled WebView to the shared APRS and chat engine. */
class MobileAprs(context: Context, private val event: (String, String) -> Unit) {
    private data class Conversation(val account: String, val peer: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val store = MobileChatStore(context.applicationContext)
    private val migration = CompletableDeferred<Unit>()
    private val conversation = MutableStateFlow(Conversation("", ""))
    private var client: AprsIsClient? = null
    private var internetChat: ChatSession? = null
    private var radioChat: ChatSession? = null
    private var radioAccount = ""
    private var rfStations=emptyList<AprsStation>()
    private var internetStations=emptyList<AprsStation>()
    private var observers: Job? = null
    private val tncDevices = AndroidTncDevices(context.applicationContext)
    private var tnc: TncLink? = null
    private var tncJob: Job? = null
    private var discovery: Job? = null
    private var internetRequest = 0L
    private var radioRequest = 0L

    init {
        scope.launch {
            combine(store.revision, conversation) { _, selected -> selected }.collectLatest { selected ->
                saved("Unable to read chat history") {
                    migration.await()
                    val rows = JSONArray()
                    for (row in store.rows(selected.account, selected.peer).takeLast(35)) {
                        rows.put(JSONObject().put("body", row.body).put("outgoing", row.outgoing)
                            .put("status", row.status))
                    }
                    event("history", JSONObject().put("account", selected.account).put("peer", selected.peer)
                        .put("rows", rows).toString())
                }
            }
        }
    }

    fun importLegacy(chats: String, outbox: String) {
        scope.launch {
            if (migration.isCompleted) return@launch
            saved("Unable to import existing chats") {
                store.importOnce("webview-radio-v1") {
                    legacyRows(preferences.getString("chats", null).orEmpty().ifEmpty { chats }, false) +
                        legacyRows(preferences.getString("outbox", null).orEmpty().ifEmpty { outbox }, true)
                }
            }
            migration.complete(Unit)
        }
    }

    fun select(account: String, peer: String) {
        conversation.value = Conversation(account, peer)
    }

    fun enqueue(account: String, peer: String, text: String) {
        scope.launch {
            if (!AprsProtocol.validCallsign(account) || !AprsProtocol.validCallsign(peer) || account == peer ||
                !AprsMessages.validLongText(text)) {
                event("chatError", "Enter valid callsigns and a message of up to 300 APRS characters.")
                return@launch
            }
            saved("Unable to save the message") {
                migration.await()
                store.enqueue(account, peer, text)
                event("queued", JSONObject().put("account", account).put("peer", peer).put("body", text).toString())
            }
        }
    }

    fun connect(host: String, port: Int, call: String, pass: String, lat: Double, lon: Double) {
        scope.launch {
            val config = try {
                AprsIsConfig(call, pass, host.trim(), port, lat, lon,
                    application = "APRSERJa", applicationVersion = BuildConfig.VERSION_NAME)
            } catch (_: IllegalArgumentException) {
                event("status", "Check callsign, server, port and passcode")
                return@launch
            }
            val request = ++internetRequest
            migration.await()
            if (request != internetRequest) return@launch
            stopInternet()
            val next = AprsIsClient()
            client = next
            val internetRepository=object : ChatRepository by store {
                override suspend fun pending(account: String): List<PendingChat> =
                    if(radioAccount==account && tnc?.canSend()==true) emptyList() else store.pending(account)
            }
            internetChat = ChatSession(call, next, internetRepository, scope) { event("chatError", it) }
            next.start(config)
            observers = scope.launch {
                launch {
                    next.state.collect { state ->
                        event("status", when (state) {
                            AprsConnectionState.STOPPED, AprsConnectionState.CONNECTING -> "Connecting"
                            AprsConnectionState.RECEIVING -> "Receiving"
                            AprsConnectionState.VERIFIED -> "Verified as $call"
                            AprsConnectionState.RECONNECTING -> "Reconnecting"
                        })
                    }
                }
                launch {
                    next.stations.collect {internetStations=it;publishStations()}
                }
            }
        }
    }

    fun listTncDevices(kind: String) {
        discovery?.cancel()
        discovery = scope.launch {
            try {
                event("tncDevices", JSONObject().put("kind", kind)
                    .put("devices", JSONArray(tncDevices.list(kind))).toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                event("tncDeviceError", JSONObject().put("kind", kind)
                    .put("message", error.message ?: "Could not list devices").toString())
            }
        }
    }

    fun connectTnc(kind: String, protocolName: String, device: String, host: String, port: Int, baud: Int,
                   channel: Int, call: String, rawPath: String, transmit: Boolean) {
        scope.launch {
            val codec = try {
                val protocol = AprsTnc.Protocol.valueOf(protocolName)
                require(protocol != AprsTnc.Protocol.AGW || kind == "tcp")
                require(protocol == AprsTnc.Protocol.KISS || kind != "ble")
                AprsTnc(protocol, channel)
            } catch (_: IllegalArgumentException) {
                event("tncStatus", "Check the TNC protocol, connection type and radio channel")
                return@launch
            }
            val path = rawPath.trim().uppercase().takeIf { it.isNotEmpty() }
                ?.split(',')?.map { it.trim() }.orEmpty()
            if (!AprsProtocol.validCallsign(call) || !RadioPacket.address(call) || call.endsWith("-0") || path.size > 8 ||
                path.any { !RadioPacket.address(it) || it.substringBefore('-') in listOf("TCPIP", "TCPXX", "NOGATE", "RFONLY") }) {
                event("tncStatus", "Check the RF callsign and digipeater path")
                return@launch
            }
            val request = ++radioRequest
            migration.await()
            if (request != radioRequest) return@launch
            stopRadio()
            radioAccount=call
            val stations = AprsStationStore()
            val name = codec.protocol.name + " TNC"
            tncJob = scope.launch {
                var attempt = 0
                while (isActive && request==radioRequest) {
                val link = TncLink(call, AprsTnc(codec.protocol, channel), path, transmit, channel) { event("diagnostic", it) }
                tnc = link
                radioChat = ChatSession(call, link, store, scope) { event("chatError", it) }
                event("tncStatus", "Opening $name…")
                try {
                    val open = tncDevices.prepare(kind, device, host.trim(), port, baud)
                    link.run(open, {
                        attempt = 0
                        event("diagnostic", link.diagnostics())
                        event("tncStatus", if (transmit) "$name · RF transmit enabled" else "$name · receive only")
                    }) { packet ->
                        val now = System.currentTimeMillis()
                        stations.receive(packet.line(), now)
                        val snapshot = stations.snapshot(now)
                        withContext(Dispatchers.Main) { rfStations=snapshot;publishStations(); event("diagnostic", link.diagnostics()) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (tnc === link && request==radioRequest && isActive) {
                        radioChat?.close()
                        radioChat = null
                        tnc = null
                        val seconds = MobileRadio.retrySeconds(attempt++)
                        event("tncStatus", "$name · " + (error.message ?: "connection failed") + " · retry in ${seconds}s")
                        link.close()
                        delay(seconds * 1000L)
                    } else return@launch
                } finally {
                    link.close()
                }
                }
            }
        }
    }

    private fun publishStations() {
        val stations=(internetStations+rfStations).sortedByDescending {it.heardAtMillis}.distinctBy {it.callsign}
        val points = JSONArray()
        for (station in stations) {
            points.put(JSONObject().put("name", station.callsign)
                .put("lat", station.latitude).put("lon", station.longitude))
        }
        event("stations", points.toString())
    }

    private var policy = AdaptiveBeaconPolicy(BeaconTiming())
    private var mode = ""
    private var sending: Job? = null
    private val directory = context.getExternalFilesDir(null) ?: context.filesDir
    private val recorder = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var track: java.io.File? = null

    fun position(location: android.location.Location) {
        val now = System.currentTimeMillis()
        if (!location.hasAccuracy() || location.accuracy !in 0f..100f || now-location.time !in 0..30000) return
        val p = BeaconPosition(AprsFix(location.latitude, location.longitude, location.time, location.accuracy.toDouble()),
            location.speed.takeIf { location.hasSpeed() }?.times(1.94384449),
            location.bearing.takeIf { location.hasBearing() }?.toDouble(),
            location.altitude.takeIf { location.hasAltitude() })
        if (preferences.getString("gpx", "on") == "on") {
            val file = track ?: java.io.File(directory, "APRSERJa-" + now + ".gpx").also { track = it }
            recorder.execute {
                try {
                    RandomAccessFile(file, "rw").use { out ->
                        if (out.length() == 0L) out.writeBytes(MobileRadio.gpxStart)
                        else out.seek(out.length() - MobileRadio.gpxEnd.length)
                        out.writeBytes(MobileRadio.gpxPoint(p) + MobileRadio.gpxEnd)
                    }
                } catch (e: Exception) { event("diagnostic", "GPX write failed: " + e.message) }
            }
        } else track = null
        val selected = preferences.getString("beacon", "off").orEmpty()
        if (selected != mode) {
            policy = AdaptiveBeaconPolicy(BeaconTiming(smart = selected == "smart"))
            mode = selected
        }
        val link = tnc ?: return
        if (selected !in listOf("genius", "smart") || !link.canSend() || sending?.isActive == true) return
        val decision = policy.evaluate(p, now, true) ?: return
        val activePolicy = policy
        sending = scope.launch {
            try {
                link.beacon(p)
                activePolicy.sent(p, now)
                event("diagnostic", link.diagnostics() + " · Beacon: " + decision.reason)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { event("diagnostic", "Beacon write failed: " + e.message) }
        }
    }

    fun exportGpx(context: Context, uri: android.net.Uri) {
        recorder.execute {
            try {
                val latest = directory.listFiles()?.filter { it.name.startsWith("APRSERJa-") && it.extension == "gpx" }
                    ?.maxByOrNull { it.lastModified() } ?: error("No GPS fixes recorded yet")
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out ->
                    latest.inputStream().use { it.copyTo(out) }
                }
                event("diagnostic", "GPX exported")
            } catch (e: Exception) { event("diagnostic", "GPX export failed: " + e.message) }
        }
    }

    fun querySetup() {
        scope.launch {
            try {
                checkNotNull(tnc) { "Connect a Mobilinkd KISS TNC first" }.querySetup {event("setupReading", it)}
            } catch (e: Exception) { event("setupReading", e.message ?: "Query failed") }
        }
    }

    fun disconnectInternet() {
        scope.launch {
            internetRequest++
            stopInternet()
            event("status","Disconnected")
        }
    }

    fun disconnectRadio() {
        scope.launch {
            radioRequest++
            stopRadio()
            event("tncStatus","Disconnected")
        }
    }

    fun close() {
        stopInternet()
        stopRadio()
        recorder.shutdown()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { store.shutdown() }
    }

    private fun stopInternet() {
        observers?.cancel()
        observers=null
        internetChat?.close()
        internetChat=null
        client?.stop()
        client=null
        internetStations=emptyList()
        publishStations()
    }

    private fun stopRadio() {
        sending?.cancel()
        radioChat?.close()
        radioChat=null
        val job=tncJob
        val link=tnc
        tncJob=null
        tnc=null
        job?.cancel()
        link?.close()
        radioAccount=""
        rfStations=emptyList()
        publishStations()
    }

    private suspend fun saved(error: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            event("chatError", error)
        }
    }

    private fun legacyRows(json: String, outgoing: Boolean): List<ImportedChat> = buildList {
        val records = JSONArray(json.ifEmpty { "[]" })
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val account = record.optString("account").trim().uppercase()
            val peer = record.optString("peer").trim().uppercase()
            if (account.isEmpty() || peer.isEmpty()) continue
            val id = record.optString("id").takeIf { !record.isNull("id") && it.isNotEmpty() }
            val body = if (outgoing) record.optString("body") else record.optString("text").removePrefix("$peer · ")
            val status = if (outgoing) when (record.optString("state")) {
                "queued" -> "QUEUED"
                "acknowledged" -> "ACKNOWLEDGED"
                "rejected" -> "REJECTED"
                else -> "NO_ACK"
            } else "RECEIVED"
            val attempts = record.optInt("attempts").coerceIn(0, 5)
            val lastSend = if (attempts > 0) (record.optLong("next") - 30_000L * attempts).coerceAtLeast(0) else 0
            // Preserve malformed historic rows for display, without submitting them on air.
            val safeStatus = if (status == "QUEUED" &&
                (!AprsProtocol.validCallsign(account) || !AprsMessages.validAddressee(peer) ||
                    !AprsMessages.validText(body) || id?.matches(Regex("[A-Za-z0-9]{1,5}")) != true)
            ) "NO_ACK" else status
            add(ImportedChat(account, peer, body, outgoing, id, record.optLong("time").takeIf { it > 0 },
                safeStatus, attempts, lastSend))
        }
    }
}
