package com.studyorbit.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*

class FloatingTimerService : Service() {
    private val store by lazy { FocusStore(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { render() }
    private var panel: DragRail? = null
    private var position = .4f
    private var layout: WindowManager.LayoutParams? = null
    private var dismissTarget: android.widget.ImageView? = null
    private var collapsed = false
    private var shownCollapsed: Boolean? = null
    private var shownRight: Boolean? = null
    private var dockRight = false
    private var snapAnimator: ValueAnimator? = null
    private val bold by lazy { resources.getFont(R.font.inter_bold) }
    private val medium by lazy { resources.getFont(R.font.inter_medium) }
    private var controls: TimerControls? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (isSessionPreference(key)) {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        position = store.prefs.getFloat("floating_timer_y", .4f).coerceIn(0f, 1f)
        dockRight = store.prefs.getBoolean("floating_timer_right", false)
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("floating_timer", "Floating timer", NotificationManager.IMPORTANCE_LOW))
        val hide = PendingIntent.getService(this, 0, Intent(this, FloatingTimerService::class.java).setAction("hide"),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.SHOW_FOCUS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7401, Notification.Builder(this, "floating_timer").setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Floating timer").setContentText("Tap to return to Focus").setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide timer", hide).build()).setOngoing(true).build())
        if (intent?.action == "hide") stopSelf() else { collapsed = false; render() }
        return START_NOT_STICKY
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: String, edge: Boolean = false, radius: Int = if (edge) 18 else 11) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        val r = dp(radius).toFloat()
        cornerRadii = if (edge) floatArrayOf(0f,0f,r,r,r,r,0f,0f) else FloatArray(8) { r }
    }
    private fun railHeight() = dp(48)
    private fun reserve() = railHeight() + dp(12)

    private fun railRadii(docked: Boolean): FloatArray {
        val r = dp(18).toFloat()
        return when {
            !docked -> FloatArray(8) { r }
            dockRight -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            else -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
    }

    // Docked rails are flat against their screen edge; a rail in motion is a fully rounded pill.
    private fun railShape(docked: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E2860.toInt(), 0xFF1D1A38.toInt())
    ).apply {
        cornerRadii = railRadii(docked)
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun railMask() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadii = railRadii(true)
    }

    private fun button(label: String, icon: Int, color: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), shape(color), null)
        contentDescription = label
        setOnClickListener { action() }
        isFocusable = true
    }

    private fun render() {
        handler.removeCallbacks(refresh)
        val session = store.session()
        if (session == null || session.strict || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val remaining = session.displayTime()
        val stopwatch = session.mode == TimerMode.STOPWATCH
        if (!stopwatch && session.running) handler.postDelayed(refresh, remaining.coerceAtLeast(50))
        val row = panel ?: DragRail().apply { gravity = Gravity.CENTER_VERTICAL }
        val parts = controls ?: createControls().also { controls = it }
        val resize = shownCollapsed != collapsed || shownRight != dockRight
        if (resize) {
            shownCollapsed = collapsed
            shownRight = dockRight
            row.removeAllViews()
            row.background = railShape(true)
            // Chevrons point toward the docked edge when collapsing and away from it when expanding.
            val flip = if (dockRight) -1f else 1f
            parts.minimize.scaleX = flip
            parts.chevron.scaleX = flip
            if (collapsed) {
                parts.clock.stop()
                parts.tab.background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, railMask())
                row.setPadding(0, 0, 0, 0)
                row.addView(parts.tab, LinearLayout.LayoutParams(-1, -1))
            } else {
                row.setPadding(dp(6), dp(6), dp(6), dp(6))
                val labels = LinearLayout.LayoutParams(0, dp(36), 1f)
                if (dockRight) {
                    row.addView(parts.minimize, dp(36), dp(36))
                    row.addView(parts.labels, labels)
                    row.addView(parts.toggle, dp(36), dp(36))
                } else {
                    row.addView(parts.toggle, dp(36), dp(36))
                    row.addView(parts.labels, labels)
                    row.addView(parts.minimize, dp(36), dp(36))
                }
            }
        }
        if (!collapsed) {
            val clock = parts.clock
            clock.isCountDown = !stopwatch
            clock.base = SystemClock.elapsedRealtime() + if (stopwatch) -remaining else remaining
            clock.setTextColor(if (session.running) Color.WHITE else 0xFFC6C5DD.toInt())
            if (session.running) clock.start() else {
                clock.stop()
                clock.text = timerDigits(remaining, stopwatch)
            }
            parts.toggle.setImageResource(if (session.running) R.drawable.ic_rail_pause else R.drawable.ic_rail_play)
            parts.toggle.contentDescription = if (session.running) "Pause timer" else "Resume timer"
            parts.status.text = when {
                !session.running -> "Paused"
                stopwatch -> "Focused"
                session.phase == Phase.BREAK -> "Break left"
                else -> "Remaining"
            }
        }
        try {
            if (panel == null) {
                windows.addView(row, windowParams().also { layout = it })
                panel = row
            } else if (resize) {
                windows.updateViewLayout(row, windowParams().also { layout = it })
            }
        } catch (_: RuntimeException) {
            stopSelf()
        }
    }

    private fun styleClock(clock: Chronometer, minSp: Int, maxSp: Int, align: Int) = clock.apply {
        isSingleLine = true
        includeFontPadding = false
        gravity = align
        setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        typeface = bold
        setTextColor(Color.WHITE)
        fontFeatureSettings = "tnum"
    }

    private fun snapToEdge(view: DragRail) {
        val params = layout ?: return
        val area = availableSize()
        dockRight = params.x + view.width / 2 > area.x / 2
        store.prefs.edit().putBoolean("floating_timer_right", dockRight).apply()
        val target = if (dockRight) (area.x - view.width).coerceAtLeast(0) else 0
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windows.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    view.background = railShape(true)
                    render()
                }
            })
            start()
        }
    }

    private fun createControls(): TimerControls {
        val toggle = button("Pause timer", R.drawable.ic_rail_pause, "#FFBCAC") {
            if (store.session()?.strict == false) store.pauseOrResume()
        }.apply { imageTintList = ColorStateList.valueOf(0xFF252144.toInt()) }
        val clock = styleClock(Chronometer(this), 11, 19, Gravity.CENTER_VERTICAL)
        val status = TextView(this).apply {
            isSingleLine = true
            includeFontPadding = false
            typeface = medium
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(Color.parseColor("#DAD5EE"))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, dp(7), 0)
            addView(clock, LinearLayout.LayoutParams(-1, 0, .64f))
            addView(status, LinearLayout.LayoutParams(-1, 0, .36f))
        }
        val minimize = button("Minimize floating timer", R.drawable.ic_rail_collapse, "#393459") {
            collapsed = true
            render()
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_rail_expand)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = "Expand floating timer"
            isClickable = true
            isFocusable = true
            addView(chevron, LinearLayout.LayoutParams(-1, -1))
            setOnClickListener {
                collapsed = false
                render()
            }
        }
        return TimerControls(toggle, labels, clock, status, minimize, tab, chevron)
    }

    @Suppress("DEPRECATION")
    private fun availableSize(): android.graphics.Point {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            return android.graphics.Point(metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }
        return android.graphics.Point().also { windows.defaultDisplay.getSize(it) }
    }

    private fun travel() = (availableSize().y - reserve()).coerceAtLeast(0)

    private fun windowParams(): WindowManager.LayoutParams {
        val area = availableSize()
        val width = dp(if (collapsed) 36 else 160).coerceAtMost(area.x.coerceAtLeast(1))
        return WindowManager.LayoutParams(
            width, railHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (dockRight) (area.x - width).coerceAtLeast(0) else 0
            y = dp(8) + (position * travel()).toInt()
        }
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val target = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = shape("#413955", radius = 40)
            contentDescription = "Drop here to hide timer"
            scaleX = .8f
            scaleY = .8f
            alpha = 0f
        }
        val targetParams = WindowManager.LayoutParams(
            dp(80), dp(80), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        runCatching { windows.addView(target, targetParams) }.onSuccess {
            dismissTarget = target
            target.animate().alpha(1f).setDuration(140).start()
        }
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { runCatching { windows.removeViewImmediate(it) } }
        dismissTarget = null
    }

    private fun overDismissTarget(x: Float, y: Float): Boolean {
        val target = dismissTarget ?: return false
        if (target.width == 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val margin = dp(4)
        return x in (location[0] - margin).toFloat()..(location[0] + target.width + margin).toFloat() &&
            y in (location[1] - margin).toFloat()..(location[1] + target.height + margin).toFloat()
    }

    private inner class DragRail : LinearLayout(this@FloatingTimerService) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var originalX = 0
        private var originalY = 0
        private var dragArea = android.graphics.Point()
        private var dragging = false
        private var overTarget = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originalX = layout?.x ?: 0
                    originalY = layout?.y ?: 0
                    dragArea = availableSize()
                    dragging = false
                    overTarget = false
                }
                MotionEvent.ACTION_MOVE -> if (startDrag(event)) return true
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (startDrag(event)) moveTo(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val dismissed = event.actionMasked == MotionEvent.ACTION_UP &&
                        overDismissTarget(event.rawX, event.rawY)
                    dragging = false
                    hideDismissTarget()
                    alpha = 1f
                    if (dismissed) {
                        stopSelf()
                    } else {
                        store.prefs.edit().putFloat("floating_timer_y", position).apply()
                        snapToEdge(this)
                    }
                }
            }
            return true
        }

        private fun startDrag(event: MotionEvent): Boolean {
            if (!dragging && kotlin.math.hypot(event.rawX - startX, event.rawY - startY) > slop) {
                dragging = true
                snapAnimator?.cancel()
                background = railShape(false)
                showDismissTarget()
                moveTo(event)
            }
            return dragging
        }

        private fun moveTo(event: MotionEvent) {
            val params = layout ?: return
            val range = (dragArea.y - reserve()).coerceAtLeast(0)
            val top = dp(8)
            val nextY = (originalY + (event.rawY - startY).toInt()).coerceIn(top, top + range)
            val nextX = (originalX + (event.rawX - startX).toInt()).coerceIn(0, (dragArea.x - width).coerceAtLeast(0))
            val hit = overDismissTarget(event.rawX, event.rawY)
            if (hit != overTarget) {
                overTarget = hit
                alpha = if (hit) .55f else 1f
                dismissTarget?.let { target ->
                    target.background = shape(if (hit) "#A33F54" else "#413955", radius = 40)
                    target.animate().scaleX(if (hit) 1f else .8f).scaleY(if (hit) 1f else .8f).setDuration(120).start()
                    if (hit) target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            if (range > 0) position = (nextY - top).toFloat() / range
            if (params.x != nextX || params.y != nextY) {
                params.x = nextX
                params.y = nextY
                windows.updateViewLayout(this, params)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recreate once so text scales, dimensions and placement follow the new configuration.
        removePanel()
        render()
    }

    private fun removePanel() {
        hideDismissTarget()
        snapAnimator?.cancel()
        snapAnimator = null
        controls?.clock?.stop()
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        controls = null
        panel = null
        layout = null
        shownCollapsed = null
        shownRight = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        removePanel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private data class TimerControls(
        val toggle: ImageButton,
        val labels: LinearLayout,
        val clock: Chronometer,
        val status: TextView,
        val minimize: ImageButton,
        val tab: LinearLayout,
        val chevron: ImageView
    )
}
