package com.kiro.sdk.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.event.KiroLogEventManager

/**
 * A Composable that displays an AdMob Banner Ad.
 * Can be used directly in any Jetpack Compose screen.
 * Supports collapsible banners by providing [collapsibleType].
 *
 * Automatically skips rendering when ads are disabled (e.g. premium purchase)
 * or UMP consent has not been granted, matching the View-based [KiroAds.showBanner] behavior.
 *
 * @param adUnitId The AdMob Ad Unit ID.
 * @param modifier Modifier for custom layout configuration.
 * @param adSize The Banner Ad size (defaults to BANNER).
 * @param collapsibleType Collapsible position: "top", "bottom", or null (standard adaptive banner).
 */
@Composable
fun KiroBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    adSize: AdSize = AdSize.BANNER,
    collapsibleType: String? = null
) {
    // Guard: skip rendering entirely when ads are disabled or consent is not granted.
    val context = LocalContext.current
    if (KiroSdk.isAdsDisabled || !KiroConsentManager.canRequestAds(context)) {
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(adSize)
                setAdUnitId(adUnitId)
                
                // Automatically track ad revenue impressions
                setOnPaidEventListener { adValue ->
                    KiroLogEventManager.logPaidAdImpression(ctx, adValue, adUnitId, "Banner")
                }
                
                // Automatically track ad clicks
                adListener = object : com.google.android.gms.ads.AdListener() {
                    override fun onAdClicked() {
                        KiroLogEventManager.logClickAdsEvent(adUnitId)
                    }
                }
                
                val adRequest = if (collapsibleType != null) {
                    val extras = android.os.Bundle().apply {
                        putString("collapsible", collapsibleType)
                    }
                    AdRequest.Builder()
                        .addNetworkExtrasBundle(com.google.ads.mediation.admob.AdMobAdapter::class.java, extras)
                        .build()
                } else {
                    AdRequest.Builder().build()
                }
                loadAd(adRequest)
            }
        },
        onRelease = { adView ->
            adView.destroy()
        }
    )
}
