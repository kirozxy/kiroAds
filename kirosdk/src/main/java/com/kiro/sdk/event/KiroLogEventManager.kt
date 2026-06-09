package com.kiro.sdk.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.android.gms.ads.AdValue
import com.google.firebase.analytics.FirebaseAnalytics
import com.kiro.sdk.KiroSdk
import com.kiro.sdk.util.KiroPreferenceUtils

object KiroLogEventManager {
    private const val TAG = "KiroLogEventManager"

    /**
     * Handles ad revenue impression tracking.
     * Calculates USD values, updates preferences, checks thresholds, and logs events.
     *
     * @param context Android context.
     * @param adValue AdMob AdValue object containing revenue details.
     * @param adUnitId The Ad Unit ID of the displayed ad.
     * @param adFormat The format of the ad (e.g. "Interstitial", "Rewarded", "Banner", "Native").
     */
    fun logPaidAdImpression(
        @Suppress("UNUSED_PARAMETER") context: Context,
        adValue: AdValue,
        adUnitId: String,
        adFormat: String
    ) {
        val revenueUsd = adValue.valueMicros / 1_000_000.0
        Log.d(TAG, "Ad Impression Revenue: $revenueUsd USD, Format: $adFormat, Unit: $adUnitId")

        // 1. Log official Google Analytics / Firebase ad_impression event
        // This is required for automatic integration with standard Firebase revenue dashboards.
        val firebaseParams = Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob")
            putString(FirebaseAnalytics.Param.AD_SOURCE, "AdMob")
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, adUnitId)
            putString(FirebaseAnalytics.Param.AD_FORMAT, adFormat)
            putDouble(FirebaseAnalytics.Param.VALUE, revenueUsd)
            putString(FirebaseAnalytics.Param.CURRENCY, adValue.currencyCode)
        }
        KiroSdk.tracker.logFirebaseEvent(FirebaseAnalytics.Event.AD_IMPRESSION, firebaseParams)

        // 2. Log detailed ad impression event (params in bundle)
        val params = Bundle().apply {
            putDouble("valuemicros", adValue.valueMicros.toDouble())
            putString("currency", adValue.currencyCode)
            putInt("precision", adValue.precisionType)
            putString("adunitid", adUnitId)
            putString("adformat", adFormat)
        }
        KiroSdk.tracker.logEvent("paid_ad_impression", params)

        // 3. Log raw ad impression value event
        val valueParams = Bundle().apply {
            putDouble("value", revenueUsd)
            putString("currency", adValue.currencyCode)
            putInt("precision", adValue.precisionType)
            putString("adunitid", adUnitId)
            putString("adformat", adFormat)
        }
        KiroSdk.tracker.logEvent("paid_ad_impression_value", valueParams)

        // 4. Update accumulated revenues in Shared Preferences
        KiroPreferenceUtils.addTotalRevenue(revenueUsd.toFloat())
        KiroPreferenceUtils.addThresholdRevenue(revenueUsd.toFloat())

        // 5. Check $0.01 threshold event
        val thresholdRevenue = KiroPreferenceUtils.getThresholdRevenue()
        if (thresholdRevenue >= 0.01f) {
            KiroPreferenceUtils.resetThresholdRevenue()
            val thresholdParams = Bundle().apply {
                putFloat("value", thresholdRevenue)
            }
            KiroSdk.tracker.logEvent("paid_ad_impression_value_001", thresholdParams)
        }

        // 6. Check cohort events (3-day and 7-day revenue logs)
        checkAndLogCohortEvents()
    }

    /**
     * Logs click events for ads.
     */
    fun logClickAdsEvent(adUnitId: String) {
        Log.d(TAG, "User clicked ad: $adUnitId")
        val bundle = Bundle().apply {
            putString("ad_unit_id", adUnitId)
        }
        KiroSdk.tracker.logEvent("event_user_click_ads", bundle)
    }

    private fun checkAndLogCohortEvents() {
        val installTime = KiroPreferenceUtils.getInstallTime()
        val currentTime = System.currentTimeMillis()
        val daysElapsed = (currentTime - installTime) / (24L * 60 * 60 * 1000)

        // Check 3-day cohort
        if (!KiroPreferenceUtils.isPushed3Day() && daysElapsed >= 3) {
            KiroPreferenceUtils.setPushed3Day(true)
            val bundle = Bundle().apply {
                putFloat("value", KiroPreferenceUtils.getTotalRevenue())
            }
            KiroSdk.tracker.logEvent("event_total_revenue_ad_in_3_days", bundle)
        }

        // Check 7-day cohort
        if (!KiroPreferenceUtils.isPushed7Day() && daysElapsed >= 7) {
            KiroPreferenceUtils.setPushed7Day(true)
            val bundle = Bundle().apply {
                putFloat("value", KiroPreferenceUtils.getTotalRevenue())
            }
            KiroSdk.tracker.logEvent("event_total_revenue_ad_in_7_days", bundle)
        }
    }
}
