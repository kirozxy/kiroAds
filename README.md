# kiroAds SDK

[![](https://jitpack.io/v/kirozxy/kiroAds.svg)](https://jitpack.io/#kirozxy/kiroAds)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

`kiroAds` is a unified Android Library (SDK) designed to simplify the integration of **Ads (AdMob)**, **Tracking (Firebase Analytics)**, and **In-App Purchases (Google Play Billing)** into your Android applications with minimal code configuration.

---

## Requirements

- **Android Studio** Flamingo (2022.2.1) or newer — ships with the required JDK 17
- **Android Gradle Plugin** 8.0+
- **Kotlin** 1.9+
- **minSdk** 21 (Android 5.0 Lollipop) or higher

> [!NOTE]
> Your app code can still target Java 8 / 11 / 17 in `compileOptions` — the requirements above only apply to your build tooling, not your app's source compatibility.

---

## Getting Started

Follow these steps to integrate the library using JitPack:

### Step 1: Add the JitPack repository
Add the JitPack repository to your root `settings.gradle.kts` file:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // <-- Add this line
    }
}
```

### Step 2: Add the dependency
Add the dependency to your app-level `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("com.github.kirozxy.kiroAds:kirosdk:1.0.2")
}
```

### Step 3: Setup Facebook Credentials (If using Facebook Analytics)
Meta (Facebook) SDK requires your Facebook App ID and Client Token to be declared in your app's manifest and string resources for background initialization.

1. Add your credentials to your app-level `res/values/strings.xml`:
```xml
<resources>
    <string name="facebook_app_id">YOUR_FACEBOOK_APP_ID</string>
    <string name="facebook_client_token">YOUR_FACEBOOK_CLIENT_TOKEN</string>
</resources>
```

2. Add metadata tags inside the `<application>` element in your app-level `AndroidManifest.xml`:
```xml
<application>
    <meta-data 
        android:name="com.facebook.sdk.ApplicationId" 
        android:value="@string/facebook_app_id"/>
    <meta-data 
        android:name="com.facebook.sdk.ClientToken" 
        android:value="@string/facebook_client_token"/>
</application>
```

### Step 4: Configure your AdMob Application ID (Required for Ads)
The Google Mobile Ads SDK **requires** your AdMob App ID to be declared in your app's `AndroidManifest.xml`. **If you skip this step, your app will crash on launch.**

Add the following `meta-data` tag inside the `<application>` element of your app-level `AndroidManifest.xml`:
```xml
<application>
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY"/> <!-- Your real AdMob App ID -->
</application>
```

> [!NOTE]
> The App ID (`~`) is different from an Ad Unit ID (`/`). You can use Google's sample App ID `ca-app-pub-3940256099942544~3347511713` for local testing.

---

## 🛠️ Usage Instructions

### 1. SDK Initialization
Initialize `KiroSdk` once inside your `Application` class:

```kotlin
package com.your.app

