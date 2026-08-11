package com.foxdebug.crashhandler

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler : CordovaPlugin() {

    companion object {
        private const val TAG = "CrashHandler"
    }

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        Log.d(TAG, "Initializing CrashHandler...")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                Log.e(TAG, "Uncaught native exception detected!", ex)

                val stackTrace = StringWriter().use { sw ->
                    PrintWriter(sw).use { pw ->
                        ex.printStackTrace(pw)
                        sw.toString()
                    }
                }

                val context: Context = cordova.activity.applicationContext
                val intent = Intent(context, CrashActivity::class.java).apply {
                    putExtra("error_type", "Native Crash")
                    putExtra("error_message", ex.message ?: ex.toString())
                    putExtra("stack_trace", stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                context.startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to launch CrashActivity", e)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
