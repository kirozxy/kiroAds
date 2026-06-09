package com.kiro.sdk.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.kiro.sdk.R
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.databinding.KiroDefaultNativeAdBinding
import com.kiro.sdk.event.KiroLogEventManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class KiroAds(val config: Config) {

    private val loadedInterstitialAds = java.util.Collections.synchronizedMap(
        java.util.HashMap<String, InterstitialAd>()
    )

    private val loadedRewardedAds = java.util.Collections.synchronizedMap(
        java.util.HashMap<String, RewardedAd>()
    )

    private val activeNativeAdViews = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<KiroNativeAdView, Boolean>())
    )

    private val activeBannerContainers = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<ViewGroup, Boolean>()
    )

    /**
     * Hides and destroys all active ads immediately (e.g. when removing ads on purchase).
     */
    fun hideAllActiveAds() {
        synchronized(activeNativeAdViews) {
            for (view in activeNativeAdViews) {
                view.visibility = android.view.View.GONE
            }
        }
        synchronized(activeBannerContainers) {
            for (container in activeBannerContainers.keys) {
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is AdView) {
                        child.destroy()
                    }
                }
                container.removeAllViews()
                container.visibility = android.view.View.GONE
            }
        }
    }

    fun registerNativeAdView(view: KiroNativeAdView) {
        activeNativeAdViews.add(view)
        val activity = view.context.findActivity()
        if (activity != null && activity.isActivityInPiP()) {
            view.onPiPModeChanged(true)
        }
    }

    fun unregisterNativeAdView(view: KiroNativeAdView) {
        activeNativeAdViews.remove(view)
    }

    fun onPictureInPictureModeChanged(activity: Activity, isInPictureInPictureMode: Boolean) {
        Log.d("KiroAds", "Activity ${activity.javaClass.simpleName} Picture-in-Picture mode changed: isInPictureInPictureMode = $isInPictureInPictureMode")

        // Collapse or restore only native ad views belonging to this activity
        synchronized(activeNativeAdViews) {
            for (view in activeNativeAdViews) {
                if (view.context.findActivity() == activity) {
                    view.onPiPModeChanged(isInPictureInPictureMode)
                }
            }
        }

        // Collapse or restore only banner containers belonging to this activity
        synchronized(activeBannerContainers) {
            for ((container, wasHiddenByPiP) in activeBannerContainers.entries) {
                if (container.context.findActivity() == activity) {
                    if (isInPictureInPictureMode) {
                        if (container.visibility == android.view.View.VISIBLE) {
                            container.visibility = android.view.View.GONE
                            activeBannerContainers[container] = true
                        }
                    } else {
                        if (wasHiddenByPiP == true) {
                            if (!KiroSdk.isAdsDisabled) {
                                container.visibility = android.view.View.VISIBLE
                            }
                            activeBannerContainers[container] = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads an Interstitial Ad asynchronously.
     * Returns true if loaded successfully, false otherwise.
     */
    suspend fun loadInterstitial(context: Context, adUnitId: String): Boolean {
        if (KiroSdk.isAdsDisabled || !KiroConsentManager.canRequestAds(context)) {
            Log.d("KiroAds", "Ads are disabled or UMP Consent is not gathered. Skipping loadInterstitial.")
            return false
        }
        return suspendCancellableCoroutine { continuation ->
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loadedInterstitialAds.remove(adUnitId)
                    Log.e("KiroAds", "Error loading Interstitial: ${adError.message}")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    loadedInterstitialAds[adUnitId] = interstitialAd
                    
                    // Automatically attach ad revenue tracking listener
                    interstitialAd.setOnPaidEventListener { adValue ->
                        KiroLogEventManager.logPaidAdImpression(context, adValue, adUnitId, "Interstitial")
                    }
                    
                    Log.d("KiroAds", "Interstitial loaded successfully")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            })
        }
    }

    /**
     * Loads an Interstitial Ad with a 2-floor priority waterfall.
     * Tries high priority first, falls back to low priority if it fails.
     * Returns true if successfully loaded on either floor, false otherwise.
     */
    suspend fun loadInterstitial2F(context: Context, highAdUnitId: String, lowAdUnitId: String): Boolean {
        var loaded = loadInterstitial(context, highAdUnitId)
        if (!loaded) {
            Log.d("KiroAds", "High floor Interstitial failed to load. Fetching low floor...")
            loaded = loadInterstitial(context, lowAdUnitId)
        }
        return loaded
    }

    /**
     * Shows the Interstitial Ad if it has been loaded previously.
     */
    fun showInterstitial(activity: Activity, adUnitId: String, onAdDismissed: (() -> Unit)? = null) {
        if (KiroSdk.isAdsDisabled || activity.isActivityInPiP()) {
            Log.d("KiroAds", "Ads are disabled or activity is in PiP mode. Skipping showInterstitial.")
            onAdDismissed?.invoke()
            return
        }
        val ad = loadedInterstitialAds.remove(adUnitId)
        if (ad != null) {
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdClicked() {
                    // Track click event automatically
                    KiroLogEventManager.logClickAdsEvent(ad.adUnitId)
                }
                
                override fun onAdDismissedFullScreenContent() {
                    onAdDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    onAdDismissed?.invoke()
                }
            }
            ad.show(activity)
        } else {
            Log.w("KiroAds", "Interstitial Ad is not ready yet.")
            onAdDismissed?.invoke()
        }
    }

    /**
     * Shows the Interstitial Ad from a 2-floor waterfall by checking either the high or low floor cache.
     */
    fun showInterstitial2F(activity: Activity, highAdUnitId: String, lowAdUnitId: String, onAdDismissed: (() -> Unit)? = null) {
        if (loadedInterstitialAds.containsKey(highAdUnitId)) {
            showInterstitial(activity, highAdUnitId, onAdDismissed)
        } else if (loadedInterstitialAds.containsKey(lowAdUnitId)) {
            showInterstitial(activity, lowAdUnitId, onAdDismissed)
        } else {
            Log.w("KiroAds", "Neither high floor ($highAdUnitId) nor low floor ($lowAdUnitId) Interstitial Ad is ready yet.")
            onAdDismissed?.invoke()
        }
    }

    /**
     * Loads an Interstitial ad with a 2-floor waterfall (High -> Low)
     * and shows it automatically upon successful load.
     * Displays a blocking progress dialog during loading.
     * Proceeds immediately calling onAdDismissed if both fail.
     */
    fun loadAndShowInterstitial2F(
        activity: Activity,
        highAdUnitId: String,
        lowAdUnitId: String,
        onAdDismissed: () -> Unit
    ) {
        if (!activity.isActivityAlive()) {
            onAdDismissed()
            return
        }

        // Show preparing ad loading dialog locally
        val dialog = createLoadingDialog(activity)
        dialog.show()

        val scope = activity.getLifecycleScope()
        scope.launch {
            // Load using 2-floor mechanism
            val loaded = loadInterstitial2F(activity, highAdUnitId, lowAdUnitId)

            // Dismiss dialog safely on main thread
            activity.runOnUiThread {
                if (activity.isActivityAlive()) {
                    dialog.dismiss()
                }
            }

            if (activity.isActivityAlive()) {
                if (loaded) {
                    showInterstitial2F(activity, highAdUnitId, lowAdUnitId, onAdDismissed)
                } else {
                    Log.e("KiroAds", "Both Interstitial floors failed to load. Skipping ad.")
                    onAdDismissed()
                }
            }
        }
    }

    /**
     * Loads a Rewarded Ad asynchronously.
     * Returns true if loaded successfully, false otherwise.
     */
    suspend fun loadRewarded(context: Context, adUnitId: String): Boolean {
        if (KiroSdk.isAdsDisabled || !KiroConsentManager.canRequestAds(context)) {
            Log.d("KiroAds", "Ads are disabled or UMP Consent is not gathered. Skipping loadRewarded.")
            return false
        }
        return suspendCancellableCoroutine { continuation ->
            val adRequest = AdRequest.Builder().build()
            RewardedAd.load(context, adUnitId, adRequest, object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loadedRewardedAds.remove(adUnitId)
                    Log.e("KiroAds", "Error loading Rewarded: ${adError.message}")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    loadedRewardedAds[adUnitId] = rewardedAd
                    
                    // Automatically attach ad revenue tracking listener
                    rewardedAd.setOnPaidEventListener { adValue ->
                        KiroLogEventManager.logPaidAdImpression(context, adValue, adUnitId, "Rewarded")
                    }
                    
                    Log.d("KiroAds", "Rewarded loaded successfully")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            })
        }
    }

    /**
     * Shows the Rewarded Ad if it is loaded.
     */
    fun showRewarded(
        activity: Activity,
        adUnitId: String,
        onUserEarnedReward: (amount: Int, type: String) -> Unit,
        onAdDismissed: (() -> Unit)? = null
    ) {
        if (KiroSdk.isAdsDisabled || activity.isActivityInPiP()) {
            Log.d("KiroAds", "Ads are disabled or activity is in PiP mode. Skipping showRewarded.")
            onAdDismissed?.invoke()
            return
        }
        val ad = loadedRewardedAds.remove(adUnitId)
        if (ad != null) {
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdClicked() {
                    // Track click event automatically
                    KiroLogEventManager.logClickAdsEvent(ad.adUnitId)
                }
                
                override fun onAdDismissedFullScreenContent() {
                    onAdDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    onAdDismissed?.invoke()
                }
            }
            ad.show(activity) { rewardItem ->
                onUserEarnedReward(rewardItem.amount, rewardItem.type)
            }
        } else {
            Log.w("KiroAds", "Rewarded Ad is not ready yet.")
            onAdDismissed?.invoke()
        }
    }

    /**
     * Loads and attaches a Banner Ad directly into a Container View.
     */
    fun showBanner(activity: Activity, container: ViewGroup, adUnitId: String, adSize: AdSize = AdSize.BANNER) {
        synchronized(activeBannerContainers) {
            activeBannerContainers[container] = false
        }
        
        // Destroy any existing AdView in this container first
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is AdView) {
                child.destroy()
            }
        }
        container.removeAllViews()
        
        container.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                synchronized(activeBannerContainers) {
                    activeBannerContainers[container] = false
                }
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                synchronized(activeBannerContainers) {
                    activeBannerContainers.remove(container)
                }
                // Destroy AdViews inside the container on detach to prevent memory leaks
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is AdView) {
                        child.destroy()
                    }
                }
                container.removeAllViews()
                container.removeOnAttachStateChangeListener(this)
            }
        })

        if (KiroSdk.isAdsDisabled || activity.isActivityInPiP() || !KiroConsentManager.canRequestAds(activity)) {
            Log.d("KiroAds", "Ads are disabled, activity is in PiP mode, or UMP Consent is not gathered. Hiding banner container.")
            container.visibility = android.view.View.GONE
            return
        }
        val adView = AdView(activity)
        adView.adUnitId = adUnitId
        adView.setAdSize(adSize)
        
        // Track ad revenue automatically
        adView.setOnPaidEventListener { adValue ->
            KiroLogEventManager.logPaidAdImpression(activity, adValue, adUnitId, "Banner")
        }
        
        // Track ad clicks automatically
        adView.adListener = object : com.google.android.gms.ads.AdListener() {
            override fun onAdClicked() {
                KiroLogEventManager.logClickAdsEvent(adUnitId)
            }
        }
        
        container.addView(adView)
        
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    /**
     * Loads a Native Ad asynchronously.
     * Returns the NativeAd object if successful, null otherwise.
     */
    suspend fun loadNativeAd(context: Context, adUnitId: String): NativeAd? {
        if (KiroSdk.isAdsDisabled || !KiroConsentManager.canRequestAds(context)) {
            Log.d("KiroAds", "Ads are disabled or UMP Consent is not gathered. Skipping loadNativeAd.")
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val adLoader = AdLoader.Builder(context, adUnitId)
                .forNativeAd { nativeAd ->
                    // Track ad revenue automatically
                    nativeAd.setOnPaidEventListener { adValue ->
                        KiroLogEventManager.logPaidAdImpression(context, adValue, adUnitId, "Native")
                    }
                    
                    if (continuation.isActive) {
                        continuation.resume(nativeAd)
                    }
                }
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdClicked() {
                        // Track click event automatically
                        KiroLogEventManager.logClickAdsEvent(adUnitId)
                    }
                    
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.e("KiroAds", "Error loading Native Ad: ${loadAdError.message}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                })
                .build()

            adLoader.loadAd(AdRequest.Builder().build())
        }
    }

    /**
     * Loads a Native Ad with a 2-floor priority waterfall.
     * Tries high priority first, falls back to low priority if it fails.
     * Returns the loaded NativeAd if successful on either floor, null otherwise.
     */
    suspend fun loadNativeAd2F(context: Context, highAdUnitId: String, lowAdUnitId: String): NativeAd? {
        var nativeAd = loadNativeAd(context, highAdUnitId)
        if (nativeAd == null) {
            Log.d("KiroAds", "High floor Native Ad failed to load. Fetching low floor...")
            nativeAd = loadNativeAd(context, lowAdUnitId)
        }
        return nativeAd
    }

    /**
     * Loads a Native Ad and immediately displays it in the provided container ViewGroup.
     * Uses the default SDK native ad layout.
     *
     * @param context Android context.
     * @param adUnitId The Ad Unit ID.
     * @param container The container ViewGroup where the ad will be placed.
     * @param onComplete Callback executed with the loading result (true if success, false if failed).
     */
    fun loadAndShowNativeAd(
        context: Context,
        adUnitId: String,
        container: ViewGroup,
        onComplete: ((success: Boolean) -> Unit)? = null
    ) {
        if (KiroSdk.isAdsDisabled) {
            Log.d("KiroAds", "Ads are disabled. Hiding native ad container.")
            container.removeAllViews()
            container.visibility = android.view.View.GONE
            onComplete?.invoke(false)
            return
        }
        val scope = context.getLifecycleScope()
        scope.launch {
            val nativeAd = loadNativeAd(context, adUnitId)
            val activity = container.context.findActivity()
            if (activity == null || activity.isActivityAlive()) {
                if (nativeAd != null) {
                    val adView = inflateDefaultNativeAdView(context)
                    populateNativeAdView(nativeAd, adView)
                    
                    // Destroy any existing AdViews inside the container
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (child is AdView) {
                            child.destroy()
                        }
                    }
                    container.removeAllViews()
                    container.addView(adView)
                    
                    if (activity != null && activity.isActivityInPiP()) {
                        container.visibility = android.view.View.GONE
                    } else {
                        container.visibility = android.view.View.VISIBLE
                    }
                    onComplete?.invoke(true)
                } else {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Loads a Native Ad with a 2-floor waterfall and immediately displays it in the provided container ViewGroup.
     * Uses the default SDK native ad layout.
     *
     * @param context Android context.
     * @param highAdUnitId High priority Ad Unit ID.
     * @param lowAdUnitId Low priority Ad Unit ID.
     * @param container The container ViewGroup where the ad will be placed.
     * @param onComplete Callback executed with the loading result.
     */
    fun loadAndShowNativeAd2F(
        context: Context,
        highAdUnitId: String,
        lowAdUnitId: String,
        container: ViewGroup,
        onComplete: ((success: Boolean) -> Unit)? = null
    ) {
        if (KiroSdk.isAdsDisabled) {
            Log.d("KiroAds", "Ads are disabled. Hiding native ad container.")
            container.removeAllViews()
            container.visibility = android.view.View.GONE
            onComplete?.invoke(false)
            return
        }
        val scope = context.getLifecycleScope()
        scope.launch {
            val nativeAd = loadNativeAd2F(context, highAdUnitId, lowAdUnitId)
            val activity = container.context.findActivity()
            if (activity == null || activity.isActivityAlive()) {
                if (nativeAd != null) {
                    val adView = inflateDefaultNativeAdView(context)
                    populateNativeAdView(nativeAd, adView)
                    
                    // Destroy any existing AdViews inside the container
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (child is AdView) {
                            child.destroy()
                        }
                    }
                    container.removeAllViews()
                    container.addView(adView)
                    
                    if (activity != null && activity.isActivityInPiP()) {
                        container.visibility = android.view.View.GONE
                    } else {
                        container.visibility = android.view.View.VISIBLE
                    }
                    onComplete?.invoke(true)
                } else {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Inflates the SDK's default Native AdView using View Binding.
     */
    fun inflateDefaultNativeAdView(context: Context): NativeAdView {
        val binding = KiroDefaultNativeAdBinding.inflate(LayoutInflater.from(context))
        val adView = binding.root
        
        // Register layout views to the Google NativeAdView wrapper
        adView.headlineView = binding.adHeadline
        adView.bodyView = binding.adBody
        adView.callToActionView = binding.adCallToAction
        adView.iconView = binding.adAppIcon
        adView.mediaView = binding.adMedia
        
        return adView
    }

    /**
     * Populates native ad details into a NativeAdView.
     * Safely checks for mapped views to support both View Binding structures and custom XMLs.
     */
    fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        // Map elements safely, supporting both pre-bound View Binding references and custom lookups
        adView.headlineView = adView.findViewById(R.id.ad_headline) ?: adView.headlineView
        adView.bodyView = adView.findViewById(R.id.ad_body) ?: adView.bodyView
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action) ?: adView.callToActionView
        adView.iconView = adView.findViewById(R.id.ad_app_icon) ?: adView.iconView
        adView.mediaView = adView.findViewById(R.id.ad_media) ?: adView.mediaView

        (adView.headlineView as? android.widget.TextView)?.text = nativeAd.headline

        if (nativeAd.body == null) {
            adView.bodyView?.visibility = android.view.View.INVISIBLE
        } else {
            adView.bodyView?.visibility = android.view.View.VISIBLE
            (adView.bodyView as? android.widget.TextView)?.text = nativeAd.body
        }

        if (nativeAd.callToAction == null) {
            adView.callToActionView?.visibility = android.view.View.INVISIBLE
        } else {
            adView.callToActionView?.visibility = android.view.View.VISIBLE
            (adView.callToActionView as? android.widget.Button)?.text = nativeAd.callToAction
        }

        if (nativeAd.icon == null) {
            adView.iconView?.visibility = android.view.View.GONE
        } else {
            adView.iconView?.visibility = android.view.View.VISIBLE
            (adView.iconView as? android.widget.ImageView)?.setImageDrawable(nativeAd.icon?.drawable)
        }

        adView.setNativeAd(nativeAd)
    }

    private fun createLoadingDialog(activity: Activity): android.app.Dialog {
        val builder = android.app.AlertDialog.Builder(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 40, 50, 40)
            gravity = Gravity.CENTER_VERTICAL
        }
        val progressBar = ProgressBar(activity).apply {
            isIndeterminate = true
        }
        val textView = TextView(activity).apply {
            text = "Preparing Ad..."
            textSize = 16f
            setPadding(40, 0, 0, 0)
        }
        layout.addView(progressBar)
        layout.addView(textView)
        builder.setView(layout)
        builder.setCancelable(false)
        return builder.create()
    }

    data class Config(
        val testDeviceIds: List<String> = emptyList()
    )
}

/**
 * Context extension to safely unwrap and find the host Activity.
 */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Safely check if the activity is in Picture-in-Picture mode, supporting SDKs back to API 21.
 */
fun Activity.isActivityInPiP(): Boolean {
    return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && this.isInPictureInPictureMode
}
