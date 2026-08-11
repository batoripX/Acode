/*
The MIT License (MIT)

Copyright (c) 2016 Mikihiro Hayashi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package org.apache.cordova.buildinfo

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BuildInfo : CordovaPlugin() {

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        if ("init" == action) {
            val buildConfigClassName = if (args.length() > 0) args.optString(0, null) else null
            init(buildConfigClassName, callbackContext)
            return true
        }
        return false
    }

    private fun init(buildConfigClassNameInput: String?, callbackContext: CallbackContext) {
        mBuildInfoCache?.let {
            callbackContext.success(it)
            return
        }

        val activity = cordova.activity ?: run {
            callbackContext.error("Activity reference is null")
            return
        }

        val packageName = activity.packageName
        var basePackageName = packageName
        var displayName: CharSequence = ""
        var firstInstallTime = 0L

        val pm = activity.packageManager

        try {
            val flags = 0
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
            firstInstallTime = pi.firstInstallTime
            pi.applicationInfo?.let {
                displayName = it.loadLabel(pm)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "PackageManager error", e)
        }

        var buildConfigClassName = buildConfigClassNameInput ?: "$packageName.BuildConfig"

        var c: Class<*>? = runCatching { Class.forName(buildConfigClassName) }.getOrNull()

        if (c == null) {
            basePackageName = activity.javaClass.`package`?.name ?: packageName
            buildConfigClassName = "$basePackageName.BuildConfig"
            c = runCatching { Class.forName(buildConfigClassName) }.getOrNull()
        }

        if (c == null) {
            callbackContext.error("BuildConfig ClassNotFoundException for: $buildConfigClassName")
            return
        }

        val info = JSONObject()
        try {
            val debug = getClassFieldBoolean(c, "DEBUG", false)

            info.put("packageName", packageName)
            info.put("basePackageName", basePackageName)
            info.put("displayName", displayName)
            info.put("name", displayName)
            info.put("version", getClassFieldString(c, "VERSION_NAME", ""))
            info.put("versionCode", getClassFieldInt(c, "VERSION_CODE", 0))
            info.put("debug", debug)
            info.put("installDate", convertLongToDateTimeString(firstInstallTime))
            info.put("buildType", getClassFieldString(c, "BUILD_TYPE", ""))
            info.put("flavor", getClassFieldString(c, "FLAVOR", ""))

            if (debug) {
                Log.d(TAG, "packageName    : \"${info.optString("packageName")}\"")
                Log.d(TAG, "basePackageName: \"${info.optString("basePackageName")}\"")
                Log.d(TAG, "displayName    : \"${info.optString("displayName")}\"")
                Log.d(TAG, "name           : \"${info.optString("name")}\"")
                Log.d(TAG, "version        : \"${info.optString("version")}\"")
                Log.d(TAG, "versionCode    : ${info.optInt("versionCode")}")
                Log.d(TAG, "debug          : ${info.optBoolean("debug")}")
                Log.d(TAG, "buildType      : \"${info.optString("buildType")}\"")
                Log.d(TAG, "flavor         : \"${info.optString("flavor")}\"")
                Log.d(TAG, "installDate    : \"${info.optString("installDate")}\"")
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSONException building build info", e)
            callbackContext.error("JSONException: ${e.message}")
            return
        }

        mBuildInfoCache = info
        callbackContext.success(info)
    }

    companion object {
        private const val TAG = "BuildInfo"

        @Volatile
        private var mBuildInfoCache: JSONObject? = null

        private fun getClassFieldBoolean(c: Class<*>, fieldName: String, defaultValue: Boolean): Boolean {
            return runCatching {
                c.getField(fieldName).getBoolean(null)
            }.getOrDefault(defaultValue)
        }

        private fun getClassFieldString(c: Class<*>, fieldName: String, defaultValue: String): String {
            return runCatching {
                c.getField(fieldName).get(null) as? String ?: defaultValue
            }.getOrDefault(defaultValue)
        }

        private fun getClassFieldInt(c: Class<*>, fieldName: String, defaultValue: Int): Int {
            return runCatching {
                c.getField(fieldName).getInt(null)
            }.getOrDefault(defaultValue)
        }

        private fun convertLongToDateTimeString(mills: Long): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            return formatter.format(Date(mills))
        }
    }
}
