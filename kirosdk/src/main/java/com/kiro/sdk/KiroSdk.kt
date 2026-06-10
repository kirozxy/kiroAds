package com.kiro.sdk

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.app.OnPictureInPictureModeChangedProvider
import androidx.core.util.Consumer
import com.google.android.gms.ads.MobileAds
import com.kiro.sdk.ads.KiroAds
import com.kiro.sdk.ads.KiroConsentManager
import com.kiro.sdk.billing.KiroBilling
import com.kiro.sdk.tracking.KiroTracker

import com.kiro.sdk.util.KiroPreferenceUtils

object KiroSdk {
    private var isInitialized = false
    
    var isDebug: Boolean = false
        private set

    var isAdsDisabled: Boolean = false
        private set

    private var enableAds: Boolean = true

    lateinit var ads: KiroAds
        private set

    lateinit var tracker: KiroTracker
        private set

    lateinit var billing: KiroBilling
        private set

    val consent: KiroConsentManager = KiroConsentManager

    /**
     * Initializes KiroSdk with context and optional custom configurations.
     */
    private var isMobileAdsInitialized = false

    /**
     * Initializes Google Mobile Ads with test device configuration.
     */
    @Synchronized
    fun initializeMobileAds(context: Context) {
        if (isMobileAdsInitialized) return
        // Respect global ads configuration: never initialize when ads are disabled (e.g. purchased)
        if (!enableAds || isAdsDisabled) {
            android.util.Log.d("KiroSdk", "Skipping Google Mobile Ads init: ads are disabled.")
            return
        }
        val appContext = context.applicationContext
        
        // Configure testDeviceIds to prevent loading real ads during debugging
        val requestConfiguration = com.google.android.gms.ads.RequestConfiguration.Builder()
            .setTestDeviceIds(ads.config.testDeviceIds)
            .build()
        MobileAds.setRequestConfiguration(requestConfiguration)

        MobileAds.initialize(appContext) {}
        isMobileAdsInitialized = true
        android.util.Log.d("KiroSdk", "Google Mobile Ads initialized successfully.")
    }

    /**
     * Initializes KiroSdk with context and optional custom configurations.
     */
    fun init(context: Context, config: SdkConfig) {
        if (isInitialized) return
        
        val appContext = context.applicationContext
        KiroPreferenceUtils.init(appContext)
        isDebug = config.isDebug
        enableAds = config.enableAds
        isAdsDisabled = KiroPreferenceUtils.isAdsDisabled()

        // Create Ads wrapper instance first
        ads = KiroAds(config.adConfig)

        // Initialize Google Mobile Ads only if enabled, ads are not disabled, and user consent is granted.
        // The enableAds/isAdsDisabled gating is enforced inside initializeMobileAds().
        if (consent.canRequestAds(appContext)) {
            initializeMobileAds(appContext)
        } else {
            android.util.Log.d("KiroSdk", "Delaying Google Mobile Ads initialization: UMP consent not yet granted.")
        }

        // Automatically register activity lifecycle callbacks to handle PiP automatically
        if (appContext is Application) {
            appContext.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private val pipListeners = java.util.HashMap<Activity, Consumer<androidx.core.app.PictureInPictureModeChangedInfo>>()

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is OnPictureInPictureModeChangedProvider) {
                        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                            ads.onPictureInPictureModeChanged(activity, info.isInPictureInPictureMode)
                        }
                        activity.addOnPictureInPictureModeChangedListener(listener)
                        pipListeners[activity] = listener
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity is OnPictureInPictureModeChangedProvider) {
                        pipListeners.remove(activity)?.let { listener ->
                            activity.removeOnPictureInPictureModeChangedListener(listener)
                        }
                    }
                }

                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            })
        }

        // Initialize Analytics Tracker
        tracker = KiroTracker(appContext, config.trackingConfig)

        // Initialize Billing Client wrapper
        billing = KiroBilling(appContext, config.billingConfig)

        isInitialized = true
    }

    /**
     * Disables ads (e.g., when a purchase is successful).
     * Saves the preference to persist across app sessions.
     */
    fun setAdsDisabled(disabled: Boolean) {
        isAdsDisabled = disabled
        KiroPreferenceUtils.setAdsDisabled(disabled)
        if (disabled && ::ads.isInitialized) {
            ads.hideAllActiveAds()
        }
    }

    /**
     * A helper to immediately disable all ads.
     */
    fun disableAds() {
        setAdsDisabled(true)
    }

    /**
     * Releases billing and active resources.
     */
    fun release() {
        if (isInitialized) {
            if (::ads.isInitialized) {
                ads.appOpen.stop()
            }
            if (::billing.isInitialized) {
                billing.release()
            }
            isInitialized = false
        }
    }

    /**
     * Overall configuration for the SDK.
     */
    data class SdkConfig(
        val isDebug: Boolean = false,
        val enableAds: Boolean = true,
        val adConfig: KiroAds.Config = KiroAds.Config(),
        val trackingConfig: KiroTracker.Config = KiroTracker.Config(),
        val billingConfig: KiroBilling.Config = KiroBilling.Config()
    )
}
