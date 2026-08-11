package com.foxdebug.acode.rk.exec.terminal

import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class BackgroundExecutor : CordovaPlugin() {

    private val processes = ConcurrentHashMap<String, Process>()
    private val processInputs = ConcurrentHashMap<String, OutputStream>()
    private val processCallbacks = ConcurrentHashMap<String, CallbackContext>()
    private val processDetails = ConcurrentHashMap<String, ProcessDetails>()
    private lateinit var processManager: ProcessManager

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        processManager = ProcessManager(cordova.context)
    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        return when (action) {
            "start" -> {
                val pid = UUID.randomUUID().toString()
                startProcess(pid, args.getString(0), args.getString(1) == "true", callbackContext)
                true
            }
            "write" -> {
                writeToProcess(args.getString(0), args.getString(1), callbackContext)
                true
            }
            "stop" -> {
                stopProcess(args.getString(0), callbackContext)
                true
            }
            "exec" -> {
                exec(args.getString(0), args.getString(1) == "true", callbackContext)
                true
            }
            "isRunning" -> {
                isProcessRunning(args.getString(0), callbackContext)
                true
            }
            "listProcesses" -> {
                listProcesses(callbackContext)
                true
            }
            "listAllProcesses" -> {
                listAllProcesses(callbackContext)
                true
            }
            "killProcess" -> {
                killProcess(args.getInt(0), callbackContext)
                true
            }
            "loadLibrary" -> {
                loadLibrary(args.getString(0), callbackContext)
                true
            }
            "setProotDebug" -> {
                ProcessManager.prootDebug = args.getBoolean(0)
                callbackContext.success("PRoot debug ${if (ProcessManager.prootDebug) "enabled" else "disabled"}")
                true
            }
            else -> {
                callbackContext.error("Unknown action: $action")
                false
            }
        }
    }

    private fun exec(cmd: String, useAlpine: Boolean, callbackContext: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val result = processManager.executeCommand(cmd, useAlpine)
                if (result.isSuccess) {
                    callbackContext.success(result.stdout)
                } else {
                    callbackContext.error(result.errorMessage)
                }
            } catch (e: Exception) {
                callbackContext.error("Exception: ${e.message}")
            }
        }
    }

    private fun startProcess(pid: String, cmd: String, useAlpine: Boolean, callbackContext: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val builder = processManager.createProcessBuilder(cmd, useAlpine)
                val process = builder.start()

                val pidVal = ProcessUtils.getPid(process)
                processes[pid] = process
                processInputs[pid] = process.outputStream
                processCallbacks[pid] = callbackContext
                processDetails[pid] = ProcessDetails(cmd, useAlpine, pidVal)

                sendPluginResult(callbackContext, pid, keepCallback = true)

                // Stream stdout
                thread(start = true) {
                    StreamHandler.streamOutput(process.inputStream) { line ->
                        sendPluginMessage(pid, "stdout:$line")
                    }
                }

                // Stream stderr
                thread(start = true) {
                    StreamHandler.streamOutput(process.errorStream) { line ->
                        sendPluginMessage(pid, "stderr:$line")
                    }
                }

                val exitCode = process.waitFor()
                sendPluginMessage(pid, "exit:$exitCode")
                cleanup(pid)
            } catch (e: Exception) {
                callbackContext.error("Failed to start process: ${e.message}")
            }
        }
    }

    private fun writeToProcess(pid: String, input: String, callbackContext: CallbackContext) {
        try {
            val os = processInputs[pid]
            if (os != null) {
                StreamHandler.writeToStream(os, input)
                callbackContext.success("Written to process")
            } else {
                callbackContext.error("Process not found or closed")
            }
        } catch (e: IOException) {
            callbackContext.error("Write error: ${e.message}")
        }
    }

    private fun stopProcess(pid: String, callbackContext: CallbackContext) {
        val process = processes[pid]
        if (process != null) {
            ProcessUtils.killProcessTree(process)
            cleanup(pid)
            callbackContext.success("Process terminated")
        } else {
            callbackContext.error("No such process")
        }
    }

    private fun isProcessRunning(pid: String, callbackContext: CallbackContext) {
        val process = processes[pid]
        if (process != null) {
            val isAlive = ProcessUtils.isAlive(process)
            val status = if (isAlive) "running" else "exited"
            if (!isAlive) cleanup(pid)
            callbackContext.success(status)
        } else {
            callbackContext.success("not_found")
        }
    }

    private fun listProcesses(callbackContext: CallbackContext) {
        val result = JSONArray()

        for ((id, process) in processes) {
            if (!ProcessUtils.isAlive(process)) continue
            val details = processDetails[id] ?: continue

            try {
                val item = JSONObject().apply {
                    put("id", id)
                    put("command", details.command)
                    put("alpine", details.alpine)
                    put("startedAt", details.startedAt)
                    put("pid", details.pid)
                }
                result.put(item)
            } catch (ignored: JSONException) {
                // Internal values always serialize safely.
            }
        }

        callbackContext.success(result)
    }

    private fun loadLibrary(path: String, callbackContext: CallbackContext) {
        callbackContext.error(
            "This feature is no longer supported. Loading native libraries directly from JavaScript is no longer allowed due to security reasons."
        )
    }

    private fun sendPluginResult(ctx: CallbackContext, message: String, keepCallback: Boolean) {
        val result = PluginResult(PluginResult.Status.OK, message).apply {
            keepCallback = keepCallback
        }
        ctx.sendPluginResult(result)
    }

    private fun sendPluginMessage(pid: String, message: String) {
        processCallbacks[pid]?.let { ctx ->
            sendPluginResult(ctx, message, keepCallback = true)
        }
    }

    private fun cleanup(pid: String) {
        processes.remove(pid)
        processInputs.remove(pid)
        processCallbacks.remove(pid)
        processDetails.remove(pid)
    }

    private fun listAllProcesses(callbackContext: CallbackContext) {
        try {
            callbackContext.success(ProcessUtils.getAllProcesses())
        } catch (e: Exception) {
            callbackContext.error("Failed to list all processes: ${e.message}")
        }
    }

    private fun killProcess(pid: Int, callbackContext: CallbackContext) {
        try {
            ProcessUtils.killProcess(pid)
            callbackContext.success("Process terminated")
        } catch (e: Exception) {
            callbackContext.error("Failed to kill process: ${e.message}")
        }
    }

    private data class ProcessDetails(
        val command: String,
        val alpine: Boolean,
        val pid: Long,
        val startedAt: Long = System.currentTimeMillis()
    )
}
