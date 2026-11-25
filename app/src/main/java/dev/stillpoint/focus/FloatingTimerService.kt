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
import android.view.View
import android.view.WindowManager
import android.widget.*

class FloatingTimerService : Service() {
    private val store by lazy { FocusStore(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { render() }
    private var panel: LinearLayout? = null
    private var collapsed = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (isSessionPreference(key)) {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
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
    private fun shape(color: String, edge: Boolean = false) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        val r = dp(if (edge) 14 else 32).toFloat()
        cornerRadii = if (edge) floatArrayOf(0f,0f,r,r,r,r,0f,0f) else FloatArray(8) { r }
    }
    private fun button(label: String, glyph: String, action: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 25f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
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
        val row = panel ?: LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape("#234621", true)
        }
        row.removeAllViews()
        if (collapsed) {
            row.setPadding(0,0,0,0)
            row.addView(button("Expand floating timer", "❯") { collapsed = false; render() }, dp(48), dp(64))
        } else {
            row.setPadding(dp(10),dp(8),dp(8),dp(8))
            val toggle = button(if (session.running) "Pause timer" else "Resume timer", if (session.running) "Ⅱ" else "▶") {
                if (store.session()?.strict == false) store.pauseOrResume()
            }.apply { background = shape("#15380F"); setTextColor(Color.parseColor("#A5E780")) }
            row.addView(toggle, dp(48), dp(48))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,dp(10),0) }
            val clock = Chronometer(this).apply {
                textSize = 24f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                fontFeatureSettings = "tnum"
                isCountDown = !stopwatch
                base = SystemClock.elapsedRealtime() + if (stopwatch) -remaining else remaining
                if (session.running) start() else text = timerDigits(remaining, stopwatch)
            }
            labels.addView(clock)
            labels.addView(TextView(this).apply {
                text = when {
                    !session.running -> "Paused"
                    stopwatch -> "Time focused"
                    session.phase == Phase.BREAK -> "Break remaining"
                    else -> "Time remaining"
                }
                textSize = 13f
                setTextColor(Color.parseColor("#D3E4CA"))
            })
            row.addView(labels)
            row.addView(button("Minimize floating timer", "×") { collapsed = true; render() }
                .apply { background = shape("#385A37") }, dp(48), dp(48))
        }
        val params = WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            y = (resources.displayMetrics.heightPixels * if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) .30 else .40).toInt()
        }
        try {
            if (panel == null) { windows.addView(row, params); panel = row } else windows.updateViewLayout(row, params)
        } catch (_: RuntimeException) { stopSelf() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
