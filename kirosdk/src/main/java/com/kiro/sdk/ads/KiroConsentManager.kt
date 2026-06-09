package com.kiro.sdk.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.kiro.sdk.KiroSdk

object KiroConsentManager {

    /**
     * Checks if the user has completed the consent flow and ads can be requested.
     */
    fun canRequestAds(context: Context): Boolean {
        val consentInformation = UserMessagingPlatform.getConsentInformation(context)
        return consentInformation.canRequestAds()
    }

    /**
     * Checks if the app is required to show a privacy settings button.
     * True if the user is in the EU/EEA region and has completed consent.
     */
    fun isPrivacyOptionsRequired(context: Context): Boolean {
        val consentInformation = UserMessagingPlatform.getConsentInformation(context)
        return consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    /**
     * Automatically requests consent info updates and shows the form if required.
     * Call this at application launch (e.g., in your launcher activity's onCreate).
     *
     * @param activity The current activity.
     * @param testDeviceHashedIds For testing only. Hashed device IDs that should be treated as
     *   debug devices. The hashed ID is printed in Logcat by the UMP SDK on the first run.
     * @param forceEeaForTesting For testing only. When true (and at least one test device ID is
     *   provided), the consent flow behaves as if the device is located in the EEA, so you can
     *   preview the GDPR consent form from anywhere. Leave false for production.
     * @param onComplete Callback executed when the consent flow finishes or fails.
     */
    fun gatherConsent(
        activity: Activity,
        testDeviceHashedIds: List<String> = emptyList(),
        forceEeaForTesting: Boolean = false,
        onComplete: (error: Exception?) -> Unit
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()

        // Attach debug settings only when test device IDs are supplied (testing/QA scenarios).
        if (testDeviceHashedIds.isNotEmpty()) {
            val debugBuilder = ConsentDebugSettings.Builder(activity)
            testDeviceHashedIds.forEach { debugBuilder.addTestDeviceHashedId(it) }
            if (forceEeaForTesting) {
                debugBuilder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            }
            paramsBuilder.setConsentDebugSettings(debugBuilder.build())
        }

        val params = paramsBuilder.build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        onComplete(Exception(formError.message))
                    } else {
                        if (consentInformation.canRequestAds()) {
                            KiroSdk.initializeMobileAds(activity.applicationContext)
                        }
                        onComplete(null)
                    }
                }
            },
            { requestConsentError ->
                onComplete(Exception(requestConsentError.message))
            }
        )
    }

    /**
     * Resets the consent state. For testing only — lets you re-trigger the consent form on the
     * next gatherConsent() call without reinstalling the app. Do not ship calls to this in production.
     */
    fun resetConsent(context: Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
    }

    /**
     * Shows the privacy options form, allowing users to modify their consent choice.
     * Call this when the user clicks a "Privacy Settings" button in your app's settings menu.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onComplete: (error: Exception?) -> Unit
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                onComplete(Exception(formError.message))
            } else {
                onComplete(null)
            }
        }
    }
}
