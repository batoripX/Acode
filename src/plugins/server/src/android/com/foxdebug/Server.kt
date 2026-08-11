package com.foxdebug.server

import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class Server : CordovaPlugin() {

    private val servers = ConcurrentHashMap<Int, NanoHTTPDWebserver>()

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
    }

    @Throws(JSONException::class)
    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        return when (action) {
            "start" -> {
                try {
                    startServer(args, callbackContext)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                true
            }
            "setOnRequestHandler" -> {
                setOnRequestHandler(args, callbackContext)
                true
            }
            "stop" -> {
                stopServer(args, callbackContext)
                true
            }
            "send" -> {
                sendResponse(args, callbackContext)
                true
            }
            else -> false // Returning false results in a "MethodNotFound" error.
        }
    }

    /**
     * Starts the server
     */
    @Throws(JSONException::class, IOException::class)
    private fun startServer(args: JSONArray, callbackContext: CallbackContext) {
        val port = if (args.length() >= 1) args.getInt(0) else 8080

        val existingServer = servers[port]
        if (existingServer != null) {
            callbackContext.success("Server started on port $port")
            return
        }

        try {
            val server = NanoHTTPDWebserver(port, cordova.context)
            server.start()
            servers[port] = server
            callbackContext.success("Server started on port $port")
        } catch (e: Exception) {
            callbackContext.sendPluginResult(
                PluginResult(PluginResult.Status.ERROR, e.message)
            );
        }
    }

    private fun setOnRequestHandler(args: JSONArray, callbackContext: CallbackContext) {
        val port = args.getInt(0)
        val server = servers[port]
        if (server == null) {
            callbackContext.error("Server not started on port $port")
            return
        }
        server.onRequestCallbackContext = callbackContext
    }

    /**
     * Stops the server
     */
    @Throws(JSONException::class)
    private fun stopServer(args: JSONArray, callbackContext: CallbackContext) {
        val port = args.getInt(0)
        val server = servers.remove(port)
        if (server == null) {
            callbackContext.error("Server not started on port $port")
            return
        }
        server.stop()
        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK))
    }

    /**
     * Will be called if the js context sends a response to the webserver
     *
     * @param args {UUID: {...}}
     */
    @Throws(JSONException::class)
    private fun sendResponse(args: JSONArray, callbackContext: CallbackContext) {
        val port = args.getInt(0)
        val server = servers[port]
        if (server == null) {
            callbackContext.sendPluginResult(
                PluginResult(PluginResult.Status.ERROR, "Server not running")
            )
            return
        }
        
        val uuid = args.getString(1)
        val responseData = args.get(2)
        server.responses[uuid] = responseData
        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK))
    }
}
