package dev.stillpoint.focus

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.util.Rational
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
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
            textSize = 42f
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
            addView(time)
            addView(state)
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val session = store.session()
                    if (session == null) { finish(); break }
                    val remaining = session.displayTime()
                    val elapsed = session.mode == TimerMode.STOPWATCH
                    val seconds = if (elapsed) remaining / 1_000 else (remaining + 999) / 1_000
                    time.text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                    state.text = when {
                        !session.running -> "Paused"
                        session.phase == Phase.BREAK -> "Break"
                        else -> "Focus"
                    }
                    val fraction = remaining % 1_000
                    delay(when {
                        !session.running -> 1_000
                        elapsed -> 1_000 - fraction
                        fraction == 0L -> 1_000
                        else -> fraction
                    })
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
                    android.widget.Toast.makeText(this, "Allow picture-in-picture in Android Settings.", android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        if (inPictureInPictureMode) entered = true
        else if (entered && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing) {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.SHOW_FOCUS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
