package dev.stillpoint.focus

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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.WindowManager
import android.widget.*

class FloatingTimerService : Service() {
    private val store by lazy { FocusStore(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { render() }
    private var panel: LinearLayout? = null
    private var position = .4f
    private var layout: WindowManager.LayoutParams? = null
    private var dismissTarget: android.widget.ImageView? = null
    private var collapsed = false
    private var shownCollapsed: Boolean? = null
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
    private fun shape(color: String, edge: Boolean = false, radius: Int = if (edge) 22 else 15) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        val r = dp(radius).toFloat()
        cornerRadii = if (edge) floatArrayOf(0f,0f,r,r,r,r,0f,0f) else FloatArray(8) { r }
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
        val row = panel ?: DragRail().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape("#252144", true)
        }
        val resize = shownCollapsed != collapsed
        if (resize) {
            shownCollapsed = collapsed
            row.removeAllViews()
            if (collapsed) {
                controls?.clock?.stop()
                row.setPadding(0, 0, 0, 0)
                row.addView(button("Expand floating timer", R.drawable.ic_rail_expand, "#252144") {
                    collapsed = false
                    render()
                }, dp(48), dp(60))
            } else {
                val expanded = controls ?: createControls().also { controls = it }
                row.setPadding(dp(6), dp(6), dp(6), dp(6))
                row.addView(expanded.toggle, dp(48), dp(48))
                row.addView(expanded.labels, LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(expanded.minimize, dp(48), dp(48))
            }
        }
        if (!collapsed) controls?.let { expanded ->
            expanded.toggle.setImageResource(if (session.running) R.drawable.ic_rail_pause else R.drawable.ic_rail_play)
            expanded.toggle.contentDescription = if (session.running) "Pause timer" else "Resume timer"
            expanded.clock.isCountDown = !stopwatch
            expanded.clock.base = SystemClock.elapsedRealtime() + if (stopwatch) -remaining else remaining
            if (session.running) expanded.clock.start() else {
                expanded.clock.stop()
                expanded.clock.text = timerDigits(remaining, stopwatch)
            }
            val status = when {
                !session.running -> "Paused"
                stopwatch -> "Time focused"
                session.phase == Phase.BREAK -> "Break remaining"
                else -> "Time remaining"
            }
            expanded.status.text = status
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

    private fun createControls(): TimerControls {
        val toggle = button("Pause timer", R.drawable.ic_rail_pause, "#FFBCAC") {
            if (store.session()?.strict == false) store.pauseOrResume()
        }.apply { imageTintList = ColorStateList.valueOf(0xFF252144.toInt()) }
        val clock = Chronometer(this).apply {
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(12, 24, 1, TypedValue.COMPLEX_UNIT_SP)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            fontFeatureSettings = "tnum"
        }
        val status = TextView(this).apply {
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(Color.parseColor("#DAD5EE"))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            addView(clock, LinearLayout.LayoutParams(-1, 0, .64f))
            addView(status, LinearLayout.LayoutParams(-1, 0, .36f))
        }
        val minimize = button("Minimize floating timer", R.drawable.ic_rail_collapse, "#393459") {
            collapsed = true
            render()
        }
        return TimerControls(toggle, labels, clock, status, minimize)
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

    private fun travel() = (availableSize().y - dp(76)).coerceAtLeast(0)

    private fun windowParams() = WindowManager.LayoutParams(
        dp(if (collapsed) 48 else 216).coerceAtMost(availableSize().x.coerceAtLeast(1)), dp(60),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        y = dp(8) + (position * travel()).toInt()
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val target = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = shape("#413955", radius = 32)
            contentDescription = "Drop here to hide timer"
        }
        val targetParams = WindowManager.LayoutParams(
            dp(64), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        runCatching { windows.addView(target, targetParams) }.onSuccess { dismissTarget = target }
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
        val margin = dp(12)
        return x in (location[0] - margin).toFloat()..(location[0] + target.width + margin).toFloat() &&
            y in (location[1] - margin).toFloat()..(location[1] + target.height + margin).toFloat()
    }

    private inner class DragRail : LinearLayout(this@FloatingTimerService) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var originalY = 0
        private var dragArea = android.graphics.Point()
        private var dragging = false
        private var overTarget = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
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
                    if (dismissed) {
                        stopSelf()
                    } else {
                        store.prefs.edit().putFloat("floating_timer_y", position).apply()
                        windows.updateViewLayout(this, windowParams().also { layout = it })
                    }
                }
            }
            return true
        }

        private fun startDrag(event: MotionEvent): Boolean {
            if (!dragging && kotlin.math.hypot(event.rawX - startX, event.rawY - startY) > slop) {
                dragging = true
                showDismissTarget()
                moveTo(event)
            }
            return dragging
        }

        private fun moveTo(event: MotionEvent) {
            val params = layout ?: return
            val range = (dragArea.y - dp(76)).coerceAtLeast(0)
            val top = dp(8)
            val nextY = (originalY + (event.rawY - startY).toInt()).coerceIn(top, top + range)
            val nextX = (event.rawX - startX).toInt().coerceIn(0, (dragArea.x - width).coerceAtLeast(0))
            val hit = overDismissTarget(event.rawX, event.rawY)
            if (hit != overTarget) {
                overTarget = hit
                dismissTarget?.background = shape(if (hit) "#A33F54" else "#413955", radius = 32)
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
        controls?.clock?.stop()
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        controls = null
        panel = null
        layout = null
        shownCollapsed = null
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
        val minimize: ImageButton
    )
}
