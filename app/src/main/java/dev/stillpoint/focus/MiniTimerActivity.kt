package dev.stillpoint.focus

import android.app.PictureInPictureParams
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                while (true) {
                    val session = store.session()
                    if (session == null) { finish(); break }
                    val remaining = session.displayTime()
                    val elapsed = session.mode == TimerMode.STOPWATCH
                    time.text = timerDigits(remaining, elapsed)
                    state.setText(when {
                        !session.running -> R.string.mini_timer_paused
                        session.phase == Phase.BREAK -> R.string.mini_timer_break
                        else -> R.string.mini_timer_focus
                    })
                    delay(if (session.running) timerTickDelay(remaining, elapsed) else 1_000)
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
