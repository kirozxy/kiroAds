package com.kiro.sdk.tracking

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.LogLevel
import com.appsflyer.AppsFlyerLib
import com.facebook.appevents.AppEventsLogger
import com.google.firebase.analytics.FirebaseAnalytics

import java.lang.ref.WeakReference

enum class TrackerPlatform {
    FIREBASE,
    APPSFLYER,
    FACEBOOK,
    ADJUST
}

class KiroTracker(context: Context, private val config: Config) {
    private val contextRef = WeakReference(context.applicationContext)

    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var facebookLogger: AppEventsLogger? = null

    init {
        // Initialize Firebase Analytics
        if (config.enableFirebase) {
            try {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            } catch (e: Exception) {
                Log.w("KiroTracker", "Firebase Analytics is not initialized. Ensure google-services.json is present. Error: ${e.message}")
            }
        }

        // Initialize AppsFlyer
        if (config.appsFlyerDevKey != null) {
            try {
                val appsFlyer = AppsFlyerLib.getInstance()
                appsFlyer.init(config.appsFlyerDevKey, null, context)
                appsFlyer.start(context)
                Log.d("KiroTracker", "AppsFlyer initialized successfully.")
            } catch (e: Exception) {
                Log.e("KiroTracker", "Failed to initialize AppsFlyer: ${e.message}")
            }
        }

        // Initialize Facebook SDK Analytics
        if (config.enableFacebook) {
            try {
                facebookLogger = AppEventsLogger.newLogger(context)
                val application = context.applicationContext as? android.app.Application
                if (application != null) {
                    AppEventsLogger.activateApp(application)
                }
                Log.d("KiroTracker", "Facebook AppEvents Logger initialized successfully and App Activation logged.")
            } catch (e: Exception) {
                Log.e("KiroTracker", "Failed to initialize Facebook AppEvents Logger: ${e.message}")
            }
        }

        // Initialize Adjust SDK
        if (config.adjustAppToken != null) {
            try {
                val environment = if (config.adjustSandbox) {
                    AdjustConfig.ENVIRONMENT_SANDBOX
                } else {
                    AdjustConfig.ENVIRONMENT_PRODUCTION
                }
                val adjustConfig = AdjustConfig(context.applicationContext, config.adjustAppToken, environment)
                if (config.adjustSandbox) adjustConfig.setLogLevel(LogLevel.VERBOSE)
                Adjust.initSdk(adjustConfig)
                Log.d("KiroTracker", "Adjust initialized successfully (env=$environment).")
            } catch (e: Exception) {
                Log.e("KiroTracker", "Failed to initialize Adjust: ${e.message}")
            }
        }
    }

