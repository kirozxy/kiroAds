package com.kiro.sdk.util

import android.content.Context
import android.content.SharedPreferences

object KiroPreferenceUtils {
    private const val PREF_NAME = "kiro_sdk_pref"
    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_TOTAL_REVENUE = "KEY_TOTAL_REVENUE"
    private const val KEY_THRESHOLD_REVENUE = "KEY_THRESHOLD_REVENUE"
    private const val KEY_PUSHED_3_DAY = "KEY_PUSHED_3_DAY"
    private const val KEY_PUSHED_7_DAY = "KEY_PUSHED_7_DAY"
    private const val KEY_ADS_DISABLED = "KEY_ADS_DISABLED"

    private lateinit var preferences: SharedPreferences

    /**
     * Initializes SharedPreferences and sets the install time if it's the first run.
     */
    fun init(context: Context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (getInstallTime() == 0L) {
            preferences.edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply()
        }
    }

    /**
     * Gets the application install timestamp.
     */
    fun getInstallTime(): Long {
        return preferences.getLong(KEY_INSTALL_TIME, 0L)
    }

    /**
     * Gets total accumulated ad revenue in USD.
     */
    fun getTotalRevenue(): Float {
        return preferences.getFloat(KEY_TOTAL_REVENUE, 0f)
    }

    /**
     * Adds ad revenue to the total accumulated revenue pool.
     */
    @Synchronized
    fun addTotalRevenue(revenue: Float) {
        val current = getTotalRevenue()
        preferences.edit().putFloat(KEY_TOTAL_REVENUE, current + revenue).apply()
    }

    /**
     * Gets accumulated ad revenue towards the $0.01 threshold.
     */
    fun getThresholdRevenue(): Float {
        return preferences.getFloat(KEY_THRESHOLD_REVENUE, 0f)
    }

    /**
     * Adds ad revenue to the threshold pool.
     */
    @Synchronized
    fun addThresholdRevenue(revenue: Float) {
        val current = getThresholdRevenue()
        preferences.edit().putFloat(KEY_THRESHOLD_REVENUE, current + revenue).apply()
    }

    /**
     * Resets the threshold pool back to 0.
     */
    @Synchronized
    fun resetThresholdRevenue() {
        preferences.edit().putFloat(KEY_THRESHOLD_REVENUE, 0f).apply()
    }

    /**
     * Checks if the 3-day revenue cohort event was already pushed.
     */
    fun isPushed3Day(): Boolean {
        return preferences.getBoolean(KEY_PUSHED_3_DAY, false)
    }

    /**
     * Marks the 3-day revenue cohort event as pushed.
     */
    fun setPushed3Day(pushed: Boolean) {
        preferences.edit().putBoolean(KEY_PUSHED_3_DAY, pushed).apply()
    }

    /**
     * Checks if the 7-day revenue cohort event was already pushed.
     */
    fun isPushed7Day(): Boolean {
        return preferences.getBoolean(KEY_PUSHED_7_DAY, false)
    }

    /**
     * Marks the 7-day revenue cohort event as pushed.
     */
    fun setPushed7Day(pushed: Boolean) {
        preferences.edit().putBoolean(KEY_PUSHED_7_DAY, pushed).apply()
    }

    /**
     * Checks if ads have been disabled (e.g., via in-app purchase).
     */
    fun isAdsDisabled(): Boolean {
        return preferences.getBoolean(KEY_ADS_DISABLED, false)
    }

    /**
     * Sets whether ads are disabled (e.g., when a purchase is successful).
     */
    fun setAdsDisabled(disabled: Boolean) {
        preferences.edit().putBoolean(KEY_ADS_DISABLED, disabled).apply()
    }
}
