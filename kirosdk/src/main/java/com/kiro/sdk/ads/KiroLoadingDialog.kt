package com.kiro.sdk.ads

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes

/**
 * Customization for the fullscreen loading dialog shown by `loadAndShow*` ad APIs.
 *
 * Three levels of customization, in order of priority:
 *  1. [customDialog] — full override. Provide your own ready-to-show Dialog. SDK only calls show()/dismiss().
 *  2. [customLayoutResId] — provide a layout resource. SDK inflates it and shows it fullscreen.
 *     SDK does not look up any specific IDs in your layout, so you fully control the visuals.
 *  3. Style fields ([backgroundColor], [progressColor], [textColor], [text]) — used when neither
 *     [customDialog] nor [customLayoutResId] is set. SDK builds a minimal centered ProgressBar +
 *     TextView fullscreen overlay using these values.
 */
data class KiroLoadingDialogConfig(
    @ColorInt val backgroundColor: Int = Color.WHITE,
    @ColorInt val progressColor: Int? = 0xFFFFC107.toInt(), // amber yellow, matches ad attribution badge
    @ColorInt val textColor: Int = Color.BLACK,
    val text: String? = "Loading ad...",                    // null hides the text
    @LayoutRes val customLayoutResId: Int? = null,
    val customDialog: ((Activity) -> Dialog)? = null,
)

/**
 * Builds a fullscreen loading dialog according to the supplied [config].
 * Internal helper used by `loadAndShowInterstitial2F` / `loadAndShowRewarded2F`.
 */
internal fun buildLoadingDialog(activity: Activity, config: KiroLoadingDialogConfig): Dialog {
    // Level 1 — full override
    config.customDialog?.let { return it(activity) }

    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)

    if (config.customLayoutResId != null) {
        // Level 2 — custom layout, dev controls the entire content
        dialog.setContentView(config.customLayoutResId)
    } else {
        // Level 3 — default programmatic layout with style fields
        dialog.setContentView(buildDefaultLoadingView(activity, config))
    }

    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setDimAmount(0f) // we control dimming via backgroundColor of the root view
        addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
    return dialog
}

private fun buildDefaultLoadingView(activity: Activity, config: KiroLoadingDialogConfig): View {
    val root = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(config.backgroundColor)
    }

    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    val progressBar = ProgressBar(activity).apply {
        isIndeterminate = true
        config.progressColor?.let {
            indeterminateTintList = ColorStateList.valueOf(it)
        }
    }
    container.addView(progressBar)

    if (!config.text.isNullOrEmpty()) {
        val textView = TextView(activity).apply {
            text = config.text
            setTextColor(config.textColor)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        container.addView(textView)
    }

    val centeredLp = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
    )
    root.addView(container, centeredLp)
    return root
}