    /**
     * Logs a custom event to specified analytics providers.
     * By default, it logs to all active providers configured.
     *
     * @param eventName Name of the event to log.
     * @param params Optional bundle of parameters.
     * @param platforms Set of platforms to log the event to.
     */
    fun logEvent(
        eventName: String,
        params: Bundle? = null,
        platforms: Set<TrackerPlatform> = setOf(TrackerPlatform.FIREBASE, TrackerPlatform.APPSFLYER, TrackerPlatform.FACEBOOK, TrackerPlatform.ADJUST)
    ) {
        Log.d("KiroTracker", "Logging event: $eventName to $platforms, Params: ${params?.toString() ?: "empty"}")

        // 1. Log to Firebase
        if (platforms.contains(TrackerPlatform.FIREBASE)) {
            try {
                firebaseAnalytics?.logEvent(eventName, params)
            } catch (e: Exception) {
                Log.e("KiroTracker", "Firebase logEvent failed: ${e.message}")
            }
        }

        // 2. Log to AppsFlyer (Convert Bundle to Map)
        if (platforms.contains(TrackerPlatform.APPSFLYER) && config.appsFlyerDevKey != null) {
            try {
                var finalEventName = eventName
                val paramsMap = bundleToMap(params)?.toMutableMap() ?: mutableMapOf()

                // Translate Firebase standard "ad_impression" to AppsFlyer "af_ad_impression"
                if (eventName == "ad_impression") {
                    finalEventName = "af_ad_impression"
                    
                    // Map parameters to AppsFlyer standard names
                    val adUnit = paramsMap["ad_unit_name"] ?: paramsMap["adunitid"]
                    if (adUnit != null) paramsMap["af_ad_revenue_ad_unit"] = adUnit
                    
                    val adFormat = paramsMap["ad_format"] ?: paramsMap["adformat"]
                    if (adFormat != null) paramsMap["af_ad_revenue_ad_type"] = adFormat
                    
                    val adSource = paramsMap["ad_source"] ?: paramsMap["ad_platform"] ?: "AdMob"
                    paramsMap["af_ad_revenue_network_name"] = adSource
                }

                // Map standard revenue parameter names for AppsFlyer dashboard integration
                if (paramsMap.containsKey("value")) {
                    val valObj = paramsMap["value"]
                    if (valObj is Number) {
                        paramsMap["af_revenue"] = valObj.toDouble()
                    } else {
                        paramsMap["af_revenue"] = valObj.toString().toDoubleOrNull() ?: 0.0
                    }
                } else if (paramsMap.containsKey("revenue")) {
                    val revObj = paramsMap["revenue"]
                    if (revObj is Number) {
                        paramsMap["af_revenue"] = revObj.toDouble()
                    } else {
                        paramsMap["af_revenue"] = revObj.toString().toDoubleOrNull() ?: 0.0
                    }
                }
                if (paramsMap.containsKey("currency")) {
                    paramsMap["af_currency"] = paramsMap["currency"] ?: "USD"
                }

                val ctx = contextRef.get()
                if (ctx != null) {
                    AppsFlyerLib.getInstance().logEvent(ctx, finalEventName, paramsMap)
                } else {
                    Log.e("KiroTracker", "AppsFlyer logEvent failed: Context is null.")
                }
            } catch (e: Exception) {
                Log.e("KiroTracker", "AppsFlyer logEvent failed: ${e.message}")
            }
        }

        // 3. Log to Facebook AppEvents
        if (platforms.contains(TrackerPlatform.FACEBOOK) && config.enableFacebook) {
            try {
                var valueToSum: Double? = null
                var fbParams: Bundle? = params
                
                if (params != null) {
                    @Suppress("DEPRECATION")
                    val rawVal = params.get("value") ?: params.get("revenue")
                    if (rawVal is Number) {
                        valueToSum = rawVal.toDouble()
                    } else if (rawVal is String) {
                        valueToSum = rawVal.toDoubleOrNull()
                    }

                    // Map "currency" to Facebook's expected "fb_currency"
                    if (params.containsKey("currency")) {
                        fbParams = Bundle(params).apply {
                            putString("fb_currency", params.getString("currency"))
                        }
                    }
                }

                // Translate standard "purchase" or "subscribe" events to Facebook's logPurchase
                if ((eventName == "purchase" || eventName == "subscribe") && valueToSum != null) {
                    val currencyCode = params?.getString("currency") ?: "USD"
                    try {
                        val bigDecimalVal = java.math.BigDecimal.valueOf(valueToSum)
                        val currency = java.util.Currency.getInstance(currencyCode)
                        facebookLogger?.logPurchase(bigDecimalVal, currency, fbParams)
                    } catch (e: Exception) {
                        Log.e("KiroTracker", "Facebook logPurchase failed: ${e.message}")
                        // Fallback to standard logEvent on failure
                        facebookLogger?.logEvent(eventName, valueToSum, fbParams)
                    }
                } else {
                    if (valueToSum != null) {
                        facebookLogger?.logEvent(eventName, valueToSum, fbParams)
                    } else {
                        facebookLogger?.logEvent(eventName, fbParams)
                    }
                }
            } catch (e: Exception) {
                Log.e("KiroTracker", "Facebook logEvent failed: ${e.message}")
            }
        }

        // 4. Log to Adjust
        if (platforms.contains(TrackerPlatform.ADJUST) && config.adjustAppToken != null) {
            try {
                val token = config.adjustEventTokens[eventName]
                if (token == null) {
                    Log.d("KiroTracker", "Skipping Adjust event '$eventName': no token mapped in adjustEventTokens.")
                } else {
                    val adjustEvent = AdjustEvent(token)
                    val paramsMap = bundleToMap(params) ?: emptyMap()

                    // Forward standard revenue + currency to Adjust's setRevenue API
                    val rawRevenue = paramsMap["value"] ?: paramsMap["revenue"]
                    val revenue = when (rawRevenue) {
                        is Number -> rawRevenue.toDouble()
                        is String -> rawRevenue.toDoubleOrNull()
                        else -> null
                    }
                    val currency = (paramsMap["currency"] as? String) ?: "USD"
                    if (revenue != null) {
                        adjustEvent.setRevenue(revenue, currency)
                    }

                    // Forward remaining params as Adjust callback parameters (visible in Adjust dashboard)
                    for ((k, v) in paramsMap) {
                        if (k == "value" || k == "revenue" || k == "currency") continue
                        adjustEvent.addCallbackParameter(k, v.toString())
                    }

                    Adjust.trackEvent(adjustEvent)
                }
            } catch (e: Exception) {
                Log.e("KiroTracker", "Adjust logEvent failed: ${e.message}")
            }
        }
    }

    /**
     * Logs an event specifically to Firebase Analytics.
     */
    fun logFirebaseEvent(eventName: String, params: Bundle? = null) {
        logEvent(eventName, params, setOf(TrackerPlatform.FIREBASE))
    }

    /**
     * Logs an event specifically to AppsFlyer.
     */
    fun logAppsFlyerEvent(eventName: String, params: Bundle? = null) {
        logEvent(eventName, params, setOf(TrackerPlatform.APPSFLYER))
    }

    /**
     * Logs an event specifically to Facebook AppEvents.
     */
    fun logFacebookEvent(eventName: String, params: Bundle? = null) {
        logEvent(eventName, params, setOf(TrackerPlatform.FACEBOOK))
    }

