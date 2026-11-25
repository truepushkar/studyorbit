package dev.stillpoint.focus

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MiniTimerActivity : ComponentActivity() {
    private var attempted = false
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = FocusStore(this)
        val time = TextView(this).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(16, 42, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(Color.WHITE)
            fontFeatureSettings = "tnum"
        }
        val state = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(218, 201, 248))
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 19, 24))
            addView(time, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
            ))
            addView(state)
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val changes = Channel<Unit>(Channel.CONFLATED)
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (isSessionPreference(key)) changes.trySend(Unit)
                }
                store.prefs.registerOnSharedPreferenceChangeListener(listener)
                try {
                    var session = store.session()
                    var shownStatus = 0
                    while (session != null) {
                        val remaining = session.displayTime()
                        val elapsed = session.mode == TimerMode.STOPWATCH
                        time.text = timerDigits(remaining, elapsed)
                        val status = when {
                            !session.running -> R.string.mini_timer_paused
                            session.phase == Phase.BREAK -> R.string.mini_timer_break
                            else -> R.string.mini_timer_focus
                        }
                        if (shownStatus != status) {
                            state.setText(status)
                            shownStatus = status
                        }
                        if (!session.running) {
                            changes.receive()
                            session = store.session()
                        } else {
                            val atEnd = !elapsed && remaining == 0L
                            val delay = if (atEnd) 1L else timerTickDelay(remaining, elapsed)
                            val changed = withTimeoutOrNull(delay) { changes.receive() } != null
                            if (changed || atEnd) session = store.session()
                        }
                    }
                    finish()
                } finally {
                    store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
                    changes.close()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!attempted && !isInPictureInPictureMode && !isFinishing) {
            attempted = true
            window.decorView.post {
                if (!isFinishing && !runCatching {
                        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1)).build())
                    }.getOrDefault(false)) {
                    Toast.makeText(this, R.string.mini_timer_permission, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        if (inPictureInPictureMode) entered = true
        else if (entered && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing) {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.SHOW_FOCUS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
