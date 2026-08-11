package com.foxdebug.server

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.apache.cordova.CallbackContext
import org.apache.cordova.PluginResult
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NanoHTTPDWebserver(
    port: Int,
    private val context: Context
) : NanoHTTPD(port) {

    val responses = ConcurrentHashMap<String, Any>()
    var onRequestCallbackContext: CallbackContext? = null

    private fun getBodyText(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        val method = session.method
        if (Method.PUT == method || Method.POST == method) {
            try {
                session.parseBody(files)
            } catch (ignored: IOException) {
                return "{}"
            } catch (ignored: ResponseException) {
                return "{}"
            }
        }
        return files["postData"] ?: ""
    }

    @Throws(JSONException::class)
    private fun createJSONRequest(requestId: String, session: IHTTPSession): JSONObject {
        return JSONObject().apply {
            put("requestId", requestId)
            put("body", getBodyText(session))
            put("headers", session.headers)
            put("method", session.method)
            put("path", session.uri)
            put("query", session.queryParameterString ?: "")
        }
    }

    @Throws(JSONException::class)
    private fun getContentType(responseObject: JSONObject): String {
        return if (responseObject.has("headers") && responseObject.getJSONObject("headers").has("Content-Type")) {
            responseObject.getJSONObject("headers").getString("Content-Type")
        } else {
            "text/plain"
        }
    }

    @Throws(FileNotFoundException::class, IOException::class)
    private fun newFixedFileResponse(file: DocumentFile, mime: String): Response {
        val res = newFixedLengthResponse(
            Response.Status.OK,
            mime,
            getInputStream(file),
            file.length()
        )
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    private fun serveFile(
        header: Map<String, String>,
        file: DocumentFile,
        mime: String
    ): Response {
        var res: Response
        try {
            val fileLen = file.length()
            val path = file.uri.toString()
            val etag = Integer.toHexString(
                ("$path${file.lastModified()}$fileLen").hashCode()
            )

            var startFrom = 0L
            var endAt = -1L
            var range = header["range"]
            if (range != null && range.startsWith("bytes=")) {
                range = range.substring("bytes=".length)
                val minus = range.indexOf('-')
                try {
                    if (minus > 0) {
                        startFrom = range.substring(0, minus).toLong()
                        endAt = range.substring(minus + 1).toLong()
                    }
                } catch (error: NumberFormatException) {
                    Log.w(TAG, "Invalid range header: $range", error)
                }
            }

            val ifRange = header["if-range"]
            val headerIfRangeMissingOrMatching = ifRange == null || etag == ifRange

            val ifNoneMatch = header["if-none-match"]
            val headerIfNoneMatchPresentAndMatching = ifNoneMatch != null &&
                ("*" == ifNoneMatch || ifNoneMatch == etag)

            if (headerIfRangeMissingOrMatching && range != null && startFrom in 0 until fileLen) {
                if (headerIfNoneMatchPresentAndMatching) {
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "")
                    res.addHeader("ETag", etag)
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }
                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) {
                        newLen = 0
                    }

                    val inputStream = getInputStream(file)
                    inputStream.skip(startFrom)

                    res = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        mime,
                        inputStream,
                        newLen
                    )
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Length", "$newLen")

                    val contentRange = "bytes $startFrom-$endAt/$fileLen"
                    res.addHeader("Content-Range", contentRange)
                    res.addHeader("ETag", etag)
                }
            } else {
                if (headerIfRangeMissingOrMatching && range != null && startFrom >= fileLen) {
                    res = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        ""
                    )
                    res.addHeader("Content-Range", "bytes */$fileLen")
                    res.addHeader("ETag", etag)
                } else if (range == null && headerIfNoneMatchPresentAndMatching) {
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "")
                    res.addHeader("ETag", etag)
                } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "")
                    res.addHeader("ETag", etag)
                } else {
                    res = newFixedFileResponse(file, mime)
                    res.addHeader("Content-Length", "$fileLen")
                    res.addHeader("ETag", etag)
                }
            }
        } catch (e: Exception) {
            Log.d("ServeFileError", e.message ?: "")
            res = if (e is FileNotFoundException) {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    e.message
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    e.message
                )
            }
        }
        return res
    }

    override fun serve(session: IHTTPSession): Response {
        val requestUUID = UUID.randomUUID().toString()

        try {
            val pluginResult = PluginResult(
                PluginResult.Status.OK,
                createJSONRequest(requestUUID, session)
            ).apply { keepCallback = true }

            onRequestCallbackContext?.sendPluginResult(pluginResult)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        while (!responses.containsKey(requestUUID)) {
            try {
                Thread.sleep(1)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        val responseObject = responses[requestUUID] as? JSONObject
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Invalid response"
            )

        return if (responseObject.has("path")) {
            try {
                val path = responseObject.getString("path")
                val file = getFile(path)
                val mimeType = URLConnection.guessContentTypeFromName(path)
                val res = serveFile(session.headers, file, mimeType)
                val headers = getJSONObject(responseObject, "headers")
                headers?.keys()?.forEach { key ->
                    res.addHeader(key, headers.getString(key))
                }
                res
            } catch (e: JSONException) {
                e.printStackTrace()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
            }
        } else {
            try {
                val response = newFixedLengthResponse(
                    Response.Status.lookup(responseObject.getInt("status")),
                    getContentType(responseObject),
                    responseObject.optString("body", "")
                )

                val headers = getJSONObject(responseObject, "headers")
                headers?.keys()?.forEach { key ->
                    response.addHeader(key, headers.getString(key))
                }
                response
            } catch (e: JSONException) {
                e.printStackTrace()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
            }
        }
    }

    private fun getFile(filePath: String): DocumentFile {
        val fileUri = Uri.parse(filePath)
        return if (filePath.matches(Regex("file:///(.*)"))) {
            val file = File(fileUri.path ?: "")
            DocumentFile.fromFile(file)
        } else {
            DocumentFile.fromSingleUri(context, fileUri)!!
        }
    }

    @Throws(FileNotFoundException::class, IOException::class)
    private fun getInputStream(file: DocumentFile): InputStream {
        val uri = file.uri
        val contentResolver = context.contentResolver
        return contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open stream for URI: $uri")
    }

    private fun getJSONObject(ob: JSONObject, key: String): JSONObject? {
        return try {
            ob.getJSONObject(key)
        } catch (e: JSONException) {
            Log.w(TAG, "Missing or invalid JSON object for key: $key", e)
            null
        }
    }

    companion object {
        private const val TAG = "NanoHTTPDWebserver"
    }
}
