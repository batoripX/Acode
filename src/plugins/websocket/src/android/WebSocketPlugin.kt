package com.foxdebug.websocket

import android.util.Log
import okhttp3.OkHttpClient
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebSocketPlugin : CordovaPlugin() {

    private var okHttpMainClient: OkHttpClient? = null

    override fun pluginInitialize() {
        okHttpMainClient = OkHttpClient()
    }

    @Throws(JSONException::class)
    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        cordova.threadPool.execute {
            when (action) {
                "connect" -> {
                    val url = args.optString(0)
                    val protocols = args.optJSONArray(1)
                    val headers = args.optJSONObject(2)
                    val binaryType = if (args.isNull(3)) null else args.optString(3)
                    val id = UUID.randomUUID().toString()

                    val client = okHttpMainClient ?: return@execute callbackContext.error("OkHttpClient not initialized")

                    val instance = WebSocketInstance(
                        url,
                        protocols,
                        headers,
                        binaryType,
                        client,
                        cordova,
                        id
                    )
                    instances[id] = instance
                    callbackContext.success(id)
                }

                "send" -> {
                    val instanceId = args.optString(0)
                    val message = args.optString(1)
                    val isBinary = args.optBoolean(2, false)

                    val inst = instances[instanceId]
                    Log.d(TAG, "send called")
                    if (inst != null) {
                        inst.send(message, isBinary)
                        callbackContext.success()
                    } else {
                        callbackContext.error("Invalid instance ID")
                    }
                }

                "close" -> {
                    val instanceId = args.optString(0)
                    val code = args.optInt(1, 1000)
                    val reason = args.optString(2, "Normal closure")

                    val inst = instances[instanceId]
                    if (inst != null) {
                        val error = inst.close(code, reason)
                        when {
                            error == null -> callbackContext.success()
                            error.isNotEmpty() -> callbackContext.error(error)
                        }
                    } else {
                        callbackContext.error("Invalid instance ID")
                    }
                }

                "registerListener" -> {
                    val instanceId = args.optString(0)
                    val inst = instances[instanceId]
                    if (inst != null) {
                        inst.setCallback(callbackContext)
                    } else {
                        callbackContext.error("Invalid instance ID")
                    }
                }

                "setBinaryType" -> {
                    val instanceId = args.optString(0)
                    val type = args.optString(1)

                    val inst = instances[instanceId]
                    if (inst != null) {
                        inst.setBinaryType(type)
                    } else {
                        Log.d(
                            TAG,
                            "setBinaryType called for instanceId=$instanceId but it's not found. Ignoring..."
                        )
                    }
                }

                "listClients" -> {
                    val clientIds = JSONArray()
                    instances.keys.forEach { clientId ->
                        clientIds.put(clientId)
                    }
                    callbackContext.success(clientIds)
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        instances.values.forEach { instance ->
            instance.close()
        }
        instances.clear()
        okHttpMainClient?.dispatcher?.executorService?.shutdown()
        Log.i(TAG, "Cleaned up on destroy")
    }

    companion object {
        private const val TAG = "WebSocketPlugin"
        private val instances = ConcurrentHashMap<String, WebSocketInstance>()

        @JvmStatic
        fun removeInstance(instanceId: String) {
            instances.remove(instanceId)
        }
    }
}
