package com.foxdebug.acode.rk.exec.terminal

import android.os.Process as AndroidProcess
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

object ProcessUtils {

    private const val TAG = "ProcessUtils"

    /**
     * Gets the PID of a process using reflection
     */
    @JvmStatic
    fun getPid(process: Process): Long {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getLong(process)
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Checks if a process is still alive
     */
    @JvmStatic
    fun isAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Forcefully kills a process and its children
     */
    @JvmStatic
    fun killProcessTree(process: Process) {
        try {
            val pid = getPid(process)
            if (pid > 0) {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid"))
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to kill process tree.", error)
        }
        process.destroy()
    }

    /**
     * Forcefully kills a single process
     */
    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    fun killProcess(pid: Int) {
        val exitCode = Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
        if (exitCode != 0) {
            throw IOException("kill -9 $pid exited with code $exitCode")
        }
    }

    /**
     * Reads the cmdline file for a given process folder
     */
    private fun readCmdline(cmdlineFile: File): String {
        return try {
            if (!cmdlineFile.exists()) return ""
            val bytes = cmdlineFile.inputStream().use { input ->
                input.readNBytes(8192) // Native Kotlin stream extension (up to 8KB)
            }
            if (bytes.isEmpty()) return ""

            // Trim trailing null bytes
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) {
                end--
            }

            // Replace null bytes with spaces
            for (i in 0 until end) {
                if (bytes[i] == 0.toByte()) {
                    bytes[i] = ' '.code.toByte()
                }
            }

            String(bytes, 0, end, StandardCharsets.UTF_8)
        } catch (ignored: IOException) {
            ""
        }
    }

    /**
     * Lists all processes running under the current app UID
     */
    @JvmStatic
    fun getAllProcesses(): JSONArray {
        val processList = JSONArray()
        val myUid = AndroidProcess.myUid()
        val myPid = AndroidProcess.myPid()
        val procDir = File("/proc")
        val files = procDir.listFiles() ?: return processList

        // Filter and iterate only over numeric directories (/proc/[pid])
        files.asSequence()
            .filter { it.isDirectory && it.name.all { char -> char.isDigit() } }
            .forEach { file ->
                val pid = file.name.toIntOrNull() ?: return@forEach
                try {
                    val statusFile = File(file, "status")
                    if (!statusFile.exists()) return@forEach

                    var procName = ""
                    var procState = ""
                    var ppid = -1
                    var rss = 0L
                    var uidMatches = false

                    statusFile.useLines { lines ->
                        for (line in lines) {
                            when {
                                line.startsWith("Name:") -> procName = line.substring(5).trim()
                                line.startsWith("State:") -> procState = line.substring(6).trim()
                                line.startsWith("PPid:") -> ppid = line.substring(5).trim().toIntOrNull() ?: -1
                                line.startsWith("Uid:") -> {
                                    val uids = line.substring(4).trim().split(Regex("\\s+"))
                                    if (uids.isNotEmpty()) {
                                        val uid = uids[0].toIntOrNull()
                                        if (uid == myUid) uidMatches = true
                                    }
                                }
                                line.startsWith("VmRSS:") -> {
                                    val rssStr = line.substring(6).replace(Regex("[^0-9]"), "")
                                    rss = rssStr.toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }

                    // Skip processes that do not belong to our app UID
                    if (!uidMatches) return@forEach

                    val cmdline = readCmdline(File(file, "cmdline")).ifEmpty { procName }

                    val procObj = JSONObject().apply {
                        put("pid", pid)
                        put("ppid", ppid)
                        put("name", procName)
                        put("command", cmdline)
                        put("state", procState)
                        put("memory", rss) // in kB
                        put("isSelf", pid == myPid)
                        put("startedAt", file.lastModified())
                    }

                    processList.put(procObj)
                } catch (ignored: Exception) {
                    // Ignore processes we cannot access
                }
            }

        return processList
    }
}
