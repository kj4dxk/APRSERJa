// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import android.content.Context
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
import org.aprsfoundation.core.AprsConnectionState
import org.aprsfoundation.core.AprsIsClient
import org.aprsfoundation.core.AprsIsConfig
import org.aprsfoundation.core.AprsMessages
import org.aprsfoundation.core.AprsProtocol
import org.aprsfoundation.core.ChatSession
import org.aprsfoundation.core.ImportedChat
import org.json.JSONArray
import org.json.JSONObject

/** Activity-scoped adapter from the bundled WebView to the shared APRS and chat engine. */
class MobileAprs(context: Context, private val event: (String, String) -> Unit) {
    private data class Conversation(val account: String, val peer: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val store = MobileChatStore(context.applicationContext)
    private val migration = CompletableDeferred<Unit>()
    private val conversation = MutableStateFlow(Conversation("", ""))
    private var client: AprsIsClient? = null
    private var chat: ChatSession? = null
    private var observers: Job? = null
    private var connectionRequest = 0L

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
            val request = ++connectionRequest
            migration.await()
            if (request != connectionRequest) return@launch
            stop()
            val next = AprsIsClient()
            client = next
            chat = ChatSession(call, next, store, scope) { event("chatError", it) }
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
                    next.stations.collect { stations ->
                        val points = JSONArray()
                        for (station in stations) {
                            points.put(JSONObject().put("name", station.callsign)
                                .put("lat", station.latitude).put("lon", station.longitude))
                        }
                        event("stations", points.toString())
                    }
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionRequest++
            stop()
            event("status", "Disconnected")
        }
    }

    fun close() {
        stop()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { store.shutdown() }
    }

    private fun stop() {
        observers?.cancel()
        observers = null
        chat?.close()
        chat = null
        client?.stop()
        client = null
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
