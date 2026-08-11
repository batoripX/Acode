package com.foxdebug.acode.rk.exec.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TerminalService : Service() {

    private val processes = ConcurrentHashMap<String, Process>()
    private val processInputs = ConcurrentHashMap<String, OutputStream>()
    private val clientMessengers = ConcurrentHashMap<String, Messenger>()
    private val processDetails = ConcurrentHashMap<String, ProcessDetails>()
    private val threadPool = Executors.newCachedThreadPool()

    private val serviceMessenger = Messenger(ServiceHandler())

    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false
    private lateinit var processManager: ProcessManager

    override fun onCreate() {
        super.onCreate()
        processManager = ProcessManager(this)
        if (defaultForeground) {
            createNotificationChannel()
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKE_LOCK -> toggleWakeLock()
            MOVE_TO_BACKGROUND -> {
                defaultForeground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            MOVE_TO_FOREGROUND -> {
                defaultForeground = true
                createNotificationChannel()
                updateNotification()
            }
        }
        return START_STICKY
    }

    private inner class ServiceHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val bundle = msg.data
            val id = bundle.getString("id") ?: return
            val clientMessenger = msg.replyTo

            when (msg.what) {
                MSG_START_PROCESS -> {
                    val cmd = bundle.getString("cmd") ?: ""
                    val alpine = "true" == bundle.getString("alpine")
                    clientMessengers[id] = clientMessenger
                    startProcess(id, cmd, alpine)
                }
                MSG_WRITE_TO_PROCESS -> {
                    val input = bundle.getString("input") ?: ""
                    writeToProcess(id, input)
                }
                MSG_STOP_PROCESS -> stopProcess(id)
                MSG_IS_RUNNING -> isProcessRunning(id, clientMessenger)
                MSG_EXEC -> {
                    val execCmd = bundle.getString("cmd") ?: ""
                    val execAlpine = "true" == bundle.getString("alpine")
                    clientMessengers[id] = clientMessenger
                    exec(id, execCmd, execAlpine)
                }
                MSG_LIST_PROCESSES -> listProcesses(id, clientMessenger)
            }
        }
    }

    private fun toggleWakeLock() {
        if (isWakeLockHeld) {
            releaseWakeLock()
        } else {
            acquireWakeLock()
        }
        updateNotification()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AcodeTerminal:WakeLock")
        }

        wakeLock?.let {
            if (!isWakeLockHeld) {
                it.acquire()
                isWakeLockHeld = true
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (isWakeLockHeld) {
                it.release()
                isWakeLockHeld = false
            }
        }
    }

    private fun startProcess(pid: String, cmd: String, useAlpine: Boolean) {
        threadPool.execute {
            try {
                val builder = processManager.createProcessBuilder(cmd, useAlpine)
                val process = builder.start()

                val pidVal = ProcessUtils.getPid(process)
                processes[pid] = process
                processInputs[pid] = process.outputStream
                processDetails[pid] = ProcessDetails(cmd, useAlpine, pidVal)

                // Stream stdout
                threadPool.execute {
                    StreamHandler.streamOutput(process.inputStream) { line ->
                        sendMessageToClient(pid, "stdout", line)
                    }
                }

                // Stream stderr
                threadPool.execute {
                    StreamHandler.streamOutput(process.errorStream) { line ->
                        sendMessageToClient(pid, "stderr", line)
                    }
                }

                // Wait for process completion
                threadPool.execute {
                    try {
                        val exitCode = process.waitFor()
                        sendMessageToClient(pid, "exit", exitCode.toString())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        cleanup(pid)
                    }
                }
            } catch (e: IOException) {
                sendMessageToClient(pid, "stderr", "Failed to start process: ${e.message}")
                sendMessageToClient(pid, "exit", "1")
                cleanup(pid)
            }
        }
    }

    private fun exec(execId: String, cmd: String, useAlpine: Boolean) {
        threadPool.execute {
            try {
                val result = processManager.executeCommand(cmd, useAlpine)

                if (result.isSuccess) {
                    sendExecResultToClient(execId, true, result.stdout)
                } else {
                    sendExecResultToClient(execId, false, result.errorMessage)
                }
            } catch (e: Exception) {
                sendExecResultToClient(execId, false, "Exception: ${e.message}")
            } finally {
                cleanup(execId)
            }
        }
    }

    private fun sendMessageToClient(id: String, action: String, data: String) {
        val clientMessenger = clientMessengers[id] ?: return
        try {
            val msg = Message.obtain().apply {
                this.data = android.os.Bundle().apply {
                    putString("id", id)
                    putString("action", action)
                    putString("data", data)
                }
            }
            clientMessenger.send(msg)
        } catch (e: RemoteException) {
            cleanup(id)
        }
    }

    private fun sendExecResultToClient(id: String, isSuccess: Boolean, data: String) {
        val clientMessenger = clientMessengers[id] ?: return
        try {
            val msg = Message.obtain().apply {
                this.data = android.os.Bundle().apply {
                    putString("id", id)
                    putString("action", "exec_result")
                    putString("data", data)
                    putBoolean("isSuccess", isSuccess)
                }
            }
            clientMessenger.send(msg)
        } catch (e: RemoteException) {
            cleanup(id)
        }
    }

    private fun writeToProcess(pid: String, input: String) {
        try {
            processInputs[pid]?.let { os ->
                StreamHandler.writeToStream(os, input)
            }
        } catch (e: IOException) {
            // Ignored, stream may be closed
        }
    }

    private fun stopProcess(pid: String) {
        processes[pid]?.let { process ->
            ProcessUtils.killProcessTree(process)
            cleanup(pid)
        }
    }

    private fun listProcesses(requestId: String, clientMessenger: Messenger?) {
        if (clientMessenger == null) return
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
            } catch (_: JSONException) {
            }
        }

        try {
            val reply = Message.obtain().apply {
                data = android.os.Bundle().apply {
                    putString("id", requestId)
                    putString("action", "listProcesses")
                    putString("data", result.toString())
                }
            }
            clientMessenger.send(reply)
        } catch (_: RemoteException) {
        }
    }

    private fun isProcessRunning(pid: String, clientMessenger: Messenger?) {
        if (clientMessenger == null) return
        val running = processes[pid]?.let { ProcessUtils.isAlive(it) } == true

        try {
            val reply = Message.obtain().apply {
                data = android.os.Bundle().apply {
                    putString("id", pid)
                    putString("action", "isRunning")
                    putString("data", if (running) "running" else "stopped")
                }
            }
            clientMessenger.send(reply)
        } catch (_: RemoteException) {
        }
    }

    private fun cleanup(id: String) {
        processes.remove(id)
        processInputs.remove(id)
        clientMessengers.remove(id)
        processDetails.remove(id)
    }

    private class ProcessDetails(
        val command: String,
        val alpine: Boolean,
        val pid: Long,
        val startedAt: Long = System.currentTimeMillis()
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Terminal Executor Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateNotification() {
        val exitIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_EXIT_SERVICE
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 0, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeLockIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_TOGGLE_WAKE_LOCK
        }
        val wakeLockPendingIntent = PendingIntent.getService(
            this, 1, wakeLockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "Executor service" + if (isWakeLockHeld) " (wakelock held)" else ""
        val wakeLockButtonText = if (isWakeLockHeld) "Release Wake Lock" else "Acquire Wake Lock"

        val notificationIcon = resolveDrawableId("ic_notification", "ic_launcher_foreground", "ic_launcher")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Acode Service")
            .setContentText(contentText)
            .setSmallIcon(notificationIcon)
            .setOngoing(true)
            .addAction(notificationIcon, wakeLockButtonText, wakeLockPendingIntent)
            .addAction(notificationIcon, "Exit", exitPendingIntent)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        releaseWakeLock()

        for (process in processes.values) {
            ProcessUtils.killProcessTree(process)
        }

        processes.clear()
        processInputs.clear()
        clientMessengers.clear()
        processDetails.clear()
        threadPool.shutdown()
    }

    private fun resolveDrawableId(vararg names: String): Int {
        for (name in names) {
            val id = resources.getIdentifier(name, "drawable", packageName)
            if (id != 0) return id
        }
        return android.R.drawable.sym_def_app_icon
    }

    companion object {
        const val MSG_START_PROCESS = 1
        const val MSG_WRITE_TO_PROCESS = 2
        const val MSG_STOP_PROCESS = 3
        const val MSG_IS_RUNNING = 4
        const val MSG_EXEC = 5
        const val MSG_LIST_PROCESSES = 6

        const val CHANNEL_ID = "terminal_exec_channel"

        const val ACTION_EXIT_SERVICE = "com.foxdebug.acode.ACTION_EXIT_SERVICE"
        const val MOVE_TO_BACKGROUND = "com.foxdebug.acode.MOVE_TO_BACKGROUND"
        const val MOVE_TO_FOREGROUND = "com.foxdebug.acode.MOVE_TO_FOREGROUND"
        const val ACTION_TOGGLE_WAKE_LOCK = "com.foxdebug.acode.ACTION_TOGGLE_WAKE_LOCK"

        @JvmField
        var defaultForeground: Boolean = true
    }
}
