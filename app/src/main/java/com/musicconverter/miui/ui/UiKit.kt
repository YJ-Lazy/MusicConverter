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
    var BG = Color.parseColor("#161A2B")
    var SURFACE = Color.parseColor("#20243A")
    var SURFACE_2 = Color.parseColor("#272B43")
    var SURFACE_3 = Color.parseColor("#303451")
    var TEXT = Color.parseColor("#EDF0FB")
    var TEXT_2 = Color.parseColor("#C8D1E5")
    var TEXT_3 = Color.parseColor("#ADB7D0")
    var ACCENT = Color.parseColor("#C3AEFF")
    var ACCENT_2 = Color.parseColor("#A995ED")
    var SUCCESS = Color.parseColor("#65D9AE")
    var BORDER = Color.parseColor("#373D59")

    fun applyTheme(context: Context) {
        if (ThemePreferences.isDark(context)) {
            BG = Color.parseColor("#161A2B")
            SURFACE = Color.parseColor("#20243A")
            SURFACE_2 = Color.parseColor("#272B43")
            SURFACE_3 = Color.parseColor("#303451")
            TEXT = Color.parseColor("#EDF0FB")
            TEXT_2 = Color.parseColor("#C8D1E5")
            TEXT_3 = Color.parseColor("#ADB7D0")
            ACCENT = Color.parseColor("#C3AEFF")
            ACCENT_2 = Color.parseColor("#A995ED")
            SUCCESS = Color.parseColor("#65D9AE")
            BORDER = Color.parseColor("#373D59")
        } else {
            BG = Color.parseColor("#F6F9FC")
            SURFACE = Color.parseColor("#FFFFFF")
            SURFACE_2 = Color.parseColor("#ECF3FA")
            SURFACE_3 = Color.parseColor("#E4EDF7")
            TEXT = Color.parseColor("#173047")
            TEXT_2 = Color.parseColor("#36536C")
            TEXT_3 = Color.parseColor("#526B83")
            ACCENT = Color.parseColor("#184FB9")
            ACCENT_2 = Color.parseColor("#406D9C")
            SUCCESS = Color.parseColor("#087A55")
            BORDER = Color.parseColor("#D5E2ED")
        }
    }

    fun prepareActivity(activity: android.app.Activity) {
        activity.setTheme(if (ThemePreferences.isDark(activity)) com.musicconverter.miui.R.style.AppTheme
            else com.musicconverter.miui.R.style.AppThemeLight)
        applyTheme(activity)
    }

    fun isDark(context: Context): Boolean = ThemePreferences.isDark(context)

    fun themedColor(context: Context, darkColor: String, lightColor: String): Int =
        Color.parseColor(if (isDark(context)) darkColor else lightColor)


    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(context, strokeDp), strokeColor)
        }

    fun ripple(context: Context, color: Int, radiusDp: Int, rippleColor: Int = if (isDark(context)) 0x33FFFFFF else 0x284C8FD8): RippleDrawable =
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
            background = rounded(SURFACE, radiusDp, context)
            elevation = dp(context, if (isDark(context)) 1 else 2).toFloat()
        }

    fun actionTile(
        context: Context,
        icon: String,
        title: String,
        subtitle: String,
        primary: Boolean = false
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        isClickable = true
        isFocusable = true
        setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 15))
        minimumHeight = dp(context, 104)
        background = ripple(context, if (primary) primaryFill(context) else SURFACE_2, 22)

        addView(text(context, icon, 22f, if (primary) primaryActionContent(context) else themedColor(context, "#CFC7FF", "#397CC3"), true))
        addView(text(context, title, 15f, if (primary) primaryActionContent(context) else TEXT, true).apply {
            setPadding(0, dp(context, 10), 0, 0)
        })
        addView(text(context, subtitle, 11.5f, if (primary) primaryActionSecondaryContent(context) else TEXT_3).apply {
            setPadding(0, dp(context, 5), 0, 0)
        })
    }

    fun wideButton(context: Context, icon: String, title: String, primary: Boolean = false): TextView =
        text(context, "$icon   $title", 15f, if (primary) primaryActionContent(context) else TEXT, true).apply {
            gravity = if (primary) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            minimumHeight = dp(context, 48)
            isClickable = true
            isFocusable = true
            background = ripple(context, if (primary) primaryFill(context) else SURFACE_2, 18)
        }


    fun navItem(context: Context, icon: String, title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = title
            addView(NavigationIcon(context, icon), LinearLayout.LayoutParams(dp(context, 52), dp(context, 29)))
            addView(text(context, title, 11f, TEXT_3, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 3), 0, 0)
            })
        }

    fun setNavSelected(item: LinearLayout, selected: Boolean) {
        val context = item.context
        val icon = item.getChildAt(0) as NavigationIcon
        icon.background = if (selected) rounded(primaryContainer(context), 16, context) else null
        icon.tint = if (selected) ACCENT else TEXT_3
        icon.invalidate()
        (item.getChildAt(1) as TextView).setTextColor(icon.tint)
        item.isSelected = selected
    }

    private class NavigationIcon(context: Context, private val type: String) : View(context) {
        var tint = TEXT_3
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = 1.6f
        }
        private val lines = android.graphics.Path()

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val dim = dp(context, 21).toFloat()
            canvas.save()
            canvas.translate((width - dim) / 2, (height - dim) / 2)
            canvas.scale(dim / 24, dim / 24)
            paint.color = tint
            lines.reset()
            fun stroke(vararg xy: Float) {
                lines.moveTo(xy[0], xy[1])
                for (i in 2 until xy.size step 2) lines.lineTo(xy[i], xy[i + 1])
            }
            when (type) {
                "music" -> {
                    stroke(9f, 16f, 9f, 6f, 20f, 4f, 20f, 14f)
                    canvas.drawOval(3f, 15f, 9f, 20f, paint)
                    canvas.drawOval(14f, 13f, 20f, 18f, paint)
                }
                "edit" -> {
                    stroke(3f, 10f, 3f, 14f)
                    stroke(7.5f, 7f, 7.5f, 17f)
                    stroke(12f, 4f, 12f, 20f)
                    stroke(16.5f, 7f, 16.5f, 17f)
                    stroke(21f, 10f, 21f, 14f)
                }
                "convert" -> {
                    stroke(12f, 3f, 12f, 15f)
                    stroke(7f, 10f, 12f, 15f, 17f, 10f)
                    stroke(4f, 16f, 4f, 20f, 20f, 20f, 20f, 16f)
                }
                else -> {
                    canvas.drawCircle(12f, 12f, 6f, paint)
                    canvas.drawCircle(12f, 12f, 2f, paint)
                    stroke(12f, 3f, 12f, 6f)
                    stroke(12f, 18f, 12f, 21f)
                    stroke(3f, 12f, 6f, 12f)
                    stroke(18f, 12f, 21f, 12f)
                }
            }
            canvas.drawPath(lines, paint)
            canvas.restore()
        }
    }


    fun infoRow(context: Context, icon: String, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val iconView = text(context, icon, 19f, themedColor(context, "#D0C8FF", "#397CC3"), true).apply {
                gravity = Gravity.CENTER
                background = rounded(SURFACE_3, 14, context)
            }
            addView(iconView, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, 0, 0)
                addView(text(context, title, 13.5f, TEXT, true))
                addView(text(context, subtitle, 11.5f, TEXT_3).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                })
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

    fun pill(context: Context, textValue: String): TextView {
        val dark = isDark(context)
        return text(
            context,
            textValue,
            11.5f,
            if (dark) TEXT_2 else Color.parseColor("#397CC3"),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                dp(context, 12),
                dp(context, 7),
                dp(context, 12),
                dp(context, 7)
            )
            background = rounded(
                if (dark) Color.parseColor("#20242E") else Color.parseColor("#E8F1FF"),
                999,
                context,
                if (dark) Color.parseColor("#343A47") else Color.parseColor("#90B9FF"),
                1
            )
        }
    }

    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            )
        }
    }

    fun primaryFill(context: Context): Int = themedColor(context, "#6947B6", "#184FB9")

    fun primaryActionContent(context: Context): Int =
        Color.WHITE

    fun primaryActionSecondaryContent(context: Context): Int =
        themedColor(context, "#E9E5FF", "#DCEAFF")

    fun primaryContainer(context: Context): Int =
        themedColor(context, "#33294F", "#E0EDFC")

    fun onPrimaryContainer(context: Context): Int =
        themedColor(context, "#E0D5FF", "#184A9F")

    fun secondaryContainer(context: Context): Int =
        themedColor(context, "#242A39", "#EEF3FA")

    fun sectionLabel(context: Context, title: String): TextView =
        text(context, title, 12.5f, ACCENT, true).apply {
            setPadding(
                dp(context, 8),
                dp(context, 7),
                dp(context, 8),
                dp(context, 7)
            )
        }

    fun badge(context: Context, value: String, emphasized: Boolean = true): TextView =
        text(
            context,
            value,
            11f,
            if (emphasized) onPrimaryContainer(context) else TEXT_2,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                dp(context, 10),
                dp(context, 5),
                dp(context, 10),
                dp(context, 5)
            )
            background = rounded(
                if (emphasized) primaryContainer(context) else SURFACE_3,
                999,
                context
            )
        }

    fun groupCard(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 24, context)
            elevation = dp(context, if (isDark(context)) 1 else 2).toFloat()
            clipToOutline = false
        }

    fun groupDivider(context: Context, insetDp: Int = 70): View =
        View(context).apply {
            setBackgroundColor(
                themedColor(context, "#2A3040", "#E2E7EF")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            ).apply {
                leftMargin = dp(context, insetDp)
                rightMargin = dp(context, 18)
            }
        }

    fun groupButton(
        context: Context,
        icon: String,
        title: String
    ): TextView =
        text(context, "$icon   $title", 14.5f, TEXT, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(
                dp(context, 18),
                0,
                dp(context, 18),
                0
            )
            isClickable = true
            isFocusable = true
            background = ripple(
                context,
                Color.TRANSPARENT,
                18,
                if (isDark(context)) 0x22FFFFFF else 0x12000000
            )
        }

    fun groupRow(
        context: Context,
        icon: String,
        title: String,
        summary: String,
        trailingText: String? = null
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 72)
            setPadding(
                dp(context, 18),
                dp(context, 14),
                dp(context, 18),
                dp(context, 14)
            )

            val iconBox = text(
                context,
                icon,
                19f,
                onPrimaryContainer(context),
                true
            ).apply {
                gravity = Gravity.CENTER
                background = rounded(primaryContainer(context), 12, context)
            }
            addView(
                iconBox,
                LinearLayout.LayoutParams(
                    dp(context, 42),
                    dp(context, 42)
                )
            )

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 14), 0, dp(context, 8), 0)
                    addView(text(context, title, 14.5f, TEXT, true))
                    addView(
                        text(context, summary, 11.5f, TEXT_3).apply {
                            setPadding(0, dp(context, 3), 0, 0)
                            setLineSpacing(0f, 1.12f)
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            if (!trailingText.isNullOrBlank()) {
                addView(badge(context, trailingText, true))
            }
        }

    fun featureTile(
        context: Context,
        icon: String,
        title: String,
        summary: String,
        badgeText: String? = null,
        emphasized: Boolean = false
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(context, 120)
            setPadding(
                dp(context, 16),
                dp(context, 16),
                dp(context, 16),
                dp(context, 16)
            )
            isClickable = true
            isFocusable = true
            background = ripple(
                context,
                if (emphasized) primaryContainer(context) else SURFACE,
                22,
                if (isDark(context)) 0x22FFFFFF else 0x12000000
            )
            elevation = dp(context, if (isDark(context)) 1 else 2).toFloat()

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                text(
                    context,
                    icon,
                    20f,
                    onPrimaryContainer(context),
                    true
                ).apply {
                    gravity = Gravity.CENTER
                    background = rounded(primaryContainer(context), 12, context)
                },
                LinearLayout.LayoutParams(
                    dp(context, 40),
                    dp(context, 40)
                )
            )
            top.addView(
                View(context),
                LinearLayout.LayoutParams(0, 1, 1f)
            )
            if (!badgeText.isNullOrBlank()) {
                top.addView(badge(context, badgeText, true))
            }
            addView(top)

            addView(
                text(context, title, 15f, TEXT, true).apply {
                    setPadding(0, dp(context, 14), 0, 0)
                }
            )
            addView(
                text(context, summary, 11.5f, TEXT_3).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                    setLineSpacing(0f, 1.12f)
                }
            )
        }


}
