package com.kiro.sdk.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.kiro.sdk.event.KiroLogEventManager

/**
 * A Composable that displays an AdMob Banner Ad.
 * Can be used directly in any Jetpack Compose screen.
 *
 * @param adUnitId The AdMob Ad Unit ID.
 * @param modifier Modifier for custom layout configuration.
 * @param adSize The Banner Ad size (defaults to BANNER).
 */
@Composable
fun KiroBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    adSize: AdSize = AdSize.BANNER
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AdView(context).apply {
                setAdSize(adSize)
                setAdUnitId(adUnitId)
                
                // Automatically track ad revenue impressions
                setOnPaidEventListener { adValue ->
                    KiroLogEventManager.logPaidAdImpression(context, adValue, adUnitId, "Banner")
                }
                
                // Automatically track ad clicks
                adListener = object : com.google.android.gms.ads.AdListener() {
                    override fun onAdClicked() {
                        KiroLogEventManager.logClickAdsEvent(adUnitId)
                    }
                }
                
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { adView ->
            adView.destroy()
        }
    )
}
