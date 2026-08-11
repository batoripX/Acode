package com.foxdebug.websocket

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketInstance(
    url: String,
    protocols: JSONArray?,
    headers: JSONObject?,
    var binaryType: String?,
    okHttpMainClient: OkHttpClient,
    private val cordova: CordovaInterface,
    private val instanceId: String
) : WebSocketListener() {

    private var webSocket: WebSocket? = null
    private var callbackContext: CallbackContext? = null
    private var extensions: String = ""
    private var protocol: String? = ""
    private var readyState: Int = 0 // CONNECTING

    init {
        val client = okHttpMainClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val requestBuilder = Request.Builder().url(url)

        // Custom headers support
        headers?.keys()?.forEach { key ->
            val value = headers.optString(key)
            requestBuilder.addHeader(key, value)
        }

        // Sec-WebSocket-Protocol header support
        if (protocols != null && protocols.length() > 0) {
            val protocolHeader = (0 until protocols.length())
                .joinToString(",") { protocols.optString(it) }
            if (protocolHeader.isNotEmpty()) {
                requestBuilder.addHeader("Sec-WebSocket-Protocol", protocolHeader)
            }
        }

        client.newWebSocket(requestBuilder.build(), this)
    }

    fun setCallback(callbackContext: CallbackContext) {
        this.callbackContext = callbackContext
        val result = PluginResult(PluginResult.Status.NO_RESULT)
        result.keepCallback = true
        callbackContext.sendPluginResult(result)
    }

    fun send(message: String, isBinary: Boolean) {
        val socket = webSocket
        if (socket != null) {
            Log.d(
                TAG,
                "websocket instanceId=$instanceId received send(..., isBinary=$isBinary) action call, sending message=$message"
            )
            if (isBinary) {
                sendBinary(socket, message)
                return
            }
            socket.send(message)
        } else {
            Log.d(
                TAG,
                "websocket instanceId=$instanceId received send(..., isBinary=$isBinary) ignoring... as webSocket is null"
            )
        }
    }

    private fun sendBinary(socket: WebSocket, base64Data: String) {
        val data = Base64.decode(base64Data, Base64.DEFAULT)
        socket.send(ByteString.of(*data))
    }

    @JvmOverloads
    fun close(code: Int = DEFAULT_CLOSE_CODE, reason: String = DEFAULT_CLOSE_REASON): String? {
        val socket = webSocket
        return if (socket != null) {
            readyState = 2 // CLOSING
            try {
                val result = socket.close(code, reason)
                Log.d(
                    TAG,
                    "websocket instanceId=$instanceId received close() action call, code=$code reason=$reason close result: $result"
                )

                if (!result) null else null
            } catch (e: Exception) {
                e.message
            }
        } else {
            Log.d(
                TAG,
                "websocket instanceId=$instanceId received close() action call, ignoring... as webSocket is null"
            )
            ""
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        this.webSocket = webSocket
        readyState = 1 // OPEN
        extensions = response.headers("Sec-WebSocket-Extensions").toString()
        protocol = response.header("Sec-WebSocket-Protocol")
        Log.i(TAG, "websocket instanceId=$instanceId Opened, received extensions=$extensions")
        sendEvent("open", null, isBinary = false, parseAsText = false)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d(TAG, "websocket instanceId=$instanceId Received message: $text")
        sendEvent("message", text, isBinary = false, parseAsText = false)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(TAG, "websocket instanceId=$instanceId Received message(bytes/binary): ${bytes}")

        try {
            if ("arraybuffer" == binaryType) {
                val base64 = bytes.base64()
                sendEvent("message", base64, isBinary = true, parseAsText = false)
            } else {
                sendEvent("message", bytes.utf8(), isBinary = true, parseAsText = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        readyState = 2 // CLOSING
        Log.i(TAG, "websocket instanceId=$instanceId is Closing code: $code reason: $reason")
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        readyState = 3 // CLOSED
        Log.i(TAG, "websocket instanceId=$instanceId Closed code: $code reason: $reason")
        val closedEvent = JSONObject().apply {
            try {
                put("code", code)
                put("reason", reason)
            } catch (e: JSONException) {
                Log.e(TAG, "Error creating close event", e)
            }
        }
        sendEvent("close", closedEvent.toString(), isBinary = false, parseAsText = false)
        WebSocketPlugin.removeInstance(instanceId)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response) {
        readyState = 3 // CLOSED
        sendEvent("error", t.message, isBinary = false, parseAsText = false)
        Log.e(TAG, "websocket instanceId=$instanceId Error: ${t.message}")
    }

    private fun sendEvent(type: String, data: String?, isBinary: Boolean, parseAsText: Boolean) {
        val callback = callbackContext ?: return
        try {
            val event = JSONObject().apply {
                put("type", type)
                put("extensions", extensions)
                put("readyState", readyState)
                put("isBinary", isBinary)
                put("parseAsText", parseAsText)
                if (data != null) put("data", data)
            }
            Log.d(TAG, "sending event: $type eventObj $event")
            val result = PluginResult(PluginResult.Status.OK, event).apply {
                keepCallback = true
            }
            callback.sendPluginResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending event", e)
        }
    }

    companion object {
        private const val TAG = "WebSocketInstance"
        private const val DEFAULT_CLOSE_CODE = 1000
        private const val DEFAULT_CLOSE_REASON = "Normal closure"
    }
}
