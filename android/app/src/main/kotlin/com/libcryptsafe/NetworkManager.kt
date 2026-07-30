package com.libcryptsafe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// L2 Кирпич 2a: сетевой орган, вынесенный из MainActivity.
// САМОДОСТАТОЧЕН: получает всё в конструкторе, НЕ лезет в Activity.
// Крипто-цепочки (CryptoManager/SessionManager/PrekeyManager) — ДОСЛОВНО как были.
// currentPeerId НЕ владеет — сообщает через handler, Activity сама решает.
// Свой CoroutineScope (не lifecycleScope Activity) — чтобы пережить в сервисе (2c).
class NetworkManager(
    private val serverUrl: String,
    private val client: OkHttpClient,
    private val myStableId: String,
    private val myPubKey: ByteArray?,
    private val appContext: Context,
    private val handler: MessengerEventHandler
) {
    private var webSocket: WebSocket? = null
    var isConnected = false; private set
    var handshakeDone = false; private set
    var reconnectAttempts = 0; private set
    private var intentionallyClosed = false

    private val pendingMessages = mutableMapOf<String, String>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect() {
        intentionallyClosed = false
        webSocket?.cancel()
        webSocket = null
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts = 0
                handler.onStatusChanged(true, reconnectAttempts)
                handler.onSystemMessage(SysMsg.CONNECTED)
                // Отправляем свой публичный ключ
                myPubKey?.let { pub ->
                    val json = JSONObject()
                    json.put("type", "pubkey")
                    json.put("key", Base64.encodeToString(pub, Base64.NO_WRAP))
                    json.put("senderId", myStableId)
                    ws.send(json.toString())
                }
                // X3DH: публикуем связку prekeys (публичные части)
                scope.launch {
                    try {
                        val uploadJson = PrekeyManager.buildUploadJson(appContext, myStableId)
                        ws.send(uploadJson)
                        android.util.Log.d("PREKEY_MGR", "связка prekeys опубликована на relay")
                    } catch (e: Exception) {
                        android.util.Log.e("PREKEY_MGR", "публикация: ${e.message}")
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.getString("type") == "pubkey") {
                        val peerPubKey = Base64.decode(json.getString("key"), Base64.NO_WRAP)
                        val peerId = json.optString("senderId", "UNKNOWN")
                        if (peerId.isNotEmpty() && peerId != "UNKNOWN" && peerId != myStableId) {
                            handler.onPeerIdResolved(peerId)
                        }
                        val result = CryptoManager.computeSharedKey(peerPubKey)
                        if (result == 0) {
                            handshakeDone = true
                            val fp = CryptoManager.getFingerprint()
                            handler.onHandshakeDone(fp)
                        }
                        return
                    }
                    // X3DH: ответ со связкой -> собрать первое сообщение
                    if (json.getString("type") == "prekeys_response") {
                        val targetId = json.optString("targetId", "")
                        val pText = pendingMessages.remove(targetId)
                        if (pText == null || targetId.isEmpty()) return
                        if (json.isNull("ik_sign") || json.isNull("ik_dh") || json.isNull("spk")) {
                            handler.onSystemMessage("у $targetId нет ключей на relay")
                            return
                        }
                        scope.launch {
                            try {
                                val initJson = SessionManager.buildInitialMessage(
                                    appContext, targetId, json, pText.toByteArray(Charsets.UTF_8))
                                if (initJson == null) {
                                    handler.onSystemMessage("не удалось собрать (подпись?)")
                                    return@launch
                                }
                                val payloadB64 = Base64.encodeToString(
                                    initJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                val envelope = JSONObject().apply {
                                    put("type", "msg"); put("to", targetId); put("payload", payloadB64)
                                }.toString()
                                webSocket?.send(envelope)
                                android.util.Log.d("X3DH_SEND", "первое сообщение отправлено -> $targetId")
                                handler.onSystemMessage("✓ отправлено (X3DH) $targetId")
                            } catch (e: Exception) {
                                android.util.Log.e("X3DH_SEND", "ошибка: ${e.message}")
                            }
                        }
                        return
                    }
                    // адресное сообщение — распаковка конверта {from,to,payload}
                    if (json.getString("type") == "msg") {
                        val payloadB64 = json.optString("payload", "")
                        if (payloadB64.isEmpty()) return
                        val cipherBytes = Base64.decode(payloadB64, Base64.NO_WRAP)
                        if (cipherBytes.size > 64 * 1024) return   // DoS-лимит
                        try {
                            val inner = JSONObject(String(cipherBytes, Charsets.UTF_8))
                            if (inner.optString("type") == "INITIAL_HANDSHAKE") {
                                scope.launch {
                                    val result = SessionManager.handleInitialMessage(appContext, inner)
                                    if (result.content != null) {
                                        handler.onInitialHandshakeReceived(
                                            result.peerId, String(result.content, Charsets.UTF_8))
                                    } else {
                                        handler.onSystemMessage(SysMsg.DECRYPT_ERROR)
                                    }
                                }
                                return
                            }
                            if (inner.optString("type") == "CHAT_ENCRYPTED") {
                                val cipherStr = inner.optString("cipher", "")
                                scope.launch {
                                    val result = SessionManager.decryptAnySession(appContext, cipherStr)
                                    if (result?.content != null) {
                                        handler.onChatReceived(
                                            result.peerId, String(result.content, Charsets.UTF_8))
                                    } else {
                                        handler.onSystemMessage(SysMsg.DECRYPT_ERROR)
                                    }
                                }
                                return
                            }
                        } catch (_: Exception) { /* не наш JSON */ }
                    }
                } catch (e: Exception) { /* не JSON */ }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                handshakeDone = false
                handler.onStatusChanged(false, reconnectAttempts)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                handshakeDone = false
                if (!intentionallyClosed) {
                    handler.onStatusChanged(false, reconnectAttempts)
                    scheduleReconnect()
                }
            }
        })
    }

    // Универсальная отправка готового JSON
    fun sendJson(json: String) { webSocket?.send(json) }

    // X3DH: положить текст в буфер и запросить prekeys (ответ придёт в onMessage)
    fun stashPending(targetId: String, plaintext: String) {
        pendingMessages[targetId] = plaintext
    }

    fun disconnect() {
        intentionallyClosed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        isConnected = false
    }

    fun isSocketNull(): Boolean = webSocket == null

    private fun scheduleReconnect() {
        if (intentionallyClosed) return
        if (isConnected) return
        reconnectHandler.removeCallbacksAndMessages(null)
        val delaySec = minOf(1 shl reconnectAttempts, 16)
        reconnectAttempts++
        reconnectHandler.postDelayed({
            if (!isConnected && !intentionallyClosed) connect()
        }, delaySec * 1000L)
    }
}
