package com.kiro.sdk.ads

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KiroNativeAdView : FrameLayout {

    private var layoutResId: Int = R.layout.kiro_default_native_ad
    val googleNativeAdView: NativeAdView

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.KiroNativeAdView, 0, 0)
            try {
                layoutResId = typedArray.getResourceId(
                    R.styleable.KiroNativeAdView_layout_native,
                    R.layout.kiro_default_native_ad
                )
            } finally {
                typedArray.recycle()
            }
        }
        googleNativeAdView = inflateAndSetupView()
    }

    constructor(context: Context, layoutResId: Int) : super(context) {
        this.layoutResId = layoutResId
        googleNativeAdView = inflateAndSetupView()
    }

    private fun inflateAndSetupView(): NativeAdView {
        if (KiroSdk.isAdsDisabled) {
            this.visibility = GONE
        }
        // Inflate the layout and determine if it's already a NativeAdView or needs a wrapper
        val inflatedView = LayoutInflater.from(context).inflate(layoutResId, this, false)
        val adView: NativeAdView
        if (inflatedView is NativeAdView) {
            adView = inflatedView
            if (adView.layoutParams == null) {
                adView.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            }
            addView(adView)
        } else {
            adView = NativeAdView(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                addView(inflatedView)
            }
            addView(adView)
        }
        return adView
    }

    private var isAdLoaded = false
    private var isPiPHidden = false
    private var currentNativeAd: NativeAd? = null
    /**
     * True when the SDK loaded the current ad on behalf of this view (via [loadAndShowAd]
     * or [loadAndShowAd2F]). In that case, the SDK owns the ad lifecycle and must destroy it
     * when the view is detached. False when the dev supplied the ad via [setNativeAd] —
     * the dev keeps full control of the ad's lifecycle (typical for lists / ViewModels).
     */
    private var ownsCurrentAd = false

    /**
     * Binds an externally loaded NativeAd object to the view.
     *
     * The dev keeps full control of the ad's lifecycle: the SDK will NOT call
     * [NativeAd.destroy] on this ad when the view is detached. Use this when the ad is shared
     * across multiple views or held in a ViewModel (typical for lists like LazyColumn /
     * RecyclerView, or for cross-screen preloading).
     */
    fun setNativeAd(nativeAd: NativeAd) {
        bindNativeAd(nativeAd, owned = false)
    }

    /**
     * Internal binding used by [loadAndShowAd] / [loadAndShowAd2F]. The SDK owns the ad and
     * will destroy it when the view detaches.
     */
    internal fun bindNativeAd(nativeAd: NativeAd, owned: Boolean) {
        if (KiroSdk.isAdsDisabled) {
            this.visibility = GONE
            isAdLoaded = false
            if (ownsCurrentAd) currentNativeAd?.destroy()
            currentNativeAd = null
            ownsCurrentAd = false
            return
        }

        // Only destroy the previous ad if the SDK owned it. External ads belong to the caller.
        if (currentNativeAd != nativeAd && ownsCurrentAd) {
            currentNativeAd?.destroy()
        }
        currentNativeAd = nativeAd
        ownsCurrentAd = owned
        isAdLoaded = true

        val activity = context.findActivity()
        if (activity != null && activity.isActivityInPiP()) {
            this.visibility = GONE
            isPiPHidden = true
        } else {
            this.visibility = VISIBLE
            isPiPHidden = false
        }
        KiroSdk.ads.populateNativeAdView(nativeAd, googleNativeAdView)
    }

    fun onPiPModeChanged(isInPiP: Boolean) {
        if (isInPiP) {
            if (visibility == VISIBLE) {
                visibility = GONE
                isPiPHidden = true
            }
        } else {
            if (isPiPHidden) {
                if (!KiroSdk.isAdsDisabled && isAdLoaded) {
                    visibility = VISIBLE
                }
                isPiPHidden = false
            }
        }
    }

    /**
     * Asynchronously loads a Native Ad and displays it inside this view.
     * Uses the layout specified via app:layout_native or the SDK default.
     *
     * @param adUnitId The AdMob Ad Unit ID.
     * @param onComplete Optional callback executed when loading completes.
     */
    fun loadAndShowAd(adUnitId: String, onComplete: ((success: Boolean) -> Unit)? = null) {
        if (KiroSdk.isAdsDisabled) {
            this.visibility = GONE
            onComplete?.invoke(false)
            return
        }
        val scope = context.getLifecycleScope()
        scope.launch {
            val nativeAd = KiroSdk.ads.loadNativeAd(context, adUnitId)
            val activity = context.findActivity()
            // Make sure the activity is still alive before showing the ad or executing the callback
            if (activity == null || activity.isActivityAlive()) {
                if (nativeAd != null) {
                    bindNativeAd(nativeAd, owned = true)
                    onComplete?.invoke(true)
                } else {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Asynchronously loads a Native Ad using a 2-floor waterfall and displays it inside this view.
     * Uses the layout specified via app:layout_native or the SDK default.
     *
     * @param highAdUnitId High priority Ad Unit ID.
     * @param lowAdUnitId Low priority Ad Unit ID.
     * @param onComplete Optional callback executed when loading completes.
     */
    fun loadAndShowAd2F(highAdUnitId: String, lowAdUnitId: String, onComplete: ((success: Boolean) -> Unit)? = null) {
        if (KiroSdk.isAdsDisabled) {
            this.visibility = GONE
            onComplete?.invoke(false)
            return
        }
        val scope = context.getLifecycleScope()
        scope.launch {
            val nativeAd = KiroSdk.ads.loadNativeAd2F(context, highAdUnitId, lowAdUnitId)
            val activity = context.findActivity()
            // Make sure the activity is still alive before showing the ad or executing the callback
            if (activity == null || activity.isActivityAlive()) {
                if (nativeAd != null) {
                    bindNativeAd(nativeAd, owned = true)
                    onComplete?.invoke(true)
                } else {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KiroSdk.ads.registerNativeAdView(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        KiroSdk.ads.unregisterNativeAdView(this)
        // Only destroy when the SDK owns the ad. External ads stay alive — the dev manages them
        // (e.g. across LazyColumn / RecyclerView item recycling, or shared across screens).
        if (ownsCurrentAd) {
            currentNativeAd?.destroy()
        }
        currentNativeAd = null
        ownsCurrentAd = false
        isAdLoaded = false
    }
}

/**
 * Safely check if the activity is alive.
 */
fun Activity.isActivityAlive(): Boolean {
    return !this.isFinishing && !this.isDestroyed
}

/**
 * Shared SDK-level fallback scope, used only when the hosting context is not a LifecycleOwner.
 * Tied to the application lifetime rather than created ad-hoc on every call.
 */
internal val kiroFallbackScope: CoroutineScope by lazy {
    CoroutineScope(Dispatchers.Main + SupervisorJob())
}

/**
 * Safely retrieve the coroutine scope associated with the hosting lifecycle.
 */
fun Context.getLifecycleScope(): CoroutineScope {
    val activity = this.findActivity()
    if (activity is androidx.lifecycle.LifecycleOwner) {
        return activity.lifecycleScope
    }
    return kiroFallbackScope
}
