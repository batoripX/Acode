package com.foxdebug.iap

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference

class Iap : CordovaPlugin() {

    private var billingClient: BillingClient? = null
    private var contextRef: WeakReference<Context>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var purchaseUpdated: CallbackContext? = null

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        contextRef = WeakReference(cordova.context)
        activityRef = WeakReference(cordova.activity)
        billingClient = getBillingClient()
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        val arg1 = getString(args, 0)
        when (action) {
            "startConnection",
            "getProducts",
            "setPurchaseUpdatedListener",
            "purchase",
            "consume",
            "getPurchases",
            "acknowledgePurchase" -> {
                cordova.threadPool.execute {
                    when (action) {
                        "startConnection" -> startConnection(callbackContext)
                        "getProducts" -> getProducts(getStringList(args, 0), callbackContext)
                        "setPurchaseUpdatedListener" -> setPurchaseUpdatedListener(callbackContext)
                        "purchase" -> purchase(arg1, callbackContext)
                        "consume" -> consume(arg1, callbackContext)
                        "getPurchases" -> getPurchases(callbackContext)
                        "acknowledgePurchase" -> acknowledgePurchase(arg1, callbackContext)
                    }
                }
                return true
            }
            else -> return false
        }
    }

    private fun getBillingClient(): BillingClient? {
        val ctx = contextRef?.get() ?: return null
        return BillingClient.newBuilder(ctx)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .setListener { billingResult, purchases ->
                try {
                    val responseCode = billingResult.responseCode
                    if (responseCode == BillingResponseCode.OK) {
                        val result = JSONArray()
                        purchases?.forEach { purchase ->
                            result.put(purchaseToJson(purchase))
                        }
                        sendPurchasePluginResult(PluginResult(PluginResult.Status.OK, result))
                    } else {
                        sendPurchasePluginResult(PluginResult(PluginResult.Status.ERROR, responseCode))
                    }
                } catch (e: JSONException) {
                    sendPurchasePluginResult(PluginResult(PluginResult.Status.ERROR, e.message))
                }
            }
            .build()
    }

    private fun setPurchaseUpdatedListener(callbackContext: CallbackContext) {
        purchaseUpdated = callbackContext
    }

    private fun consume(token: String?, callbackContext: CallbackContext) {
        if (token.isNullOrEmpty()) {
            callbackContext.error("Purchase token cannot be null or empty")
            return
        }
        val client = checkClientConnected(callbackContext) ?: return

        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(token)
            .build()

        client.consumeAsync(consumeParams) { billingResult, _ ->
            val responseCode = billingResult.responseCode
            if (responseCode == BillingResponseCode.OK) {
                callbackContext.success(responseCode)
            } else {
                callbackContext.error(responseCode)
            }
        }
    }

    private fun startConnection(callbackContext: CallbackContext) {
        try {
            var client = billingClient
            if (client == null) {
                client = getBillingClient()
                billingClient = client
            }
            if (client == null) {
                callbackContext.error("Failed to initialize BillingClient")
                return
            }

            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    val responseCode = billingResult.responseCode
                    if (responseCode == BillingResponseCode.OK) {
                        callbackContext.success(responseCode)
                    } else {
                        callbackContext.error(responseCode)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    callbackContext.error("Billing service disconnected")
                }
            })
        } catch (e: SecurityException) {
            callbackContext.error(e.message)
        }
    }

    private fun getProducts(idList: List<String>?, callbackContext: CallbackContext) {
        val client = checkClientConnected(callbackContext) ?: return
        if (idList.isNullOrEmpty()) {
            callbackContext.error("Product ID list is empty or null")
            return
        }

        val productList = idList.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            try {
                val responseCode = billingResult.responseCode
                if (responseCode == BillingResponseCode.OK) {
                    val productDetailsList = queryProductDetailsResult.productDetailsList ?: emptyList()
                    val products = JSONArray()
                    
                    for (productDetails in productDetailsList) {
                        val product = JSONObject()
                        val offerDetails = productDetails.oneTimePurchaseOfferDetails
                        if (offerDetails != null) {
                            product.put("productId", productDetails.productId)
                            product.put("title", productDetails.title)
                            product.put("description", productDetails.description)
                            product.put("price", offerDetails.formattedPrice)
                            product.put("priceAmountMicros", offerDetails.priceAmountMicros)
                            product.put("priceCurrencyCode", offerDetails.priceCurrencyCode)
                            product.put("type", productDetails.productType)
                        }
                        products.put(product)
                    }
                    callbackContext.success(products)
                } else {
                    callbackContext.error(responseCode)
                }
            } catch (e: JSONException) {
                callbackContext.error(e.message)
            }
        }
    }

    private fun purchase(productIdOrJson: String?, callbackContext: CallbackContext) {
        try {
            if (productIdOrJson.isNullOrBlank()) {
                callbackContext.error("Product ID cannot be null or empty")
                return
            }

            val client = checkClientConnected(callbackContext) ?: return
            val activity = activityRef?.get()
            if (activity == null) {
                callbackContext.error("Activity reference is lost")
                return
            }

            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productIdOrJson)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    val productDetailsList = queryProductDetailsResult.productDetailsList ?: emptyList()
                    if (productDetailsList.isNotEmpty()) {
                        val productDetails = productDetailsList[0]
                        val flowParams = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(
                                listOf(
                                    BillingFlowParams.ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails)
                                        .build()
                                )
                            )
                            .build()

                        val result = client.launchBillingFlow(activity, flowParams)
                        val responseCode = result.responseCode
                        if (responseCode == BillingResponseCode.OK) {
                            callbackContext.success()
                        } else {
                            callbackContext.error(responseCode)
                        }
                    } else {
                        callbackContext.error("No product details found for: $productIdOrJson")
                    }
                } else {
                    callbackContext.error(billingResult.responseCode)
                }
            }
        } catch (e: Exception) {
            callbackContext.error("Purchase error: ${e.message}")
        }
    }

    private fun getPurchases(callbackContext: CallbackContext) {
        val client = checkClientConnected(callbackContext) ?: return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { billingResult, purchasesList ->
            try {
                val responseCode = billingResult.responseCode
                if (responseCode == BillingResponseCode.OK) {
                    val purchases = JSONArray()
                    purchasesList.forEach { purchase ->
                        purchases.put(purchaseToJson(purchase))
                    }
                    callbackContext.success(purchases)
                } else {
                    callbackContext.error(responseCode)
                }
            } catch (e: JSONException) {
                callbackContext.error(e.message)
            }
        }
    }

    private fun acknowledgePurchase(purchaseToken: String?, callbackContext: CallbackContext) {
        if (purchaseToken.isNullOrEmpty()) {
            callbackContext.error("Purchase token cannot be null or empty")
            return
        }
        val client = checkClientConnected(callbackContext) ?: return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        client.acknowledgePurchase(params) { billingResult ->
            val responseCode = billingResult.responseCode
            if (responseCode == BillingResponseCode.OK) {
                callbackContext.success()
            } else {
                callbackContext.error(responseCode)
            }
        }
    }

    private fun checkClientConnected(callbackContext: CallbackContext): BillingClient? {
        var client = billingClient
        if (client == null) {
            client = getBillingClient()
            billingClient = client
        }
        if (client == null) {
            callbackContext.error("Billing client is not connected")
            return null
        }
        return client
    }

    @Throws(JSONException::class)
    private fun purchaseToJson(purchase: Purchase): JSONObject {
        return JSONObject().apply {
            val skuArray = JSONArray()
            purchase.skus.forEach { sku -> skuArray.put(sku) }
            put("productIds", skuArray)
            put("orderId", purchase.orderId)
            put("signature", purchase.signature)
            put("purchaseTime", purchase.purchaseTime)
            put("purchaseToken", purchase.purchaseToken)
            put("purchaseState", purchase.purchaseState)
            put("isAcknowledged", purchase.isAcknowledged)
            put("developerPayload", purchase.developerPayload)
        }
    }

    private fun sendPurchasePluginResult(result: PluginResult) {
        purchaseUpdated?.let { callback ->
            result.setKeepCallback(true)
            callback.sendPluginResult(result)
        }
    }

    private fun getString(args: JSONArray, index: Int): String? {
        return try {
            args.getString(index)
        } catch (_: JSONException) {
            null
        }
    }

    private fun getStringList(args: JSONArray, index: Int): List<String>? {
        return try {
            val array = args.getJSONArray(index)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (_: JSONException) {
            null
        }
    }
}
