package com.kiro.sdk.ads

import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.NativeAd
import com.kiro.sdk.R

/**
 * A Composable that displays a NativeAd using a custom layout XML or the SDK's default layout template.
 *
 * @param nativeAd The loaded NativeAd object.
 * @param modifier Modifier for custom layout configuration.
 * @param layoutResId Custom XML layout resource ID. If not provided, falls back to the default SDK template layout.
 */
@Composable
fun KiroNativeAd(
    nativeAd: NativeAd,
    modifier: Modifier = Modifier,
    @LayoutRes layoutResId: Int? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val resId = layoutResId ?: R.layout.kiro_default_native_ad
            KiroNativeAdView(context, resId)
        },
        update = { adView ->
            adView.setNativeAd(nativeAd)
        }
    )
}
