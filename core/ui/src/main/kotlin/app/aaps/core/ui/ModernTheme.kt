package app.aaps.core.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import app.aaps.core.ui.elements.SingleClickButton
import com.google.android.material.button.MaterialButton

/**
 * Personal-fork: optional "new design" (dashboard-like colors, rounded non-caps buttons).
 * Purely visual: an overlay on top of AppTheme for every activity, nothing else changes.
 */
object ModernTheme {

    @Volatile var enabled = false

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (enabled) activity.theme.applyStyle(R.style.ThemeOverlay_Aaps_Modern, true)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** For contexts wrapped with AppTheme again (AlertDialogHelper), which would drop the activity overlay. */
    fun applyTo(context: Context) {
        if (enabled) context.theme.applyStyle(R.style.ThemeOverlay_Aaps_Modern, true)
    }
}

/**
 * Set by ThemeOverlay.Aaps.Modern (viewInflaterClass): restyles the classic gray and OK/Cancel buttons.
 * Must create exactly the same view classes as the classic (AppCompat) inflater, otherwise saved view state
 * does not match after switching the design and the activity crashes on restore.
 */
@Suppress("unused")
class ModernViewInflater : AppCompatViewInflater() {

    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton =
        super.createButton(context, attrs).also { restyle(it, context, attrs) }

    // teal when checked, like the dashboard mockup (the classic style tints checkboxes white)
    override fun createCheckBox(context: Context, attrs: AttributeSet): AppCompatCheckBox =
        super.createCheckBox(context, attrs).also {
            if (attrs.styleAttribute == R.style.Widget_App_CheckBox) {
                val accent = ContextCompat.getColor(context, R.color.modern_accent)
                val sub = ContextCompat.getColor(context, R.color.modern_sub)
                it.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf((sub and 0x00FFFFFF) or 0x61000000, accent, sub)
                )
            }
        }

    // most AAPS buttons are SingleClickButton or a fully qualified MaterialButton, which AppCompat would otherwise leave to the plain LayoutInflater
    override fun createView(context: Context, name: String, attrs: AttributeSet): View? =
        when (name) {
            SingleClickButton::class.java.name -> SingleClickButton(context, attrs).also { restyle(it, context, attrs) }
            MaterialButton::class.java.name    -> MaterialButton(context, attrs).also { restyle(it, context, attrs) }
            // root of the XML dialogs (StyleDialog): lighter popup with a border so it stands out from the screen behind
            "ScrollView", "LinearLayout"       ->
                if (attrs.styleAttribute == R.style.StyleDialog)
                    (if (name == "ScrollView") ScrollView(context, attrs) else LinearLayout(context, attrs)).also { setDialogBackground(it, context) }
                else super.createView(context, name, attrs)
            else                               -> super.createView(context, name, attrs)
        }

    private fun restyle(button: AppCompatButton, context: Context, attrs: AttributeSet) {
        val dp = context.resources.displayMetrics.density
        val accent = ContextCompat.getColor(context, R.color.modern_accent)
        val card = ContextCompat.getColor(context, if (isDialog(context)) R.color.modern_dialog_control else R.color.modern_card2)
        val line = ContextCompat.getColor(context, R.color.modern_line)
        when {
            attrs.styleAttribute == R.style.OkCancelButton_Text -> {
                val primary = button.id == R.id.ok
                setRoundedBackground(button, dp, if (primary) accent else card, if (primary) null else line, insetDp = 4)
                button.setTextColor(enabledStates(ContextCompat.getColor(context, if (primary) R.color.modern_on_accent else R.color.modern_text)))
                button.setTypeface(button.typeface, Typeface.BOLD)
                button.minWidth = (88 * dp).toInt()
                button.setPadding((16 * dp).toInt(), button.paddingTop, (16 * dp).toInt(), button.paddingBottom)
                button.isAllCaps = false
            }

            // GrayButton family (GrayButton, ButtonSmall/MediumFontStyle, customBtnStyle)
            attrs.styleAttribute in grayStyles -> {
                if (button is MaterialButton) {
                    button.backgroundTintList = enabledStates(card)
                    button.strokeColor = ColorStateList.valueOf(line)
                    button.strokeWidth = (1 * dp).toInt()
                    button.cornerRadius = (8 * dp).toInt()
                } else setRoundedBackground(button, dp, card, line, insetDp = 0)
                button.isAllCaps = false
                button.letterSpacing = 0f
            }
        }
    }

    private fun setDialogBackground(view: View, context: Context) {
        val dp = context.resources.displayMetrics.density
        val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(ContextCompat.getColor(context, R.color.modern_dialog))
            setStroke((1 * dp).toInt(), ContextCompat.getColor(context, R.color.modern_dialog_line))
        }
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    private fun isDialog(context: Context): Boolean {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating))
        return a.getBoolean(0, false).also { a.recycle() }
    }

    private fun setRoundedBackground(button: AppCompatButton, dp: Float, fill: Int, stroke: Int?, insetDp: Int) {
        val shape = GradientDrawable().apply {
            cornerRadius = 8 * dp
            color = enabledStates(fill)
            stroke?.let { setStroke((1 * dp).toInt(), it) }
        }
        button.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), InsetDrawable(shape, 0, (insetDp * dp).toInt(), 0, (insetDp * dp).toInt()), null)
        // buttons would tint the custom background with their gray / colorPrimary tint
        button.backgroundTintList = null
    }

    private val grayStyles = setOf(R.style.GrayButton, R.style.ButtonSmallFontStyle, R.style.ButtonMediumFontStyle)

    private fun enabledStates(color: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf((color and 0x00FFFFFF) or 0x61000000, color)
    )
}
