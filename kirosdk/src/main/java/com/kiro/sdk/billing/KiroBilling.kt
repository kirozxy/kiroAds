package com.kiro.sdk.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.resume
import com.kiro.sdk.KiroSdk

class KiroBilling(context: Context, val config: Config) : PurchasesUpdatedListener {

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _purchaseEvents = MutableSharedFlow<PurchaseResult>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    
    /**
     * Flow that emits purchase results (Success, Cancelled, Error).
     */
    val purchaseEvents: SharedFlow<PurchaseResult> = _purchaseEvents.asSharedFlow()

    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    init {
        if (config.enableBilling) {
            connectToBilling()
        }
    }

    private fun connectToBilling() {
        if (!config.enableBilling) return
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    reconnectAttempts = 0 // Reset reconnect counter on successful connection
                    Log.d("KiroBilling", "Billing Service connected successfully.")
                    // Automatically restore purchases to verify "Remove Ads" status
                    restorePurchases()
                } else {
                    Log.e("KiroBilling", "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w("KiroBilling", "Billing Service disconnected. Attempting to reconnect...")
                retryConnectionWithBackoff()
            }
        })
    }

    private fun retryConnectionWithBackoff() {
        if (!config.enableBilling) return
        if (reconnectAttempts < maxReconnectAttempts) {
            val delayMs = java.lang.Math.pow(2.0, reconnectAttempts.toDouble()).toLong() * 1000L
            reconnectAttempts++
            scope.launch {
                delay(delayMs)
                Log.d("KiroBilling", "Retrying billing service connection (Attempt $reconnectAttempts)...")
                connectToBilling()
            }
        } else {
            Log.e("KiroBilling", "Max billing service reconnection attempts reached.")
        }
    }

    /**
     * Queries Google Play for all active purchases and subscriptions.
     * Automatically restores the Ads-Disabled state if any active purchase/subscription
     * matches the configured removeAdsProductIds.
     */
    fun restorePurchases() {
        if (!isConnected) return

        val removeAdsIds = config.removeAdsProductIds
        if (removeAdsIds.isEmpty()) return

        // 1. Query active in-app purchases
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(inAppParams, PurchasesResponseListener { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasRemoveAds = purchasesList.any { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            purchase.products.any { productId -> removeAdsIds.contains(productId) }
                }
                if (hasRemoveAds) {
                    Log.d("KiroBilling", "Active 'Remove Ads' In-App product found. Disabling ads.")
                    KiroSdk.setAdsDisabled(true)
                }
            }
        })

        // 2. Query active subscriptions
        val subsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(subsParams, PurchasesResponseListener { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasRemoveAds = purchasesList.any { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            purchase.products.any { productId -> removeAdsIds.contains(productId) }
                }
                if (hasRemoveAds) {
                    Log.d("KiroBilling", "Active 'Remove Ads' Subscription found. Disabling ads.")
                    KiroSdk.setAdsDisabled(true)
                }
            }
        })
    }

    /**
     * Queries product details (Subscriptions or In-app Products).
     */
    suspend fun queryProductDetails(
        productIds: List<String>,
        productType: String = BillingClient.ProductType.INAPP
    ): List<ProductDetails>? = suspendCancellableCoroutine { continuation ->
        if (!isConnected) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                continuation.resume(productDetailsResult.productDetailsList)
            } else {
                Log.e("KiroBilling", "Failed to query products: ${billingResult.debugMessage}")
                continuation.resume(null)
            }
        }
    }

    /**
     * Launches the Google Play billing purchase flow.
     */
    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails): BillingResult {
        if (!isConnected) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                .setDebugMessage("Billing client is not connected.")
                .build()
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        return billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        scope.launch {
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    handlePurchase(purchase)
                }
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                _purchaseEvents.emit(PurchaseResult.Cancelled)
            } else {
                _purchaseEvents.emit(PurchaseResult.Error(billingResult.responseCode, billingResult.debugMessage))
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Must acknowledge the purchase within 3 days, otherwise Google will refund the transaction.
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                val result = acknowledgePurchaseAsync(acknowledgePurchaseParams)
                
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("KiroBilling", "Purchase acknowledged successfully: ${purchase.orderId}")
                    _purchaseEvents.emit(PurchaseResult.Success(purchase))
                } else {
                    Log.e("KiroBilling", "Failed to acknowledge purchase: ${result.debugMessage}")
                    _purchaseEvents.emit(PurchaseResult.Error(result.responseCode, result.debugMessage))
                }
            } else {
                _purchaseEvents.emit(PurchaseResult.Success(purchase))
            }
        }
    }

    private suspend fun acknowledgePurchaseAsync(params: AcknowledgePurchaseParams): BillingResult =
        suspendCancellableCoroutine { continuation ->
            billingClient.acknowledgePurchase(params) { billingResult ->
                continuation.resume(billingResult)
            }
        }

    /**
     * Queries Google Play for all currently active purchases and subscriptions.
     * Returns a list of active Purchase objects, or null if the query fails or client is disconnected.
     */
    suspend fun queryActivePurchases(): List<Purchase>? {
        if (!isConnected) return null

        val activePurchases = mutableListOf<Purchase>()

        // 1. Query INAPP purchases
        val inAppResult = suspendCancellableCoroutine<List<Purchase>?> { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            billingClient.queryPurchasesAsync(params, PurchasesResponseListener { billingResult, purchasesList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    continuation.resume(purchasesList)
                } else {
                    Log.e("KiroBilling", "Failed to query active INAPP purchases: ${billingResult.debugMessage}")
                    continuation.resume(null)
                }
            })
        }

        if (inAppResult != null) {
            activePurchases.addAll(inAppResult.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED })
        }

        // 2. Query SUBS purchases
        val subsResult = suspendCancellableCoroutine<List<Purchase>?> { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billingClient.queryPurchasesAsync(params, PurchasesResponseListener { billingResult, purchasesList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    continuation.resume(purchasesList)
                } else {
                    Log.e("KiroBilling", "Failed to query active SUBS purchases: ${billingResult.debugMessage}")
                    continuation.resume(null)
                }
            })
        }

        if (subsResult != null) {
            activePurchases.addAll(subsResult.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED })
        }

        return activePurchases
    }

    /**
     * Releases billing resources.
     */
    fun release() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
        scope.cancel()
    }

    sealed interface PurchaseResult {
        data class Success(val purchase: Purchase) : PurchaseResult
        object Cancelled : PurchaseResult
        data class Error(val code: Int, val message: String) : PurchaseResult
    }

    data class Config(
        val enableBilling: Boolean = true,
        val removeAdsProductIds: List<String> = emptyList()
    )
}
