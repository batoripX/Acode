package com.foxdebug.acode.rk.plugin

import android.content.Context
import com.foxdebug.acode.rk.auth.EncryptedPreferenceManager
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Tee : CordovaPlugin() {

    // pluginId : token
    private val tokenStore = ConcurrentHashMap<String, String>()

    // assigned tokens (pluginIds)
    private val disclosed = ConcurrentHashMap.newKeySet<String>()

    // token : list of permissions
    private val permissionStore = ConcurrentHashMap<String, List<String>>()

    private lateinit var context: Context

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        context = cordova.context
    }

    override fun execute(action: String, args: JSONArray, callback: CallbackContext): Boolean {
        return when (action) {
            "get_secret" -> {
                val token = args.getString(0)
                val key = args.getString(1)
                val defaultValue = args.getString(2)

                val pluginId = getPluginIdFromToken(token) ?: run {
                    callback.error("INVALID_TOKEN")
                    return true
                }

                val prefs = EncryptedPreferenceManager(context, pluginId)
                val value = prefs.getString(key, defaultValue)
                callback.success(value)
                true
            }
            "set_secret" -> {
                val token = args.getString(0)
                val key = args.getString(1)
                val value = args.getString(2)

                val pluginId = getPluginIdFromToken(token) ?: run {
                    callback.error("INVALID_TOKEN")
                    return true
                }

                val prefs = EncryptedPreferenceManager(context, pluginId)
                prefs.setString(key, value)
                callback.success()
                true
            }
            "requestToken" -> {
                val pluginId = args.getString(0)
                val pluginJson = args.getString(1)
                handleTokenRequest(pluginId, pluginJson, callback)
                true
            }
            "grantedPermission" -> {
                val token = args.getString(0)
                val permission = args.getString(1)

                if (!permissionStore.containsKey(token)) {
                    callback.error("INVALID_TOKEN")
                    return true
                }

                val granted = grantedPermission(token, permission)
                callback.success(if (granted) 1 else 0)
                true
            }
            "listAllPermissions" -> {
                val token = args.getString(0)

                if (!permissionStore.containsKey(token)) {
                    callback.error("INVALID_TOKEN")
                    return true
                }

                val permissions = listAllPermissions(token)
                val result = JSONArray(permissions)
                callback.success(result)
                true
            }
            else -> false
        }
    }

    private fun getPluginIdFromToken(token: String): String? {
        return tokenStore.entries.firstOrNull { it.value == token }?.key
    }

    // ============================================================
    // Do not change function signatures
    fun isTokenValid(token: String, pluginId: String): Boolean {
        val storedToken = tokenStore[pluginId]
        return storedToken != null && token == storedToken
    }

    fun grantedPermission(token: String, permission: String): Boolean {
        val permissions = permissionStore[token]
        return permissions != null && permissions.contains(permission)
    }

    fun listAllPermissions(token: String): List<String> {
        val permissions = permissionStore[token] ?: return emptyList()
        return ArrayList(permissions) // return copy (safe)
    }
    // ============================================================

    @Synchronized
    private fun handleTokenRequest(
        pluginId: String,
        pluginJson: String,
        callback: CallbackContext
    ) {
        if (disclosed.contains(pluginId)) {
            callback.error("TOKEN_ALREADY_ISSUED")
            return
        }

        val token = tokenStore.getOrPut(pluginId) { UUID.randomUUID().toString() }

        try {
            val json = JSONObject(pluginJson)
            val permissions = json.optJSONArray("permissions")

            val permissionList = mutableListOf<String>()
            if (permissions != null) {
                for (i in 0 until permissions.length()) {
                    permissionList.add(permissions.getString(i))
                }
            }

            // Bind permissions to token
            permissionStore[token] = permissionList
        } catch (e: JSONException) {
            callback.error("INVALID_PLUGIN_JSON")
            return
        }

        disclosed.add(pluginId)
        callback.success(token)
    }
}
