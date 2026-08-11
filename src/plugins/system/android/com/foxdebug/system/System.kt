package com.foxbyte.acode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings.Global
import android.util.Log
import android.view.View
import android.view.Window
import androidx.core.content.FileProvider
import org.apache.cordova.CallbackContext
import org.apache.cordova.PluginResult
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter

class SystemManager(
    private val context: Context,
    private val activity: android.app.Activity,
    private val webView: CustomWebView? // Replace with your project's webview type if different
) {

    private var intentHandler: CallbackContext? = null
    private var fileProviderAuthority: String? = null

    companion object {
        private const val TAG = "System"
    }

    fun updateSystemBarsAppearance(
        decorView: View,
        window: Window,
        themeType: String,
        uiOptions: Int,
        lightNavigationBar: Int
    ) {
        val controller = decorView.windowInsetsController ?: return

        if (themeType == "light") {
            controller.setSystemBarsAppearance(uiOptions or lightNavigationBar, lightNavigationBar)
            return
        }

        controller.setSystemBarsAppearance(uiOptions and lightNavigationBar.inv(), lightNavigationBar)
    }

    private fun deprecatedFlagUiLightStatusBar(): Int = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

    private fun getDeprecatedSystemUiVisibility(decorView: View): Int = decorView.systemUiVisibility

    private fun setDeprecatedSystemUiVisibility(decorView: View, visibility: Int) {
        decorView.systemUiVisibility = visibility
    }

    private fun getCordovaIntent(callback: CallbackContext) {
        val intent = activity.intent
        if (isReservedAuthIntent(intent)) {
            callback.sendPluginResult(PluginResult(PluginResult.Status.OK, JSONObject()))
            return
        }
        callback.sendPluginResult(PluginResult(PluginResult.Status.OK, getIntentJson(intent)))
    }

    private fun setIntentHandler(callback: CallbackContext) {
        intentHandler = callback
        val result = PluginResult(PluginResult.Status.NO_RESULT).apply {
            keepCallback = true
        }
        callback.sendPluginResult(result)
    }

    fun onNewIntent(intent: Intent?) {
        if (isReservedAuthIntent(intent)) return

        intentHandler?.let { handler ->
            val result = PluginResult(PluginResult.Status.OK, getIntentJson(intent)).apply {
                keepCallback = true
            }
            handler.sendPluginResult(result)
        }
    }

    private fun isReservedAuthIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if ("acode" != data.scheme) return false

        return "auth" == data.host && "/callback" == data.path
    }

    private fun getIntentJson(intent: Intent?): JSONObject {
        val json = JSONObject()
        if (intent == null) return json

        try {
            json.put("action", intent.action)
            json.put("data", intent.dataString)
            json.put("type", intent.type)
            json.put("package", intent.`package`)
            json.put("extras", getExtrasJson(intent.extras))
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return json
    }

    private fun getExtrasJson(extras: Bundle?): JSONObject {
        val json = JSONObject()
        if (extras != null) {
            for (key in extras.keySet()) {
                try {
                    when (val value = extras.get(key)) {
                        is String -> json.put(key, value)
                        is Int -> json.put(key, value)
                        is Long -> json.put(key, value)
                        is Double -> json.put(key, value)
                        is Float -> json.put(key, value)
                        is Boolean -> json.put(key, value)
                        is Bundle -> json.put(key, getExtrasJson(value))
                        else -> json.put(key, value?.toString())
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }
        return json
    }

    private fun getContentProviderUri(fileUri: String?): Uri? = getContentProviderUri(fileUri, "")

    private fun getContentProviderUri(fileUri: String?, filename: String?): Uri? {
        if (fileUri.isNullOrEmpty()) return null

        val uri = Uri.parse(fileUri) ?: return null

        if ("file".equalsIgnoreCase(uri.scheme)) {
            val path = uri.path ?: return null
            val originalFile = File(path)
            if (!originalFile.exists()) {
                Log.e(TAG, "File does not exist for URI: $fileUri")
                return null
            }

            val authority = getFileProviderAuthority() ?: run {
                Log.e(TAG, "No FileProvider authority available.")
                return null
            }

            return try {
                FileProvider.getUriForFile(context, authority, originalFile)
            } catch (ex: Exception) {
                when (ex) {
                    is IllegalArgumentException, is SecurityException -> {
                        try {
                            val cacheCopy = ensureShareableCopy(originalFile, filename)
                            FileProvider.getUriForFile(context, authority, cacheCopy)
                        } catch (copyError: Exception) {
                            Log.e(TAG, "Failed to expose file via FileProvider", copyError)
                            null
                        }
                    }
                    else -> throw ex
                }
            }
        }
        return uri
    }

    @Throws(IOException::class)
    private fun ensureShareableCopy(source: File, displayName: String?): File {
        val cacheRoot = File(context.cacheDir, "shared")
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
            throw IOException("Unable to create shared cache directory")
        }

        var resolvedName = displayName?.takeIf { it.isNotEmpty() }?.let { File(it).name }
        if (resolvedName.isNullOrEmpty()) resolvedName = source.name
        if (resolvedName.isNullOrEmpty()) resolvedName = "shared-file"

        val target = ensureUniqueFile(File(cacheRoot, resolvedName))
        copyFile(source, target)
        return target
    }

    private fun ensureUniqueFile(target: File): File {
        if (!target.exists()) return target

        val name = target.name
        var prefix = name
        var suffix = ""
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0) {
            prefix = name.substring(0, dotIndex)
            suffix = name.substring(dotIndex)
        }

        var index = 1
        var candidate = target
        while (candidate.exists()) {
            candidate = File(target.parentFile, "$prefix-$index$suffix")
            index++
        }
        return candidate
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
    }

    private fun grantUriPermissions(intent: Intent, uri: Uri?, flags: Int) {
        if (uri == null) return
        val pm = context.packageManager
        val resInfoList: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            context.grantUriPermission(packageName, uri, flags)
        }
    }

    private fun resolveMimeType(currentMime: String?, uri: Uri?, filename: String?): String {
        if (!currentMime.isNullOrEmpty() && currentMime != "*/*") {
            return currentMime
        }

        var mime: String? = uri?.let { context.contentResolver.getType(it) }

        if (mime.isNullOrEmpty() && filename != null) {
            mime = getMimeTypeFromExtension(filename)
        }

        if (mime.isNullOrEmpty() && uri?.path != null) {
            mime = getMimeTypeFromExtension(uri.path!!)
        }

        return if (!mime.isNullOrEmpty()) mime else "*/*"
    }

    private fun getMimeTypeFromExtension(path: String): String? {
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(path)
        return if (extension != null) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else null
    }

    private fun getFileProviderAuthority(): String? {
        if (!fileProviderAuthority.isNullOrEmpty()) {
            return fileProviderAuthority
        }

        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packageInfo: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            packageInfo.providers?.forEach { providerInfo: ProviderInfo? ->
                if (providerInfo?.name == FileProvider::class.java.name) {
                    fileProviderAuthority = providerInfo.authority
                    return@forEach
                }
            }
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Unable to inspect package providers for FileProvider authority.", error)
        }

        if (fileProviderAuthority.isNullOrEmpty()) {
            fileProviderAuthority = "${context.packageName}.provider"
        }

        return fileProviderAuthority
    }

    private fun isPackageInstalled(
        packageName: String,
        packageManager: PackageManager,
        callback: CallbackContext
    ): Boolean {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getGlobalSetting(setting: String, callback: CallbackContext) {
        val value = Global.getFloat(context.contentResolver, setting, -1f).toInt()
        callback.success(value)
    }

    private fun clearCache(callback: CallbackContext) {
        webView?.clearCache(true)
        callback.success("Cache cleared")
    }

    private fun setInputType(type: String) {
        val mode = when (type) {
            "NO_SUGGESTIONS" -> 0
            "NO_SUGGESTIONS_AGGRESSIVE" -> 1
            else -> -1
        }
        webView?.setInputType(mode)
    }

    private fun setNativeContextMenuDisabled(disabled: Boolean) {
        webView?.setNativeContextMenuDisabled(disabled)
    }

    private fun extractAsset(assetName: String, destinationPath: String, callback: CallbackContext) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destinationPath).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            callback.success()
        } catch (e: IOException) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            callback.error(sw.toString())
        }
    }
}

// Extension helper for String.equalsIgnoreCase
private fun String.equalsIgnoreCase(other: String?): Boolean = this.equals(other, ignoreCase = true)
