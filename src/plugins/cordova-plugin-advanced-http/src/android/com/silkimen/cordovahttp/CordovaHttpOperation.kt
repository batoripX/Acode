package com.silkimen.cordovahttp

import com.silkimen.http.TLSConfiguration
import org.json.JSONObject

class CordovaHttpOperation : CordovaHttpBase {

    constructor(
        method: String,
        url: String,
        serializer: String,
        data: Any?,
        headers: JSONObject,
        connectTimeout: Int,
        readTimeout: Int,
        followRedirects: Boolean,
        responseType: String,
        tlsConfiguration: TLSConfiguration,
        callbackContext: CordovaObservableCallbackContext
    ) : super(
        method,
        url,
        serializer,
        data,
        headers,
        connectTimeout,
        readTimeout,
        followRedirects,
        responseType,
        tlsConfiguration,
        callbackContext
    )

    constructor(
        method: String,
        url: String,
        headers: JSONObject,
        connectTimeout: Int,
        readTimeout: Int,
        followRedirects: Boolean,
        responseType: String,
        tlsConfiguration: TLSConfiguration,
        callbackContext: CordovaObservableCallbackContext
    ) : super(
        method,
        url,
        headers,
        connectTimeout,
        readTimeout,
        followRedirects,
        responseType,
        tlsConfiguration,
        callbackContext
    )
}
