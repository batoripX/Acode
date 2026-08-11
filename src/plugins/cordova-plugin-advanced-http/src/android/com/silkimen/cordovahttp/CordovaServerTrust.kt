package com.silkimen.cordovahttp

import android.app.Activity
import android.content.res.AssetManager
import android.util.Log
import com.silkimen.http.TLSConfiguration
import org.apache.cordova.CallbackContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CordovaServerTrust(
    private val mode: String,
    private val activity: Activity,
    private val tlsConfiguration: TLSConfiguration,
    private val callbackContext: CallbackContext
) : Runnable {

    private val noOpTrustManagers: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        }
    )

    private val noOpVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    override fun run() {
        try {
            when (mode) {
                "legacy" -> {
                    tlsConfiguration.hostnameVerifier = null
                    tlsConfiguration.trustManagers = null
                }
                "nocheck" -> {
                    tlsConfiguration.hostnameVerifier = noOpVerifier
                    tlsConfiguration.trustManagers = noOpTrustManagers
                }
                "pinned" -> {
                    tlsConfiguration.hostnameVerifier = null
                    tlsConfiguration.trustManagers = getTrustManagers(getCertsFromBundle("www/certificates"))
                }
                else -> {
                    tlsConfiguration.hostnameVerifier = null
                    tlsConfiguration.trustManagers = getTrustManagers(getCertsFromKeyStore("AndroidCAStore"))
                }
            }

            callbackContext.success()
        } catch (e: Exception) {
            Log.e(TAG, "An error occurred while configuring SSL cert mode", e)
            callbackContext.error("An error occurred while configuring SSL cert mode")
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun getTrustManagers(store: KeyStore): Array<TrustManager> {
        val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
        val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
        tmf.init(store)
        return tmf.trustManagers
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun getCertsFromBundle(path: String): KeyStore {
        val assetManager: AssetManager = activity.assets
        val files = assetManager.list(path) ?: emptyArray()

        val cf = CertificateFactory.getInstance("X.509")
        val keyStoreType = KeyStore.getDefaultType()
        val keyStore = KeyStore.getInstance(keyStoreType)

        keyStore.load(null, null)

        files.forEachIndexed { index, fileName ->
            if (fileName.endsWith(".cer", ignoreCase = true)) {
                assetManager.open("$path/$fileName").use { inputStream ->
                    val certificate = cf.generateCertificate(inputStream)
                    keyStore.setCertificateEntry("CA$index", certificate)
                }
            }
        }

        return keyStore
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun getCertsFromKeyStore(storeType: String): KeyStore {
        val store = KeyStore.getInstance(storeType)
        store.load(null)
        return store
    }

    companion object {
        private const val TAG = "Cordova-Plugin-HTTP"
    }
}
