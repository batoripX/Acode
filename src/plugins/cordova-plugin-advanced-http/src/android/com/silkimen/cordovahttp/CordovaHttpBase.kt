package com.silkimen.cordovahttp

import android.util.Base64
import android.util.Log
import com.silkimen.http.HttpBodyDecoder
import com.silkimen.http.HttpRequest
import com.silkimen.http.HttpRequest.HttpRequestException
import com.silkimen.http.JsonUtils
import com.silkimen.http.TLSConfiguration
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

abstract class CordovaHttpBase protected constructor(
    protected var method: String,
    protected var url: String,
    protected var serializer: String = "none",
    protected var data: Any? = null,
    protected var headers: JSONObject? = null,
    protected var connectTimeout: Int = 0,
    protected var readTimeout: Int = 0,
    protected var followRedirects: Boolean = false,
    protected var responseType: String? = null,
    protected var tlsConfiguration: TLSConfiguration,
    protected var callbackContext: CordovaObservableCallbackContext
) : Runnable {

    // Secondary constructor matching legacy Java constructor without 'data' and 'serializer'
    constructor(
        method: String,
        url: String,
        headers: JSONObject?,
        connectTimeout: Int,
        readTimeout: Int,
        followRedirects: Boolean,
        responseType: String?,
        tlsConfiguration: TLSConfiguration,
        callbackContext: CordovaObservableCallbackContext
    ) : this(
        method = method,
        url = url,
        serializer = "none",
        data = null,
        headers = headers,
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
        followRedirects = followRedirects,
        responseType = responseType,
        tlsConfiguration = tlsConfiguration,
        callbackContext = callbackContext
    )

    override fun run() {
        val response = CordovaHttpResponse()
        var request: HttpRequest? = null

        try {
            request = createRequest().also { req ->
                prepareRequest(req)
                sendBody(req)
                processResponse(req, response)
                req.disconnect()
            }
        } catch (e: HttpRequestException) {
            when (val cause = e.cause) {
                is SSLException -> {
                    response.setStatus(-2)
                    response.setErrorMessage("TLS connection could not be established: ${e.message}")
                    Log.w(TAG, "TLS connection could not be established", e)
                }
                is UnknownHostException -> {
                    response.setStatus(-3)
                    response.setErrorMessage("Host could not be resolved: ${e.message}")
                    Log.w(TAG, "Host could not be resolved", e)
                }
                is SocketTimeoutException -> {
                    response.setStatus(-4)
                    response.setErrorMessage("Request timed out: ${e.message}")
                    Log.w(TAG, "Request timed out", e)
                }
                is InterruptedIOException -> {
                    val message = cause.message
                    if ("thread interrupted".equals(message?.lowercase(), ignoreCase = true)) {
                        setAborted(request, response)
                    } else {
                        handleGenericError(e, cause.message, response)
                    }
                }
                else -> handleGenericError(e, cause?.message, response)
            }
        } catch (ie: InterruptedException) {
            setAborted(request, response)
        } catch (e: Exception) {
            response.setStatus(-1)
            response.setErrorMessage(e.message)
            Log.e(TAG, "An unexpected error occurred", e)
        }

        try {
            if (response.hasFailed()) {
                callbackContext.error(response.toJSON())
            } else {
                callbackContext.success(response.toJSON())
            }
        } catch (e: JSONException) {
            Log.e(TAG, "An unexpected error occurred while creating HTTP response object", e)
        }
    }

    private fun handleGenericError(e: Exception, message: String?, response: CordovaHttpResponse) {
        response.setStatus(-1)
        response.setErrorMessage("There was an error with the request: $message")
        Log.w(TAG, "Generic request error", e)
    }

    @Throws(JSONException::class)
    protected open fun createRequest(): HttpRequest {
        return HttpRequest(url, method)
    }

    @Throws(JSONException::class, IOException::class)
    protected open fun prepareRequest(request: HttpRequest) {
        request.followRedirects(followRedirects)
        request.connectTimeout(connectTimeout)
        request.readTimeout(readTimeout)
        request.acceptCharset("UTF-8")
        request.uncompress(true)

        tlsConfiguration.hostnameVerifier?.let { verifier ->
            request.setHostnameVerifier(verifier)
        }

        request.setSSLSocketFactory(tlsConfiguration.tlsSocketFactory)

        setContentType(request)
        request.headers(JsonUtils.getStringMap(headers))
    }

    protected open fun setContentType(request: HttpRequest) {
        when (serializer) {
            "json" -> request.contentType("application/json", "UTF-8")
            "utf8" -> request.contentType("text/plain", "UTF-8")
            "raw" -> request.contentType("application/octet-stream")
            "urlencoded", "multipart" -> {
                // Content-type set directly during payload construction
            }
        }
    }

    @Throws(Exception::class)
    protected open fun sendBody(request: HttpRequest) {
        val payload = data ?: return

        when (serializer) {
            "json" -> request.send(payload.toString())
            "utf8" -> request.send((payload as JSONObject).getString("text"))
            "raw" -> request.send(Base64.decode(payload as String, Base64.DEFAULT))
            "urlencoded" -> request.form(JsonUtils.getObjectMap(payload as JSONObject))
            "multipart" -> {
                val jsonPayload = payload as JSONObject
                val buffers: JSONArray = jsonPayload.getJSONArray("buffers")
                val names: JSONArray = jsonPayload.getJSONArray("names")
                val fileNames: JSONArray = jsonPayload.getJSONArray("fileNames")
                val types: JSONArray = jsonPayload.getJSONArray("types")

                for (i in 0 until buffers.length()) {
                    val bytes = Base64.decode(buffers.getString(i), Base64.DEFAULT)
                    val name = names.getString(i)

                    if (fileNames.isNull(i)) {
                        request.part(name, String(bytes, Charsets.UTF_8))
                    } else {
                        request.part(
                            name,
                            fileNames.getString(i),
                            types.getString(i),
                            ByteArrayInputStream(bytes)
                        )
                    }
                }

                if (buffers.length() == 0) {
                    request.contentType("multipart/form-data; boundary=00content0boundary00")
                    request.send("\r\n--00content0boundary00--\r\n")
                }
            }
        }
    }

    @Throws(Exception::class)
    protected open fun processResponse(request: HttpRequest, response: CordovaHttpResponse) {
        val outputStream = ByteArrayOutputStream()
        request.receive(outputStream)

        response.setStatus(request.code())
        response.setUrl(request.url().toString())
        response.setHeaders(request.headers())

        val bytes = outputStream.toByteArray()
        if (request.code() in 200..299) {
            if ("text" == responseType || "json" == responseType) {
                val decoded = HttpBodyDecoder.decodeBody(bytes, request.charset())
                response.setBody(decoded)
            } else {
                response.setData(bytes)
            }
        } else {
            response.setErrorMessage(HttpBodyDecoder.decodeBody(bytes, request.charset()))
        }
    }

    protected open fun setAborted(request: HttpRequest?, response: CordovaHttpResponse) {
        response.setStatus(-8)
        response.setErrorMessage("Request was aborted")

        request?.run {
            try {
                disconnect()
            } catch (any: Exception) {
                Log.w(TAG, "Failed to close aborted request", any)
            }
        }

        Log.i(TAG, "Request was aborted")
    }

    companion object {
        protected const val TAG = "Cordova-Plugin-HTTP"
    }
}