import android.app.Application
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.ads.KiroAds
import com.kiro.sdk.tracking.KiroTracker
import com.kiro.sdk.billing.KiroBilling

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the SDK
        KiroSdk.init(
            context = this,
            config = KiroSdk.SdkConfig(
                isDebug = true, // Set to false when compiling for production
                enableAds = true,
                adConfig = KiroAds.Config(
                    // Register your physical/emulator device IDs here so that test ads
                    // are served during development (prevents AdMob policy violations).
                    // Find your device ID in Logcat after the first ad request.
                    testDeviceIds = listOf("YOUR_TEST_DEVICE_ID")
                ),
                trackingConfig = KiroTracker.Config(
                    enableFirebase = true,
                    appsFlyerDevKey = "YOUR_APPSFLYER_DEV_KEY", // Provide dev key to enable AppsFlyer
                    enableFacebook = true, // Set to true to enable Facebook AppEvents
                    adjustAppToken = "YOUR_ADJUST_APP_TOKEN",   // Provide token to enable Adjust
                    adjustSandbox = BuildConfig.DEBUG,          // sandbox env in debug, production in release
                    adjustEventTokens = AdjustEvents.tokens     // see "Adjust event tokens" section below
                ),
                billingConfig = KiroBilling.Config(
                    enableBilling = true,
                    // Declare your premium/remove-ads product/subscription IDs to restore state automatically (even after clearing data)
                    removeAdsProductIds = listOf("remove_ads_forever", "remove_ads_monthly")
                )
            )
        )
    }
}
```

> [!NOTE]
> **About Mobile Ads initialization:** `KiroSdk.init()` only initializes the Google Mobile Ads engine when ads are enabled, not disabled by purchase, **and** UMP consent already allows ad requests. If consent has not been gathered yet, initialization is automatically deferred and triggered for you once `gatherConsent()` completes (see the next section). You do not need to call `MobileAds.initialize()` yourself.

### 2. EU User Consent (GDPR Compliance)

If your app targets users in the European Union (EU) or European Economic Area (EEA), you must comply with the GDPR and Google's EU User Consent Policy. 

`KiroSdk` includes a built-in `consent` manager (`KiroConsentManager`) wrapping Google's User Messaging Platform (UMP) SDK.

#### Gather Consent on App Launch
Call `gatherConsent` in your launcher activity's `onCreate` before loading or requesting ads:

```kotlin
import com.kiro.sdk.KiroSdk
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Gather consent from EU users
        KiroSdk.consent.gatherConsent(activity = this) { error ->
            if (error != null) {
                Log.w("MyApp", "Consent gathering failed: ${error.message}")
            }
            
            // 2. Check if we can request ads after the flow completes
            if (KiroSdk.consent.canRequestAds(context = this)) {
                // Preload your ads safely
                lifecycleScope.launch {
                    KiroSdk.ads.loadInterstitial(context = this@MainActivity, "YOUR_AD_UNIT_ID")
                }
            }
        }
    }
}
```

#### Best Practice: Splash Screen Integration (Consent + loadAndShow Flow)
In a typical production application, you should handle consent gathering on your **Splash Screen**, then load and display a Splash Interstitial Ad immediately before navigating to the main screen:

```kotlin
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.kiro.sdk.KiroSdk

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // 1. First gather consent from the user
        KiroSdk.consent.gatherConsent(activity = this) { error ->
            if (error != null) {
                Log.w("SplashActivity", "Consent request failed: ${error.message}")
            }

            // 2. Check if we are allowed to request ads under GDPR rules
            if (KiroSdk.consent.canRequestAds(context = this)) {
                // 3. Load & show splash interstitial immediately
                KiroSdk.ads.loadAndShowInterstitial2F(
                    activity = this,
                    highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
                    lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID",
                    onAdDismissed = {
                        // Move to Main screen when ad is closed
                        navigateToMain()
                    }
                )
            } else {
                // GDPR rules do not allow ads (e.g. user rejected consent), skip ad
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
```

#### Show Privacy Settings Option
Google requires providing a way for users to change or revoke their consent choices at any time.

Check if privacy options are required (i.e. user is in EEA) and show a button in your settings screen:
```kotlin
if (KiroSdk.consent.isPrivacyOptionsRequired(context = this)) {
    btnPrivacySettings.visibility = View.VISIBLE
    btnPrivacySettings.setOnClickListener {
        KiroSdk.consent.showPrivacyOptionsForm(activity = this) { error ->
            // Flow completed (choices updated)
        }
    }
} else {
    btnPrivacySettings.visibility = View.GONE
}
```

#### Testing the Consent Form (Debug Only)
Outside the EU/EEA you normally won't see the GDPR form. To preview and re-trigger it during development, pass your test device's hashed ID and force the EEA geography:

```kotlin
KiroSdk.consent.gatherConsent(
    activity = this,
    testDeviceHashedIds = listOf("YOUR_TEST_DEVICE_HASHED_ID"),
    forceEeaForTesting = true // Behaves as if the device is in the EEA
) { error ->
    // Consent flow finished
}
```

Find your hashed device ID in Logcat after running `gatherConsent()` once (the UMP SDK logs it). To make the form appear again on the next run, reset the stored consent state:

```kotlin
// Debug only — never ship this call in production
KiroSdk.consent.resetConsent(context = this)
```

> [!WARNING]
> `testDeviceHashedIds`, `forceEeaForTesting`, and `resetConsent()` are for development/QA only. Remove them (or guard with `BuildConfig.DEBUG`) before shipping to production.

---

### 3. Using Ads

#### 💡 Best Practice: Managing Ad Unit IDs

To avoid typos and make your code easy to maintain, it is highly recommended to manage all Ad Unit IDs in a single Kotlin `object`. This structure also lets you easily switch between Google Test IDs (during local debugging) and Real Production IDs automatically:

```kotlin
package com.your.app.config

import com.your.app.BuildConfig

object AdUnitIds {
    // True during debug builds, false in production release builds
    private val isDebug = BuildConfig.DEBUG

    val BANNER_HOME = if (isDebug) {
        "ca-app-pub-3940256099942544/6300978111" // Google Test Banner ID
    } else {
        "ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY" // Real Production ID
    }

    val INTER_SPLASH_HIGH = if (isDebug) {
        "ca-app-pub-3940256099942544/1033173712" // Google Test Interstitial ID
    } else {
        "ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY" // Real Production ID
    }

    val NATIVE_DETAILS = if (isDebug) {
        "ca-app-pub-3940256099942544/2247696110" // Google Test Native ID
    } else {
        "ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY" // Real Production ID
    }
}
```

Now, instead of hardcoding raw strings, you can use:
```kotlin
KiroSdk.ads.loadInterstitial(context, AdUnitIds.INTER_SPLASH_HIGH)
```

---

---

#### Full-Screen Ad Cooldown (Interstitial + App Open)

To avoid annoying users with back-to-back full-screen ads, the SDK enforces a **global cooldown** between any two full-screen ads (Interstitial + App Open). Within the cooldown window, further show requests are silently skipped and their `onAdDismissed` callback fires immediately so navigation continues.

**Default: 30 seconds.** Rewarded ads are NOT subject to this cooldown — they are user-initiated and the user expects the reward.

Configure once in `SdkConfig`:
```kotlin
KiroSdk.init(
    context = this,
    config = KiroSdk.SdkConfig(
        adConfig = KiroAds.Config(
            testDeviceIds = listOf("..."),
            fullScreenAdCooldownMs = 30_000L  // 30s default; set 0 to disable
        )
    )
)
```

Or change at runtime:
```kotlin
KiroSdk.ads.setFullScreenAdCooldownMs(45_000L)   // 45s
KiroSdk.ads.setFullScreenAdCooldownMs(0L)        // disable cooldown entirely
KiroSdk.ads.resetFullScreenAdCooldown()          // forget last show -> next ad shows immediately
val current = KiroSdk.ads.getFullScreenAdCooldownMs()
```

> [!TIP]
> Use `resetFullScreenAdCooldown()` right after a paid Remove Ads upgrade is reverted (debug only) or to bypass cooldown for a special promotional moment. Avoid calling it routinely or it defeats the purpose.

#### Displaying a Banner Ad

##### Option A: For XML layouts (View-based)
Attach the Banner Ad directly to a container (e.g., a `FrameLayout`):

```kotlin
import com.kiro.sdk.KiroSdk
import com.google.android.gms.ads.AdSize

// Inside your Activity (Using View Binding)
val adContainer = binding.adContainer
KiroSdk.ads.showBanner(
    activity = this,
    container = adContainer,
    adUnitId = "ca-app-pub-3940256099942544/6300978111", // Google's test banner ad unit ID
    adSize = AdSize.BANNER
)
```

##### Option B: For Jetpack Compose
Render the banner using the Composable function provided by the SDK:

```kotlin
import com.kiro.sdk.ads.KiroBannerAd
import com.google.android.gms.ads.AdSize

@Composable
fun MyScreen() {
    Column {
        // Your UI content...
        
        // Show Banner Ad at the bottom of the page
        KiroBannerAd(
            adUnitId = "ca-app-pub-3940256099942544/6300978111", // Google's test banner ID
            adSize = AdSize.BANNER
        )
    }
}
```

#### Using Interstitial Ads

##### Option A: Manual preloading & displaying (Standard)
Preload the ad asynchronously using Kotlin coroutines and display it when ready:
```kotlin
import kotlinx.coroutines.launch

// 1. Preload the Interstitial Ad in a Coroutine Scope.
// Note: Checking the returned Boolean (val isLoaded) is optional.
// If you just want to preload silently in the background, you can simply call:
// KiroSdk.ads.loadInterstitial(context, "ca-app-pub-3940256099942544/1033173712")
lifecycleScope.launch {
    val isLoaded = KiroSdk.ads.loadInterstitial(context, "ca-app-pub-3940256099942544/1033173712")
    if (isLoaded) {
        // Optional: Perform actions knowing the ad is preloaded successfully
    }
}

// 2. Show the ad (e.g., when transitioning between screens)
KiroSdk.ads.showInterstitial(activity = this, adUnitId = "ca-app-pub-3940256099942544/1033173712") {
    // Callback executed when the ad is closed or if ad failed to show
    val intent = Intent(this, NextActivity::class.java)
    startActivity(intent)
}
```

##### Option B: Preload with 2-Floor Waterfall (eCPM Optimization)
Tries to load the High Floor Ad Unit first. If it fails, falls back to the Low Floor Ad Unit automatically:

1. **Preload the waterfall ads**:
```kotlin
lifecycleScope.launch {
    KiroSdk.ads.loadInterstitial2F(
        context = context,
        highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
        lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID"
    )
}
```

2. **Show the ad when ready**:
The SDK will automatically check which of the two floors loaded successfully and display it:
```kotlin
KiroSdk.ads.showInterstitial2F(
    activity = this,
    highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
    lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID"
) {
    // Callback executed when the ad is closed or if both failed to load/show
    val intent = Intent(this, NextActivity::class.java)
    startActivity(intent)
}
```

##### Option C: Auto Load and Show with 2-Floor Waterfall (Immediate request)
Starts loading (High -> Low Floor) while displaying a blocking fullscreen progress dialog. Shows the ad immediately upon load success, or bypasses immediately if both fail:
```kotlin
KiroSdk.ads.loadAndShowInterstitial2F(
    activity = this,
    highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
    lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID",
    onAdDismissed = {
        // Proceed to the next screen or action
        val intent = Intent(this, NextActivity::class.java)
        startActivity(intent)
    }
)
```

###### Customizing the Loading Dialog
Both `loadAndShowInterstitial2F` and `loadAndShowRewarded2F` accept an optional `KiroLoadingDialogConfig` so you can match your app's theme. There are three levels of customization, in order of priority:

```kotlin
import android.graphics.Color
import com.kiro.sdk.ads.KiroLoadingDialogConfig

// Level 1: tweak default fullscreen overlay (background, progress color, text)
// Defaults: white background, black text, amber yellow progress, "Loading ad..." caption.
val styled = KiroLoadingDialogConfig(
    backgroundColor = Color.parseColor("#CC101820"),
    progressColor = Color.parseColor("#FFD54F"),
    textColor = Color.WHITE,
    text = "Loading ad..."
)

// Level 2: provide your own layout XML — SDK inflates and shows it fullscreen
val customLayout = KiroLoadingDialogConfig(
    customLayoutResId = R.layout.my_loading_overlay
)

// Level 3: full override — return your own ready-to-show Dialog
val fullOverride = KiroLoadingDialogConfig(
    customDialog = { activity ->
        MyBrandedLoadingDialog(activity)
    }
)

KiroSdk.ads.loadAndShowInterstitial2F(
    activity = this,
    highAdUnitId = "...",
    lowAdUnitId = "...",
    loadingConfig = styled,
    onAdDismissed = { /* ... */ }
)
```

#### Using Rewarded Ads
Preload the Rewarded Ad and handle reward callbacks:

```kotlin
import kotlinx.coroutines.launch

// 1. Preload the Rewarded Ad.
// Note: Checking the returned Boolean (val isLoaded) is optional.
// If you just want to preload silently, simply call:
// KiroSdk.ads.loadRewarded(context, "ca-app-pub-3940256099942544/5224354917")
lifecycleScope.launch {
    val isLoaded = KiroSdk.ads.loadRewarded(context, "ca-app-pub-3940256099942544/5224354917")
    if (isLoaded) {
        // Optional: Handle actions knowing the ad loaded successfully
    }
}

// 2. Show the ad and reward the user
KiroSdk.ads.showRewarded(
    activity = this,
    adUnitId = "ca-app-pub-3940256099942544/5224354917",
    onUserEarnedReward = { amount, type ->
        // Grant rewards to the user here
        println("User earned: $amount $type")
    },
    onAdDismissed = {
        // Executed when the ad is dismissed
    }
)
```

##### 2-Floor Waterfall variants
Same eCPM optimization as interstitial — try the high floor first, fall back to the low floor:

```kotlin
// Manual preload + show
lifecycleScope.launch {
    KiroSdk.ads.loadRewarded2F(context, "HIGH_FLOOR_AD_UNIT_ID", "LOW_FLOOR_AD_UNIT_ID")
}

KiroSdk.ads.showRewarded2F(
    activity = this,
    highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
    lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID",
    onUserEarnedReward = { amount, type -> /* grant reward */ },
    onAdDismissed = { /* ... */ }
)

// Auto load and show with fullscreen loading dialog
KiroSdk.ads.loadAndShowRewarded2F(
    activity = this,
    highAdUnitId = "HIGH_FLOOR_AD_UNIT_ID",
    lowAdUnitId = "LOW_FLOOR_AD_UNIT_ID",
    onUserEarnedReward = { amount, type -> /* grant reward */ },
    onAdDismissed = {
        // Called when the ad is dismissed, or immediately if both floors fail to load.
    }
)
```

> [!NOTE]
> `loadAndShowRewarded2F` accepts the same `loadingConfig: KiroLoadingDialogConfig` parameter as the interstitial variant. See *Customizing the Loading Dialog* above.

#### Using Native Ads

The SDK provides a custom view `KiroNativeAdView` (which wraps Google's `NativeAdView`) to make displaying Native Ads as simple as defining them in XML and loading them with a single line of code.

##### Step 1: Declare `KiroNativeAdView` in XML

You can define `KiroNativeAdView` in your layout file. To specify a custom native ad layout, pass it to the `app:layout_native` attribute. If omitted, the SDK will automatically fallback to its default design template layout.

###### Option A: Using SDK Default Layout Template
```xml
<com.kiro.sdk.ads.KiroNativeAdView
    android:id="@+id/kiro_native_ad"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

###### Option B: Using Custom XML Layout
Define your own custom XML layout (e.g., `layout_my_custom_ad.xml`) and map it using `app:layout_native`:
```xml
<com.kiro.sdk.ads.KiroNativeAdView
    android:id="@+id/kiro_native_ad"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:layout_native="@layout/layout_my_custom_ad" />
```

> [!TIP]
> **Custom Layout Requirement:** When using your own custom XML layout with `KiroNativeAdView`, make sure to assign standard SDK resource IDs to your layout elements:
> - `@id/ad_headline` for the Title `TextView`
> - `@id/ad_body` for the Description `TextView`
> - `@id/ad_call_to_action` for the Call to Action `Button`
> - `@id/ad_app_icon` for the Icon `ImageView`
> - `@id/ad_media` for the Google `MediaView` (Image/Video container)

---

##### Step 2: Load and Display Native Ads

###### Option A: Auto-Load & Show (Recommended - Easiest)
Load and show the Native Ad dynamically inside the view with a single function call:

```kotlin
// Using View Binding
val nativeAdView = binding.kiroNativeAd

// Using standard ad unit ID
nativeAdView.loadAndShowAd("ca-app-pub-3940256099942544/2247696110") { success ->
    // Optional callback when loading completes
}

// Or using 2-floor waterfall priority (eCPM Optimization)
nativeAdView.loadAndShowAd2F("HIGH_FLOOR_AD_UNIT_ID", "LOW_FLOOR_AD_UNIT_ID")
```

###### Option B: Preload Asynchronously and Bind Later (Supports Multi-Screen Flow)

If you prefer to load the ad object in the background (e.g., in a splash screen, previous Activity, or a shared `ViewModel`) and display it later in another screen:

1. **Preload the Ad**:
```kotlin
import com.google.android.gms.ads.nativead.NativeAd
import kotlinx.coroutines.launch

// You can store this in a Singleton, Application class, or a shared ViewModel
var preloadedNativeAd: NativeAd? = null

// Load the ad in your first screen (Screen A)
lifecycleScope.launch {
    preloadedNativeAd = KiroSdk.ads.loadNativeAd(context, "ca-app-pub-3940256099942544/2247696110")
}
```

2. **Bind the Ad in another screen (Screen B)**:
```kotlin
// Retrieve and bind the preloaded ad inside Screen B's Activity/Fragment
val ad = preloadedNativeAd
if (ad != null) {
    val nativeAdView = binding.kiroNativeAd
    nativeAdView.setNativeAd(ad)
}
```

3. **Release Resources (Important)**:
To avoid memory leaks, always destroy the preloaded ad when the destination screen is destroyed:
```kotlin
override fun onDestroy() {
    preloadedNativeAd?.destroy()
    preloadedNativeAd = null
    super.onDestroy()
}
```

##### 💡 Best Practice: Preloading Multiple Ads (AdManager Template)

If your application has multiple screens that each preload different Ad Unit IDs, it is highly recommended to use a centralized thread-safe `AdManager` object using a `HashMap` cache structure:

```kotlin
import android.content.Context
import com.google.android.gms.ads.nativead.NativeAd
import com.kiro.sdk.KiroSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AdManager {
    // Thread-safe map storing loaded ads mapped by their Ad Unit ID
    private val preloadedAds = java.util.Collections.synchronizedMap(
        java.util.HashMap<String, NativeAd>()
    )

    /**
     * Start preloading a Native Ad in the background.
     */
    fun preloadNativeAd(context: Context, adUnitId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            // Prevent duplicate loads if already preloaded
            if (preloadedAds.containsKey(adUnitId)) return@launch

            val ad = KiroSdk.ads.loadNativeAd(context, adUnitId)
            if (ad != null) {
                preloadedAds[adUnitId] = ad
            }
        }
    }

    /**
     * Consume (retrieve and remove) the preloaded ad for binding.
     * Google policies forbid displaying the same NativeAd instance on multiple views simultaneously.
     */
    fun consumeNativeAd(adUnitId: String): NativeAd? {
        return preloadedAds.remove(adUnitId)
    }

    /**
     * Destroy a specific ad to avoid memory leaks.
     */
    fun destroyAd(adUnitId: String) {
        preloadedAds.remove(adUnitId)?.destroy()
    }

    /**
     * Clear all cached ads.
     */
    fun destroyAll() {
        synchronized(preloadedAds) {
            for (ad in preloadedAds.values) {
                ad.destroy()
            }
            preloadedAds.clear()
        }
    }
}
```

**Usage:**
- In `SplashActivity.onCreate`: `AdManager.preloadNativeAd(this, "HOME_AD_UNIT_ID")`
- In `HomeActivity.onCreate`:
  ```kotlin
  val ad = AdManager.consumeNativeAd("HOME_AD_UNIT_ID")
  if (ad != null) {
      binding.kiroNativeAd.setNativeAd(ad)
  }
  ```
- In `HomeActivity.onDestroy`: `AdManager.destroyAd("HOME_AD_UNIT_ID")`

###### Option C: Inside Jetpack Compose
To display native ads in Compose layouts, use the built-in `KiroNativeAd` composable. You can optionally specify a custom layout XML resource using the `layoutResId` parameter. If not provided, it falls back to the default SDK template layout.

> [!NOTE]
> **Custom rendering in Compose:** Google's `NativeAdView` requires registered Android `View` references (TextView, Button, ImageView, MediaView) for click attribution and impression tracking, so a fully Compose-only layout is not supported. The recommended pattern is to design your own XML layout (with the standard IDs `@id/ad_headline`, `@id/ad_body`, `@id/ad_call_to_action`, `@id/ad_app_icon`, `@id/ad_media`) and reference it through `layoutResId`. The SDK takes care of binding and tracking; you keep full visual control through standard XML / Material theming.

```kotlin
import com.kiro.sdk.ads.KiroNativeAd

@Composable
fun MyScreen() {
    Column {
        // ...
        
        loadedNativeAd?.let { nativeAd ->
            // Option C1: Using SDK Default Layout Template
            KiroNativeAd(
                nativeAd = nativeAd,
                modifier = Modifier.fillMaxWidth().height(250.dp)
            )

            // Option C2: Using Custom XML Layout (e.g., layout_my_custom_ad.xml)
            KiroNativeAd(
                nativeAd = nativeAd,
                layoutResId = R.layout.layout_my_custom_ad,
                modifier = Modifier.fillMaxWidth().height(250.dp)
            )
        }
    }
}
```

###### Option D: Manual Layout Inflation & Binding (Advanced)
If you want to manually inflate custom layouts without using `KiroNativeAdView` container:

```kotlin
// 1. Inflate your custom layout where the root is com.google.android.gms.ads.nativead.NativeAdView
val adBinding = LayoutMyCustomNativeAdBinding.inflate(layoutInflater)
val adView = adBinding.root // This is NativeAdView

// 2. Map standard IDs or assign manually
adView.headlineView = adBinding.tvMyCustomTitle
adView.bodyView = adBinding.tvMyCustomDesc
adView.callToActionView = adBinding.btnMyCustomAction
adView.iconView = adBinding.imgMyCustomIcon
adView.mediaView = adBinding.myCustomMediaView

// 3. Assign data manually and bind to Google NativeAd
val ad = loadedNativeAd
if (ad != null) {
    adBinding.tvMyCustomTitle.text = ad.headline
    adBinding.tvMyCustomDesc.text = ad.body
    adBinding.btnMyCustomAction.text = ad.callToAction
    ad.icon?.let { adBinding.imgMyCustomIcon.setImageDrawable(it.drawable) }

    adView.setNativeAd(ad)
}

// 4. Add to container
binding.adContainer.removeAllViews()
binding.adContainer.addView(adView)
```

#### Native Ads in Lists (LazyColumn / RecyclerView)

The SDK lets you reuse a `NativeAd` across list-item recycling without ever extending the SDK. The key is **ownership**:

- `KiroNativeAdView.setNativeAd(ad)` — you load and own the ad. The view never destroys it on detach. Ideal for items in `LazyColumn`, `RecyclerView`, or shared across screens.
- `KiroNativeAdView.loadAndShowAd*` — the SDK loads and owns the ad. The view destroys it on detach (1-shot screens).

Always destroy externally-loaded ads yourself — typically in `ViewModel.onCleared()`.

##### Compose — LazyColumn
```kotlin
class FeedViewModel : ViewModel() {
    private val _ads = MutableStateFlow<List<NativeAd>>(emptyList())
    val ads = _ads.asStateFlow()

    fun preload(context: Context, slots: Int = 3, adUnitId: String) {
        viewModelScope.launch {
            val loaded = (0 until slots).mapNotNull {
                KiroSdk.ads.loadNativeAd(context, adUnitId)
            }
            _ads.value = loaded
        }
    }

    override fun onCleared() {
        super.onCleared()
        _ads.value.forEach { it.destroy() }   // we own the ads
    }
}

@Composable
fun Feed(items: List<Post>, ads: List<NativeAd>) {
    LazyColumn {
        itemsIndexed(items) { index, post ->
            PostItem(post)
            if (index % 5 == 4) {
                ads.getOrNull(index / 5)?.let { ad ->
                    KiroNativeAd(
                        nativeAd = ad,
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    )
                }
            }
        }
    }
}
```

##### XML — RecyclerView
```kotlin
class FeedAdapter(
    private val items: List<Item>,
    private val ads: List<NativeAd>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(p: Int) = if (p % 5 == 4) TYPE_AD else TYPE_CONTENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        TYPE_AD -> AdVH(KiroNativeAdView(parent.context, R.layout.item_native_ad))
        else -> ContentVH(LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, p: Int) {
        if (holder is AdVH) ads[p / 5].let(holder.adView::setNativeAd)
    }

    override fun getItemCount() = items.size
    private class AdVH(val adView: KiroNativeAdView) : RecyclerView.ViewHolder(adView)

    companion object { private const val TYPE_AD = 1; private const val TYPE_CONTENT = 0 }
}
```

> [!IMPORTANT]
> Per Google policy, **the same `NativeAd` instance must not be displayed in two views at the same time**. Preload as many ads as concurrent slots you expect, or load on demand per slot.

#### Picture-in-Picture (PiP) Mode Handling

Google AdMob policies strictly forbid displaying ads inside a floating Picture-in-Picture (PiP) window due to small screen dimensions and clickability rules. The SDK automates ad hiding and restoration to keep your application compliant.

##### Automatic Detection (ComponentActivity)

If your Activity extends `androidx.activity.ComponentActivity` (which is standard for all Jetpack Compose and `AppCompatActivity` screens), the SDK will **automatically** hook into the Activity's PiP changes.

When the Activity enters PiP:
- All active Native Ads (`KiroNativeAdView`) belonging to that Activity will automatically collapse (`GONE`).
- All active Banner Containers belonging to that Activity will automatically collapse (`GONE`).
- Full-screen ads (Interstitial, Rewarded) will be blocked from displaying if called on that Activity.

When the Activity exits PiP:
- Only views that were visible before entering PiP and have loaded ads will automatically restore to `VISIBLE`.

##### Manual Trigger (Optional)

If you are using legacy Activities that do not extend `ComponentActivity`, you can trigger the PiP change listener manually inside your Activity:

```kotlin
override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    
    // Notify the SDK to collapse/restore ads for this activity
    KiroSdk.ads.onPictureInPictureModeChanged(activity = this, isInPictureInPictureMode = isInPictureInPictureMode)
}
```

##### Coexistence with Other Activities

The PiP detection is activity-scoped. If a video activity enters PiP mode, ads on that specific activity will be hidden. However, any other activities running on the main screen (e.g., your `MainActivity` underneath the floating PiP window) can still load and show ads (including interstitials) normally.

---

#### Using App Open Ads

App Open ads are full-screen ads that appear when the app comes to the foreground (cold start or warm resume). The SDK supports two integration modes:

##### Mode A: Auto-managed (Recommended)
The SDK preloads on cold start, listens to `ProcessLifecycleOwner` for foreground events, and automatically shows the ad when appropriate. Cold-start show is skipped by default to avoid clashing with your splash flow.

Pass a **blocklist** of Activity classes where auto-show should be suppressed (typically your splash, onboarding, paywall, settings):
```kotlin
// Application.onCreate(), AFTER KiroSdk.init(...)
KiroSdk.ads.appOpen.start(
    application = this,
    adUnitId = "ca-app-pub-3940256099942544/9257395921", // Google's App Open test ID
    skipFirstLaunch = true,
    blockedActivities = setOf(
        SplashActivity::class.java,
        OnboardingActivity::class.java,
        PremiumPurchaseActivity::class.java
    )
)
```

You can also adjust the blocklist at runtime:
```kotlin
KiroSdk.ads.appOpen.addBlockedActivity(NewSensitiveScreen::class.java)
KiroSdk.ads.appOpen.removeBlockedActivity(OnboardingActivity::class.java)
KiroSdk.ads.appOpen.setBlockedActivities(setOf(/* full replace */))
```

For one-off suppression (e.g. you're about to show your own splash interstitial and don't want App Open to compete on this single foreground event):
```kotlin
KiroSdk.ads.appOpen.disableNextShow()
```

`KiroSdk.release()` automatically calls `appOpen.stop()` for you.

##### Mode B: Manual control
If you want full control over when the ad shows (e.g. only on specific screens), use the suspend API:
```kotlin
lifecycleScope.launch {
    val isLoaded = KiroSdk.ads.loadAppOpen(context, "AD_UNIT_ID")
    if (isLoaded) {
        KiroSdk.ads.showAppOpen(activity = this@MyActivity, adUnitId = "AD_UNIT_ID") {
            // ad dismissed
        }
    }
}
```

##### Mode C: Splash (Show App Open instead of Interstitial)
If you prefer to gate your splash screen behind an App Open ad rather than an Interstitial, use `loadAndShowAppOpen`. It mirrors `loadAndShowInterstitial2F`: shows a fullscreen loading dialog, waits up to `timeoutMs`, shows the ad on success, calls `onAdDismissed` on failure/timeout/dismiss.

```kotlin
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Gather consent first, then show App Open as the splash gate.
        KiroSdk.consent.gatherConsent(this) { _ ->
            if (KiroSdk.consent.canRequestAds(this)) {
                KiroSdk.ads.loadAndShowAppOpen(
                    activity = this,
                    adUnitId = "ca-app-pub-3940256099942544/9257395921",
                    timeoutMs = 5000L,
                    onAdDismissed = { goToMain() }
                )
            } else {
                goToMain()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
```

> [!NOTE]
> If you use Mode C **and** Mode A together, add your splash Activity to the auto-managed blocklist so the same foreground event doesn't trigger two App Open shows:
> ```kotlin
> KiroSdk.ads.appOpen.start(
>     application = this,
>     adUnitId = "...",
>     blockedActivities = setOf(SplashActivity::class.java)
> )
> ```

> [!IMPORTANT]
> Per Google guidance, App Open ads expire **4 hours** after load. The auto-managed mode handles this transparently. In manual mode, reload after expiration.
> The SDK also automatically blocks App Open shows when ads are disabled, when the user is in PiP mode, or when consent has not been gathered.

---

### 4. Using Tracking (Analytics)

Log standard events and screen views:

```kotlin
import android.os.Bundle
import com.kiro.sdk.KiroSdk

// Log a simple custom event to ALL active trackers
KiroSdk.tracker.logEvent("click_button_premium")

// Log an event with properties to ALL active trackers
val bundle = Bundle().apply {
    putString("item_id", "pro_version_monthly")
    putDouble("price", 4.99)
}
KiroSdk.tracker.logEvent("select_promotion", bundle)

// Log a screen view event (Firebase specific under the hood)
KiroSdk.tracker.logScreenView(screenName = "HomeFragment", screenClass = "MainActivity")

// Log ONLY to Facebook AppEvents
KiroSdk.tracker.logFacebookEvent("facebook_only_event")

// Log ONLY to AppsFlyer
KiroSdk.tracker.logAppsFlyerEvent("appsflyer_only_event")

// Log ONLY to Firebase
KiroSdk.tracker.logFirebaseEvent("firebase_only_event")

// Log ONLY to Adjust (requires a token mapped in Config.adjustEventTokens)
KiroSdk.tracker.logAdjustEvent("purchase", Bundle().apply {
    putDouble("value", 4.99)
    putString("currency", "USD")
})

// Custom combination: Log to Firebase and AppsFlyer but NOT Facebook
import com.kiro.sdk.tracking.TrackerPlatform
KiroSdk.tracker.logEvent(
    eventName = "custom_log_event",
    params = bundle,
    platforms = setOf(TrackerPlatform.FIREBASE, TrackerPlatform.APPSFLYER)
)

// Set user ID/Customer ID across Firebase, AppsFlyer, Facebook, and Adjust
KiroSdk.tracker.setUserId("user_123456")
```

#### Adjust event tokens
Adjust does not accept event names directly. You must create event tokens on the Adjust dashboard, then map them in the SDK config.

##### 💡 Best Practice: Centralize events in a single object
Mirror the [AdUnitIds pattern](#-best-practice-managing-ad-unit-ids) so event names and Adjust tokens live in one source of truth. This avoids typos and makes refactors safe across the codebase:

```kotlin
package com.your.app.config

object AdjustEvents {
    // Event names — used everywhere in the app code
    const val PURCHASE          = "purchase"
    const val TUTORIAL_COMPLETE = "tutorial_complete"
    const val LEVEL_UP          = "level_up"

    // Event tokens — copy-paste from your Adjust dashboard
    val tokens: Map<String, String> = mapOf(
        PURCHASE          to "evt001",
        TUTORIAL_COMPLETE to "evt002",
        LEVEL_UP          to "evt003"
    )
}
```

Then plug it into the SDK config and use the constants when logging:
```kotlin
// Application.onCreate()
trackingConfig = KiroTracker.Config(
    adjustAppToken = "YOUR_ADJUST_APP_TOKEN",
    adjustSandbox = BuildConfig.DEBUG,
    adjustEventTokens = AdjustEvents.tokens
)

// Anywhere in your app
KiroSdk.tracker.logEvent(AdjustEvents.PURCHASE, params)
```

When you call `logEvent(AdjustEvents.PURCHASE, ...)`, the SDK looks up `"evt001"` and forwards the event to Adjust. Events without a mapping are silently skipped for Adjust (they still go to Firebase / AppsFlyer / Facebook as usual).

Standard `value`/`currency` parameters are automatically forwarded as Adjust revenue:
```kotlin
val params = Bundle().apply {
    putDouble("value", 9.99)
    putString("currency", "USD")
}
KiroSdk.tracker.logEvent(AdjustEvents.PURCHASE, params)   // Adjust setRevenue(9.99, "USD")
```

> [!NOTE]
> All other parameters become Adjust callback parameters and appear in the Adjust dashboard against the event.

#### Adjust ad revenue (automatic)
For paid ad impressions, the SDK additionally calls Adjust's dedicated `Adjust.trackAdRevenue` API on every impression. This is **automatic and requires no event token** — it surfaces in Adjust's "Ad Revenue" dashboard with eCPM / ARPDAU rollups.

The default ad source is `"admob_sdk"`. If you mediate through other networks, override per-call via `KiroSdk.tracker.logAdRevenue(...)`:
```kotlin
import com.adjust.sdk.AdjustConfig

KiroSdk.tracker.logAdRevenue(
    revenueUsd = 0.0023,
    currency = "USD",
    adUnitId = "ca-app-pub-...",
    adFormat = "Banner",
    adNetworkSource = AdjustConfig.AD_REVENUE_APPLOVIN_MAX  // or another supported source
)
```

> [!TIP]
> Auto ad revenue and the standard `paid_ad_impression*` events run in parallel:
> - **`Adjust.trackAdRevenue`** — for the Adjust Ad Revenue dashboard (no token).
> - **`logEvent("paid_ad_impression", ...)`** — for cross-platform reporting (Firebase / AppsFlyer / Facebook / Adjust). Map a token in `adjustEventTokens` if you also want this on Adjust's Events dashboard.

#### What ad-related events are auto-tracked?

The SDK fires these events automatically for every ad load/show/click (Banner, Interstitial, Rewarded, Native, App Open). You do not need to call them manually.

| Event | When | Goes to |
|---|---|---|
| `ad_impression` | Every paid impression | Firebase only (Firebase standard) |
| `paid_ad_impression` | Every paid impression | Firebase + AppsFlyer + Facebook + Adjust* |
| `paid_ad_impression_value` | Every paid impression | All 4 platforms* |
| `paid_ad_impression_value_001` | Total revenue ≥ $0.01 | All 4 platforms* |
| `event_total_revenue_ad_in_3_days` | Day 3 cohort | All 4 platforms* |
| `event_total_revenue_ad_in_7_days` | Day 7 cohort | All 4 platforms* |
| `event_user_click_ads` | Every ad click | All 4 platforms* |
| (ad revenue) | Every paid impression | Adjust **Ad Revenue** dashboard via `trackAdRevenue` (no token needed) |

*For Adjust, only events with a matching entry in `adjustEventTokens` reach the dashboard. Map only the events you care about — the rest are silently skipped.

Recommended minimal mapping for Adjust:
```kotlin
object AdjustEvents {
    const val USER_CLICK_ADS = "event_user_click_ads"
    const val REVENUE_001    = "paid_ad_impression_value_001"

    val tokens = mapOf(
        USER_CLICK_ADS to "abc123",   // copy from Adjust dashboard
        REVENUE_001    to "abc124"
    )
}
```

---

### 5. Using In-App Purchases (Google Play Billing)

`KiroBilling` offers simple API flows backed by Kotlin coroutines and Flows to monitor subscription/purchase statuses smoothly:

#### Observe purchase transactions (Activity or ViewModel)
```kotlin
import androidx.lifecycle.lifecycleScope
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.billing.KiroBilling
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

lifecycleScope.launch {
    KiroSdk.billing.purchaseEvents.collect { result ->
        when (result) {
            is KiroBilling.PurchaseResult.Success -> {
                val purchase = result.purchase
                
                // 1. Unlock premium/purchased features here
                println("Purchase successful: ${purchase.orderId}")
                
                // 2. If the user purchased the "Remove Ads" product, immediately disable ads:
                if (purchase.products.contains("remove_ads_product_id")) {
                    KiroSdk.disableAds()
                }
            }
            is KiroBilling.PurchaseResult.Cancelled -> {
                // User cancelled the billing flow
            }
            is KiroBilling.PurchaseResult.Error -> {
                // Handle purchase errors
                println("Purchase failed: ${result.message} (Error code: ${result.code})")
            }
        }
    }
}
```

#### Query products and trigger checkout flows
```kotlin
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.launch

lifecycleScope.launch {
    // 1. Fetch available products from the Google Play Store
    val productIds = listOf("premium_monthly", "premium_yearly")
    val productDetailsList = KiroSdk.billing.queryProductDetails(
        productIds = productIds,
        productType = BillingClient.ProductType.SUBS // SUBS for subscriptions, INAPP for one-time purchases
    )
    
    // 2. Launch the Play Store checkout screen
    val premiumMonthlyDetails = productDetailsList?.find { it.productId == "premium_monthly" }
    if (premiumMonthlyDetails != null) {
        KiroSdk.billing.launchPurchaseFlow(activity = this@MainActivity, productDetails = premiumMonthlyDetails)
    }
}
```

#### Disabling Ads (Remove Ads feature)

The SDK supports hiding and disabling all ads dynamically (e.g., after a successful "Remove Ads" in-app purchase). When ads are disabled, all future ad loading requests are skipped, and all active ad layouts (including `KiroNativeAdView` and banner containers) will automatically set their visibility to `GONE`.

```kotlin
// Disable all ads immediately (saves preference state automatically to SharedPreferences)
KiroSdk.disableAds()

// Or set ads enabled/disabled state programmatically:
KiroSdk.setAdsDisabled(true) // disables ads
KiroSdk.setAdsDisabled(false) // enables ads (useful for testing or debugging)

// Check current ads status
val isAdsDisabled = KiroSdk.isAdsDisabled
```

#### Query active purchases (Restore premium features)

To restore or check any other premium VIP features owned by the user (even after clearing application local data), you can query Google Play's active purchases at any time using the `queryActivePurchases()` suspending function:

```kotlin
import kotlinx.coroutines.launch

lifecycleScope.launch {
    val activePurchases = KiroSdk.billing.queryActivePurchases()
    if (activePurchases != null) {
        // Check if user owns the VIP premium product
        val isVip = activePurchases.any { purchase ->
            purchase.products.contains("premium_vip_product_id")
        }
        if (isVip) {
            // Unlock your custom premium features in the app
            unlockVipFeature()
        }
    }
}
```

#### Releasing Resources (Optional)

`KiroSdk` keeps a billing connection alive for the lifetime of the app, which is the recommended setup for most apps. If you need to explicitly tear down the billing client and cancel its background work (e.g. in tests, or when fully shutting the SDK down), call:

```kotlin
KiroSdk.release()
```

After calling `release()`, you must call `KiroSdk.init(...)` again before using any SDK feature.

---

## ❓ Troubleshooting

#### The app crashes immediately on launch
Make sure you added your AdMob **Application ID** to the host app's `AndroidManifest.xml` (see *Getting Started → Step 4*). A missing or malformed `com.google.android.gms.ads.APPLICATION_ID` meta-data causes the Google Mobile Ads SDK to crash the app at startup.

#### Ads never load / `loadInterstitial` always returns `false`
Ad loading is intentionally skipped when **any** of the following is true:
- Ads are disabled (`KiroSdk.isAdsDisabled == true`, e.g. after a "Remove Ads" purchase).
- UMP consent has not been gathered yet, so `KiroSdk.consent.canRequestAds()` returns `false`.
- The host `Activity` is currently in Picture-in-Picture mode (for *show* calls).

Always call `gatherConsent()` once at launch and verify `canRequestAds()` returns `true` before expecting ads. See the *EU User Consent* section.

#### Nothing happens after calling `gatherConsent()` and no consent form appears
This is usually **expected behavior**, not a bug. The UMP consent form is shown based on your AdMob/Funding Choices configuration **and** the user's geographic region:
- Users in the **EU/EEA and UK** typically see the GDPR consent form.
- Users **outside** those regions usually see no form, and `canRequestAds()` returns `true` immediately, so ads load normally.

You should still call `gatherConsent()` for **every** user regardless of region, because the same build may be installed by an EU user. To preview the EU form while developing outside the EU, configure UMP debug settings (test device ID + `DebugGeography.DEBUG_GEOGRAPHY_EEA`) in the Google UMP SDK.

> [!NOTE]
> To force the EEA consent form for local testing from anywhere, pass test device IDs and `forceEeaForTesting = true` to `gatherConsent()` (see *Testing the consent form* below).

#### Real ads show up during development (risk of an AdMob ban)
Register your device as a test device via `KiroAds.Config(testDeviceIds = listOf("..."))` in `SdkConfig`. Run the app once, then copy the device ID printed in Logcat (look for a line like `Use RequestConfiguration.Builder().setTestDeviceIds(...)`) and add it to the list. Also prefer Google's sample Ad Unit IDs while debugging.

#### Banner / Native ad space is blank
- Confirm consent allows ads and that ads are not disabled.
- Native ads: make sure your custom layout uses the required IDs (`@id/ad_headline`, `@id/ad_body`, `@id/ad_call_to_action`, `@id/ad_app_icon`, `@id/ad_media`).
- Test ad fill is not guaranteed for every request; retry or use the 2-floor (`...2F`) variants.

#### Facebook events are not tracked
Set `enableFacebook = true` in `KiroTracker.Config` **and** declare the Facebook App ID + Client Token in your manifest and string resources (see *Getting Started → Step 3*).

#### "Remove Ads" state is lost after reinstall / clearing data
Declare your remove-ads product/subscription IDs in `KiroBilling.Config(removeAdsProductIds = listOf(...))`. On launch the SDK queries Google Play and automatically re-disables ads if an active matching purchase is found.
