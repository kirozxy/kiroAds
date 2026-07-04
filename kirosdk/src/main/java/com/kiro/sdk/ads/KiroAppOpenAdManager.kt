package com.kiro.sdk.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.event.KiroLogEventManager

/**
 * Auto-managed App Open ads.
 *
 * Loads on start, listens to ProcessLifecycleOwner ON_START events to detect when the app
 * comes to foreground, and shows the loaded ad on the current Activity. Cold start is skipped
 * by default to avoid showing on first launch.
 *
 * Use the blocklist to suppress auto-show on specific screens (e.g. Splash, Onboarding,
 * Premium subscription pages):
 * ```
 * KiroSdk.ads.appOpen.start(
 *     application = this,
 *     adUnitId = "...",
 *     blockedActivities = setOf(SplashActivity::class.java, OnboardingActivity::class.java)
 * )
 * ```
 *
 * For one-off suppression around your own splash interstitial flow, use [disableNextShow].
 */
class KiroAppOpenAdManager : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private var application: Application? = null
    private var adUnitId: String? = null
    private var skipFirstLaunch: Boolean = true
    private val blockedActivities: MutableSet<Class<out Activity>> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    private var isLoadingAd: Boolean = false
    private var isShowingAd: Boolean = false
    private var isFirstLaunch: Boolean = true
    private var isInitialized: Boolean = false
    private var skipNextShow: Boolean = false

    private var currentActivity: Activity? = null

    /**
     * Starts the manager. Must be called from your Application.onCreate (or after KiroSdk.init).
     */
    fun start(
        application: Application,
        adUnitId: String,
        skipFirstLaunch: Boolean = true,
        blockedActivities: Set<Class<out Activity>> = emptySet()
    ) {
        if (isInitialized) return
        this.application = application
        this.adUnitId = adUnitId
        this.skipFirstLaunch = skipFirstLaunch
        this.blockedActivities.clear()
        this.blockedActivities.addAll(blockedActivities)
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        isInitialized = true
        loadAd()
    }

    /** Replaces the blocklist of Activity classes where auto-show is suppressed. */
    fun setBlockedActivities(activities: Set<Class<out Activity>>) {
        blockedActivities.clear()
        blockedActivities.addAll(activities)
    }

    /** Adds a single Activity class to the auto-show blocklist. */
    fun addBlockedActivity(activity: Class<out Activity>) {
        blockedActivities.add(activity)
    }

    /** Removes a single Activity class from the auto-show blocklist. */
    fun removeBlockedActivity(activity: Class<out Activity>) {
        blockedActivities.remove(activity)
    }

    /** Stops the manager and unregisters lifecycle callbacks. */
    fun stop() {
        if (!isInitialized) return
        application?.unregisterActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        currentActivity = null
        isInitialized = false
    }

    /** Skip the next auto-show. Useful when running another full-screen ad (e.g. splash inter). */
    fun disableNextShow() {
        skipNextShow = true
    }

    /** True if a non-expired App Open ad is currently cached and ready to show. */
    fun isAdAvailable(): Boolean {
        val unitId = adUnitId ?: return false
        return KiroAdPool.hasAd(AdType.APP_OPEN, unitId)
    }

    private fun loadAd() {
        val app = application ?: return
        val unitId = adUnitId ?: return
        if (isLoadingAd || isAdAvailable()) return
        if (KiroSdk.isAdsDisabled || !KiroConsentManager.canRequestAds(app)) {
            Log.d(TAG, "Ads disabled or consent not granted. Skipping App Open load.")
            return
        }
        isLoadingAd = true
        AppOpenAd.load(
            app, unitId, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    KiroAdPool.putAd(AdType.APP_OPEN, unitId, ad)
                    isLoadingAd = false
                    ad.setOnPaidEventListener { adValue ->
                        KiroLogEventManager.logPaidAdImpression(app, adValue, unitId, "AppOpen")
                    }
                    Log.d(TAG, "App Open Ad loaded.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    Log.e(TAG, "App Open Ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun showAdIfAvailable(activity: Activity, onDismissed: (() -> Unit)? = null) {
        val unitId = adUnitId ?: run {
            onDismissed?.invoke()
            return
        }
        if (isShowingAd) {
            onDismissed?.invoke()
            return
        }
        if (skipNextShow) {
            skipNextShow = false
            onDismissed?.invoke()
            return
        }
        if (KiroSdk.isAdsDisabled || activity.isActivityInPiP()) {
            onDismissed?.invoke()
            return
        }
        if (blockedActivities.contains(activity.javaClass)) {
            Log.d(TAG, "Skipping App Open: ${activity.javaClass.simpleName} is in the blocklist.")
            onDismissed?.invoke()
            return
        }
        if (!isAdAvailable()) {
            onDismissed?.invoke()
            loadAd()
            return
        }
        if (!KiroSdk.ads.canShowFullScreenAd()) {
            Log.d(TAG, "Skipping App Open: full-screen ad cooldown active.")
            onDismissed?.invoke()
            return
        }
        val ad = KiroAdPool.getAd(AdType.APP_OPEN, unitId) as? AppOpenAd ?: run {
            onDismissed?.invoke()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdClicked() {
                KiroLogEventManager.logClickAdsEvent(unitId)
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingAd = false
                onDismissed?.invoke()
                loadAd()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                isShowingAd = false
                onDismissed?.invoke()
                loadAd()
            }
        }
        isShowingAd = true
        ad.show(activity)
        KiroSdk.ads.markFullScreenAdShown()
    }

    // -------------------- Application.ActivityLifecycleCallbacks --------------------

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        if (!isShowingAd) currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    // -------------------- DefaultLifecycleObserver (process foreground) --------------------

    override fun onStart(owner: LifecycleOwner) {
        if (skipFirstLaunch && isFirstLaunch) {
            isFirstLaunch = false
            return
        }
        currentActivity?.let { showAdIfAvailable(it) }
    }

    companion object {
        private const val TAG = "KiroAppOpen"
    }
}