    /**
     * Logs an event specifically to Adjust. Requires a token mapped via [Config.adjustEventTokens].
     */
    fun logAdjustEvent(eventName: String, params: Bundle? = null) {
        logEvent(eventName, params, setOf(TrackerPlatform.ADJUST))
    }

    /**
     * Forwards ad revenue to Adjust via its dedicated `Adjust.trackAdRevenue` API. This is more
     * idiomatic than `trackEvent` for ad revenue: it lands in Adjust's "Ad Revenue" dashboard with
     * automatic eCPM/ARPDAU rollups, and **does not require an event token**.
     *
     * Called automatically by `KiroLogEventManager.logPaidAdImpression` for every paid impression
     * (Banner / Interstitial / Rewarded / Native / App Open). You normally do not need to call
     * this yourself.
     *
     * @param revenueUsd Revenue value in the supplied [currency].
     * @param currency ISO 4217 currency code, e.g. "USD".
     * @param adUnitId AdMob ad unit ID (or other network's unit identifier).
     * @param adFormat Ad format string used as Adjust placement (e.g. "Banner", "Interstitial").
     * @param adNetworkSource Adjust ad source identifier. Defaults to `"admob_sdk"`.
     *   For other networks, pass the matching `AdjustConfig.AD_REVENUE_*` constant or its raw
     *   string value (e.g. `"applovin_max_sdk"`, `"ironsource_sdk"`, `"unity_sdk"`,
     *   `"admost_sdk"`, `"adx_sdk"`, or `AdjustConfig.AD_REVENUE_PUBLISHER` for custom).
     */
    fun logAdRevenue(
        revenueUsd: Double,
        currency: String,
        adUnitId: String,
        adFormat: String,
        adNetworkSource: String = "admob_sdk"
    ) {
        if (config.adjustAppToken == null) return
        try {
            val adRevenue = AdjustAdRevenue(adNetworkSource)
            adRevenue.setRevenue(revenueUsd, currency)
            adRevenue.setAdRevenueUnit(adUnitId)
            adRevenue.setAdRevenuePlacement(adFormat)
            Adjust.trackAdRevenue(adRevenue)
        } catch (e: Exception) {
            Log.e("KiroTracker", "Adjust trackAdRevenue failed: ${e.message}")
        }
    }

    /**
     * Sets user identity / customer ID across all tracking providers.
     */
    fun setUserId(userId: String) {
        Log.d("KiroTracker", "Setting User ID: $userId")

        // Firebase User ID
        try {
            firebaseAnalytics?.setUserId(userId)
        } catch (e: Exception) {
            Log.e("KiroTracker", "Firebase setUserId failed: ${e.message}")
        }

        // AppsFlyer Customer User ID
        if (config.appsFlyerDevKey != null) {
            try {
                AppsFlyerLib.getInstance().setCustomerUserId(userId)
            } catch (e: Exception) {
                Log.e("KiroTracker", "AppsFlyer setCustomerUserId failed: ${e.message}")
            }
        }

        // Facebook User ID
        if (config.enableFacebook) {
            try {
                AppEventsLogger.setUserID(userId)
            } catch (e: Exception) {
                Log.e("KiroTracker", "Facebook setUserID failed: ${e.message}")
            }
        }

        // Adjust User ID — surfaced to dashboard as a global callback parameter
        if (config.adjustAppToken != null) {
            try {
                Adjust.addGlobalCallbackParameter("user_id", userId)
            } catch (e: Exception) {
                Log.e("KiroTracker", "Adjust setUserId failed: ${e.message}")
            }
        }
    }

    /**
     * Sets a user property (Firebase Analytics specific).
     */
    fun setUserProperty(name: String, value: String?) {
        Log.d("KiroTracker", "Setting User Property: $name -> $value")
        try {
            firebaseAnalytics?.setUserProperty(name, value)
        } catch (e: Exception) {
            Log.e("KiroTracker", "Failed to set user property: ${e.message}")
        }
    }

    /**
     * Logs a screen view event.
     */
    fun logScreenView(screenName: String, screenClass: String? = null) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            if (screenClass != null) {
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
            }
        }
        logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
    }

    /**
     * Helper to convert Bundle objects to Maps (for AppsFlyer).
     */
    @Suppress("DEPRECATION")
    private fun bundleToMap(bundle: Bundle?): Map<String, Any>? {
        if (bundle == null) return null
        val map = HashMap<String, Any>()
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value != null) {
                map[key] = value
            }
        }
        return map
    }

    data class Config(
        val enableFirebase: Boolean = true,
        val appsFlyerDevKey: String? = null,
        val enableFacebook: Boolean = false,
        /**
         * Adjust app token (from your Adjust dashboard). When `null`, Adjust is disabled.
         */
        val adjustAppToken: String? = null,
        /**
         * When `true`, Adjust runs in sandbox/test environment with verbose logging.
         * Set to `true` for debug builds, `false` for production.
         */
        val adjustSandbox: Boolean = false,
        /**
         * Maps your event names (used in `logEvent`) to Adjust event tokens (created on the
         * Adjust dashboard). Events without a mapping are skipped for Adjust silently.
         */
        val adjustEventTokens: Map<String, String> = emptyMap()
    )
}
