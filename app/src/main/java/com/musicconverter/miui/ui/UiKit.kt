package com.musicconverter.miui.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object UiKit {
    val BG = Color.parseColor("#0B0D12")
    val SURFACE = Color.parseColor("#151821")
    val SURFACE_2 = Color.parseColor("#1D2230")
    val SURFACE_3 = Color.parseColor("#242A39")
    val TEXT = Color.parseColor("#F7F8FB")
    val TEXT_2 = Color.parseColor("#AAB1C2")
    val TEXT_3 = Color.parseColor("#747D92")
    val ACCENT = Color.parseColor("#8B5CF6")
    val ACCENT_2 = Color.parseColor("#6D5DFB")
    val SUCCESS = Color.parseColor("#43D19E")
    val BORDER = Color.parseColor("#2A3040")

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(context, strokeDp), strokeColor)
        }

    fun ripple(context: Context, color: Int, radiusDp: Int, rippleColor: Int = 0x33FFFFFF): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            rounded(color, radiusDp, context),
            null
        )

    fun text(context: Context, value: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun chip(context: Context, value: String): TextView =
        text(context, value, 12f, TEXT_2, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            background = rounded(SURFACE_3, 20, context, BORDER, 1)
        }

    fun sectionTitle(context: Context, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, 18f, TEXT, true))
            if (!subtitle.isNullOrBlank()) {
                addView(text(context, subtitle, 12.5f, TEXT_3).apply {
                    setPadding(0, dp(context, 5), 0, 0)
                })
            }
        }

    fun card(context: Context, radiusDp: Int = 24): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
            background = rounded(SURFACE, radiusDp, context, BORDER, 1)
        }

    fun actionTile(context: Context, icon: String, title: String, subtitle: String, primary: Boolean = false): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        isClickable = true
        isFocusable = true
        setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 15))
        minimumHeight = dp(context, 104)
        background = ripple(context, if (primary) ACCENT_2 else SURFACE_2, 22)

        addView(text(context, icon, 22f, if (primary) Color.WHITE else Color.parseColor("#CFC7FF"), true))
        addView(text(context, title, 15f, Color.WHITE, true).apply { setPadding(0, dp(context, 10), 0, 0) })
        addView(text(context, subtitle, 11.5f, if (primary) Color.parseColor("#E9E5FF") else TEXT_3).apply { setPadding(0, dp(context, 5), 0, 0) })
    }

    fun wideButton(context: Context, icon: String, title: String, primary: Boolean = false): TextView =
        text(context, "$icon   $title", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            minimumHeight = dp(context, 54)
            isClickable = true
            isFocusable = true
            background = ripple(context, if (primary) ACCENT_2 else SURFACE_2, 18)
        }

    fun navItem(context: Context, icon: String, title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, 18, context)
            addView(text(context, icon, 20f, TEXT_3, true).apply { gravity = Gravity.CENTER })
            addView(text(context, title, 11.5f, TEXT_3, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 3), 0, 0)
            })
        }

    fun setNavSelected(item: LinearLayout, selected: Boolean) {
        val context = item.context
        item.background = if (selected) rounded(SURFACE_3, 18, context) else rounded(Color.TRANSPARENT, 18, context)
        val color = if (selected) Color.parseColor("#D8D1FF") else TEXT_3
        for (i in 0 until item.childCount) {
            (item.getChildAt(i) as? TextView)?.setTextColor(color)
        }
    }

    fun infoRow(context: Context, icon: String, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val iconView = text(context, icon, 19f, Color.parseColor("#D0C8FF"), true).apply {
                gravity = Gravity.CENTER
                background = rounded(SURFACE_3, 14, context)
            }
            addView(iconView, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, 0, 0)
                addView(text(context, title, 13.5f, TEXT, true))
                addView(text(context, subtitle, 11.5f, TEXT_3).apply { setPadding(0, dp(context, 4), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    fun spacer(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }

    fun margins(view: View, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        val context = view.context
        val p = (view.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom))
        view.layoutParams = p
    }
}
