package com.foxdebug.ftp

import android.app.Activity
import android.content.Context
import android.util.Log
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.parser.ParserInitializationException
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOExceptionimport java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

class Ftp : CordovaPlugin() {

    private val ftpProfiles = ConcurrentHashMap<String, FTPClient>()
    private var context: Context? = null
    private var activity: Activity? = null

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        context = cordova.context
        activity = cordova.activity
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        return try {
            val method = this.javaClass.getDeclaredMethod(
                action,
                JSONArray::class.java,
                CallbackContext::class.java
            )
            method.invoke(this, args, callbackContext)
            true
        } catch (e: NoSuchMethodException) {
            callbackContext.error(e.message)
            false
        } catch (e: SecurityException) {
            callbackContext.error(e.message)
            false
        } catch (e: Exception) {
            callbackContext.error(e.message)
            false
        }
    }

    @JvmOverloads
    fun connect(args: JSONArray, callback: CallbackContext, isRetry: Boolean = false) {
        cordova.threadPool.execute {
            val port = args.optInt(1)
            val host = args.optString(0)
            val username = args.optString(2)
            val password = args.optString(3)
            val connectionMode = args.optString(6)
            val ftpId = getFtpId(host, port, username)
            var ftp: FTPClient? = null

            try {
                if (ftpProfiles.containsKey(ftpId)) {
                    ftp = ftpProfiles[ftpId]
                    val reply = ftp?.replyCode ?: 0
                    if (ftp != null && ftp.isConnected && FTPReply.isPositiveCompletion(reply)) {
                        ftp.controlEncoding = "UTF-8"
                        ftp.autodetectUTF8 = true
                        System.setProperty("ftp.client.encoding", "UTF-8")
                        ftp.sendNoOp()
                        Log.d(TAG, "FTPClient ($ftpId) is connected")
                        callback.success(ftpId)
                        return@execute
                    }
                    Log.d(TAG, "FTPClient ($ftpId) is not connected")
                    ftp?.disconnect()
                    Log.d(TAG, "FTPClient ($ftpId) disconnecting...")
                } else {
                    Log.d(TAG, "Creating new FTPClient ($ftpId)")
                    ftp = FTPClient()
                    ftpProfiles[ftpId] = ftp
                }

                Log.d(TAG, "FTPClient ($ftpId) connecting...")
                ftp.connect(host, port)
                ftp.controlKeepAliveTimeout = 300
                if ("active" == connectionMode) {
                    Log.d(TAG, "Entering Local Active mode")
                    ftp.enterLocalActiveMode()
                } else {
                    Log.d(TAG, "Entering Passive Active mode")
                    ftp.enterLocalPassiveMode()
                }

                Log.d(TAG, "FTPClient ($ftpId) logging in...")
                ftp.login(username, password)

                val reply = ftp.replyCode
                if (!FTPReply.isPositiveCompletion(reply)) {
                    ftp.disconnect()
                    Log.d(TAG, "FTPClient ($ftpId) server refused connection.")
                    callback.error("FTP server refused connection.")
                    return@execute
                }

                ftp.listHiddenFiles = true
                ftpProfiles[ftpId] = ftp
                Log.d(TAG, "FTPClient ($ftpId) connected")
                callback.success(ftpId)
            } catch (e: Exception) {
                Log.e(TAG, "FTPClient ($ftpId)", e)
                if (ftp != null) {
                    ftpProfiles.remove(ftpId)
                }

                if (!isRetry) {
                    connect(args, callback, true)
                    return@execute
                }

                callback.error(e.message)
            }
        }
    }

    fun listDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                var path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    path = "/"
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                val files = ftp.listFiles(path)
                Log.d(TAG, "FTPClient ($ftpId) Listing files in $path")
                Log.d(TAG, "FTPClient ($ftpId) Found ${files.size} files.")

                val jsonFiles = JSONArray()

                for (file in files) {
                    val filename = file.name
                    if (filename == "." || filename == "..") continue

                    val jsonFile = JSONObject().apply {
                        put("name", filename)
                        put("length", file.size)
                        put("url", joinPath(path, filename))
                    }

                    if (file.isSymbolicLink) {
                        jsonFile.put("isLink", true)
                        val linkTarget = file.link
                        jsonFile.put("link", linkTarget)
                        val linkPath = if (linkTarget.startsWith("/")) linkTarget else joinPath(path, linkTarget)
                        try {
                            val targetFiles = ftp.listFiles(linkPath)
                            if (targetFiles.isNotEmpty()) {
                                val targetFile = targetFiles[0]
                                jsonFile.put("isFile", targetFile.isFile)
                                jsonFile.put("isDirectory", targetFile.isDirectory)
                                jsonFile.put("url", linkPath)
                            } else {
                                jsonFile.put("isFile", false)
                                jsonFile.put("isDirectory", false)
                            }
                        } catch (e: Exception) {
                            jsonFile.put("isFile", false)
                            jsonFile.put("isDirectory", false)
                        }
                    } else {
                        jsonFile.put("isLink", false)
                        jsonFile.put("isDirectory", file.isDirectory)
                        jsonFile.put("isFile", file.isFile)
                        jsonFile.put("link", JSONObject.NULL)
                    }

                    jsonFile.put("lastModified", file.timestamp.timeInMillis)
                    jsonFile.put("canWrite", file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION))
                    jsonFile.put("canRead", file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION))
                    jsonFiles.put(jsonFile)
                }
                callback.success(jsonFiles)
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun exists(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            val ftpId = args.optString(0)
            var path = args.optString(1)
            try {
                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    path = "/"
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                val ftpFiles = ftp.listFiles(path)
                callback.success(if (ftpFiles.isNotEmpty()) 1 else 0)
            } catch (e: Exception) {
                Log.e(TAG, "FTPClient ($ftpId) path: $path", e)
                callback.error(e.message)
            }
        }
    }

    fun sendNoOp(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }
                ftp.sendNoOp()
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun deleteFile(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.deleteFile(path)
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun deleteDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                Log.d(TAG, "Deleting directory $path")
                emptyDirectory(path, ftp)
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun rename(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val oldPath = args.optString(1)
                val newPath = args.optString(2)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (oldPath.isNullOrEmpty()) {
                    callback.error("Old path is required.")
                    return@execute
                }

                if (newPath.isNullOrEmpty()) {
                    callback.error("New path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                val parentPath = getParentPath(oldPath)
                val ftpFiles = ftp.listFiles(parentPath) ?: emptyArray()

                Log.d(TAG, "Renaming $oldPath to $newPath")
                ftp.rename(oldPath, newPath)

                val newFile = ftp.listFiles(newPath) ?: emptyArray()
                if (newFile.isNotEmpty()) {
                    callback.success(newPath)
                } else {
                    val latestFtpFiles = ftp.listFiles(parentPath) ?: emptyArray()
                    val changedFile = latestFtpFiles.firstOrNull { file ->
                        ftpFiles.none { oldFile -> oldFile.name == file.name }
                    }

                    if (changedFile != null) {
                        val changedFilePath = joinPath(parentPath, changedFile.name)
                        ftp.rename(changedFilePath, oldPath)
                    }
                    callback.error("Failed to rename file")
                }
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun downloadFile(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)
                val localFilePath = args.optString(2)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                if (localFilePath.isNullOrEmpty()) {
                    callback.error("Local file is required.")
                    return@execute
                }

                val uri = URI(localFilePath)
                val localFile = File(uri)
                val ftp = ftpProfiles[ftpId]

                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.setFileType(FTP.BINARY_FILE_TYPE)

                if (localFile.exists()) {
                    localFile.delete()
                }

                ftp.retrieveFileStream(path)?.use { inputStream ->
                    FileOutputStream(localFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    Log.d(TAG, "FTPClient ($ftpId) path: $path - not found")
                    callback.error("File not found.")
                    return@execute
                }

                if (!ftp.completePendingCommand()) {
                    ftp.logout()
                    ftp.disconnect()
                    callback.error("File transfer failed.")
                    return@execute
                }

                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun uploadFile(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val localFilePath = args.optString(1)
                val remoteFilePath = args.optString(2)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (remoteFilePath.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                if (localFilePath.isNullOrEmpty()) {
                    callback.error("Local file is required.")
                    return@execute
                }

                Log.d("FTPUpload", "uploadFile: $localFilePath")
                val uri = URI(localFilePath)
                val localFile = File(uri)
                val ftp = ftpProfiles[ftpId]

                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                Log.d("FTPUpload", "Destination $remoteFilePath")

                FileInputStream(localFile).use { inputStream ->
                    ftp.storeFileStream(remoteFilePath)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    } ?: run {
                        callback.error("File not found.")
                        return@execute
                    }
                }

                if (!ftp.completePendingCommand()) {
                    ftp.logout()
                    ftp.disconnect()
                    callback.error("File transfer failed.")
                    return@execute
                }

                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun getKeepAlive(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                callback.success(ftp.controlKeepAliveTimeout.toInt())
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun execCommand(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val command = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (command.isNullOrEmpty()) {
                    callback.error("Command is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.sendCommand(command)
                val reply = ftp.replyString
                callback.success(reply)
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun isConnected(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                callback.success(if (ftp.isConnected) 1 else 0)
            } catch (e: Exception) {
                Log.e(TAG, "FTPClient", e)
                callback.error(e.message)
            }
        }
    }

    fun disconnect(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val ftp = ftpProfiles[ftpId]
                if (ftp != null) {
                    ftp.disconnect()
                    ftpProfiles.remove(ftpId)
                }
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun createDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.makeDirectory(path)
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun changeDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.changeWorkingDirectory(path)
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun changeToParentDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                ftp.changeToParentDirectory()
                callback.success()
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun getWorkingDirectory(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                val workingDirectory = ftp.printWorkingDirectory()
                callback.success(workingDirectory)
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    fun getStat(args: JSONArray, callback: CallbackContext) {
        cordova.threadPool.execute {
            try {
                val ftpId = args.optString(0)
                val path = args.optString(1)

                if (ftpId.isNullOrEmpty()) {
                    callback.error("FTP ID is required.")
                    return@execute
                }

                if (path.isNullOrEmpty()) {
                    callback.error("Path is required.")
                    return@execute
                }

                val ftp = ftpProfiles[ftpId]
                if (ftp == null) {
                    callback.error("FTP client not found.")
                    return@execute
                }

                val files = ftp.listFiles(path)
                if (files == null || files.isEmpty()) {
                    callback.error("File not found.")
                    return@execute
                }

                val file = files[0]
                val stat = JSONObject().apply {
                    put("isFile", file.isFile)
                    put("isValid", file.isValid)
                    put("isUnknown", file.isUnknown)
                    put("isDirectory", file.isDirectory)
                    put("isLink", file.isSymbolicLink)
                    put("linkCount", file.hardLinkCount)
                    put("length", file.size)
                    put("name", getBaseName(file.name))
                    put("lastModified", file.timestamp.timeInMillis)
                    put("link", file.link)
                    put("group", file.group)
                    put("user", file.user)
                    put("canWrite", file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION))
                    put("canRead", file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION))
                }

                callback.success(stat)
            } catch (e: Exception) {
                callback.error(e.message)
            }
        }
    }

    private fun getFtpId(host: String, port: Int, username: String): String {
        return "$username@$host:$port"
    }

    @Throws(IOException::class)
    private fun emptyDirectory(directory: String, client: FTPClient) {
        val files = client.listFiles(directory) ?: return
        for (file in files) {
            val filename = file.name
            if (filename == "." || filename == "..") continue
            if (file.isDirectory) {
                Log.d(TAG, "Removing directory: ${file.name}")
                emptyDirectory("$directory/${file.name}", client)
            } else {
                Log.d(TAG, "Removing file: ${file.name}")
                client.deleteFile("$directory/${file.name}")
            }
        }
        client.removeDirectory(directory)
    }

    private fun joinPath(p1: String, p2: String): String {
        val prefix = if (!p1.endsWith("/")) "$p1/" else p1
        return prefix + p2
    }

    companion object {
        private const val TAG = "FTP"

        private fun getBaseName(path: String): String {
            val lastSepIndex = path.lastIndexOf('/')
            if (lastSepIndex == path.length - 1) {
                return getBaseName(path.substring(0, lastSepIndex))
            }
            return path.substring(lastSepIndex + 1)
        }

        private fun getParentPath(path: String): String {
            var lastSepIndex = path.lastIndexOf('/')
            if (lastSepIndex == path.length - 1) {
                lastSepIndex = path.substring(0, lastSepIndex).lastIndexOf('/')
            }
            return path.substring(0, lastSepIndex)
        }
    }
}
