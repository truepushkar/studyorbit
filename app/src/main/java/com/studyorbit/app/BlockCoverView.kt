package com.studyorbit.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal fun blockCoverView(
    context: Context,
    label: String,
    reason: String,
    session: Session?,
    advanced: Boolean,
    primaryLabel: String,
    primaryAction: () -> Unit,
    goHome: (() -> Unit)? = null,
    openApp: (() -> Unit)? = null
): LinearLayout {
    val foreground = Color.rgb(255, 249, 246)
    val muted = Color.rgb(198, 197, 221)
    val accent = Color.rgb(255, 188, 172)
    val secondary = Color.rgb(54, 51, 85)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun LinearLayout.space(units: Float) {
        addView(View(context), LinearLayout.LayoutParams(1, 0, units))
    }
    fun LinearLayout.label(text: String, size: Float, color: Int, weight: Int = R.font.inter_regular, top: Int = 0) {
        addView(TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = context.resources.getFont(weight)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    fun LinearLayout.action(text: String, primary: Boolean, filled: Boolean = true, onClick: () -> Unit) {
        addView(Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            typeface = context.resources.getFont(R.font.inter_semibold)
            setTextColor(if (primary) Color.rgb(33, 30, 56) else accent)
            background = GradientDrawable().apply {
                setColor(if (primary) accent else if (filled) secondary else Color.TRANSPARENT)
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        setPadding(dp(24), dp(24), dp(24), dp(16))
        setBackgroundColor(Color.rgb(23, 22, 50))

        space(1f)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_studyorbit)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        label("StudyOrbit", 16f, accent, R.font.inter_semibold, top = 18)
        space(.35f)
        label(if (advanced) "This feed can wait." else "Back to your orbit.", 30f, foreground, R.font.inter_semibold)
        label(label, 22f, foreground, R.font.inter_medium, top = 22)
        label(reason, 15f, muted, top = 8)
        if (session != null) {
            space(.48f)
            if (session.subject.isNotBlank()) label(session.subject, 14f, muted)
            val remaining = (session.displayTime() + 999L) / 1000L
            val caption = when {
                !session.running -> "Your focus session is paused"
                session.mode == TimerMode.STOPWATCH -> "Your stopwatch is running"
                else -> String.format(Locale.ROOT, "%02d:%02d left in your %s session",
                    remaining / 60, remaining % 60, session.phase.label.lowercase(Locale.ROOT))
            }
            label(caption, 14f, muted, top = 2)
        }
        space(1.35f)
        action(primaryLabel, primary = true, onClick = primaryAction)
        if (goHome != null) action("Go to Home", primary = false, onClick = goHome)
        if (openApp != null) action(context.getString(R.string.open_app), primary = false, filled = false, onClick = openApp)
        space(if (advanced) .5f else 1f)
    }
}
