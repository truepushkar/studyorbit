package dev.stillpoint.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Trace
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal enum class Soundscape(val label: String) {
    OFF("Off"), RAIN("Rain"), NOISE("White noise"), DRIFT("Ambient chords")
}

internal enum class TimerTheme(val label: String) {
    ORBIT("Orbit"), FOREST("Forest"), DUSK("Dusk"), OCEAN("Ocean")
}

internal fun FocusStore.soundscape() = Soundscape.entries.getOrElse(prefs.getInt("soundscape", 0)) { Soundscape.OFF }

class FocusAudio : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { FocusStore(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var track: AudioTrack? = null
    private var preparedSound = Soundscape.OFF
    private var preparingSound = Soundscape.OFF
    private var preparation: Job? = null
    private var wantsPlayback = false
    private var ownsFocus = false
    private var notified: String? = null
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (wantsPlayback) track?.play()
                AudioManager.AUDIOFOCUS_LOSS -> if (ownsFocus) stopSelf()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.pause()
            }
        }, handler).build()
    private val refresh = Runnable { updateAudio() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!isSessionPreference(key) && key != "soundscape") return@OnSharedPreferenceChangeListener
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (notified == null) publish("Preparing focus sounds", foreground = true)
        updateAudio()
        return START_NOT_STICKY
    }

    private fun updateAudio() {
        val session = store.session()
        val sound = store.soundscape()
        if (session == null || sound == Soundscape.OFF) {
            wantsPlayback = false
            cancelPreparation()
            stopSelf()
            return
        }
        handler.removeCallbacks(refresh)
        wantsPlayback = session.running && session.phase == Phase.FOCUS
        if (session.running && session.mode != TimerMode.STOPWATCH) {
            handler.postDelayed(refresh, session.remaining.coerceAtLeast(1))
        }
        if (!wantsPlayback) {
            cancelPreparation()
            pauseTrack()
            publish("Audio paused")
        } else if (preparedSound == sound) {
            playTrack(sound)
        } else if (preparingSound != sound) {
            releaseTrack()
            prepareTrack(sound)
        }
    }

    private fun playTrack(sound: Soundscape) {
        if (ownsFocus) return
        ownsFocus = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!ownsFocus) {
            stopSelf()
            return
        }
        runCatching {
            track?.play()
            publish(sound.label)
        }.onFailure { stopSelf() }
    }

    private fun prepareTrack(sound: Soundscape) {
        cancelPreparation()
        preparingSound = sound
        preparation = scope.launch(Dispatchers.Default) {
            var player: AudioTrack? = null
            try {
                Trace.beginSection("StudyOrbit.audio.prepare")
                val prepared = try {
                    val samples = makeLoop(sound, coroutineContext)
                    val created = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    ).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
                    player = created
                    check(created.write(samples, 0, samples.size) == samples.size)
                    check(created.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
                    created
                } finally {
                    Trace.endSection()
                }
                withContext(Dispatchers.Main.immediate) {
                    if (wantsPlayback && preparingSound == sound) {
                        track = prepared
                        player = null
                        preparedSound = sound
                        preparingSound = Soundscape.OFF
                        preparation = null
                        playTrack(sound)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) { stopSelf() }
            } finally {
                player?.release()
            }
        }
    }

    private fun cancelPreparation() {
        preparation?.cancel()
        preparation = null
        preparingSound = Soundscape.OFF
    }

    private fun publish(label: String, foreground: Boolean = false) {
        if (notified == label) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("focus_audio", "Focus sounds", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.SHOW_FOCUS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "focus_audio")
            .setSmallIcon(R.drawable.ic_timer).setContentTitle("Focus sounds").setContentText(label)
            .setContentIntent(open).setOngoing(true).build()
        if (foreground) startForeground(30, notification) else manager.notify(30, notification)
        notified = label
    }

    private fun pauseTrack() {
        if (!ownsFocus) return
        track?.pause()
        audio.abandonAudioFocusRequest(request)
        ownsFocus = false
    }

    private fun releaseTrack() {
        pauseTrack()
        track?.release()
        track = null
        preparedSound = Soundscape.OFF
    }

    override fun onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        wantsPlayback = false
        releaseTrack()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun makeLoop(sound: Soundscape, coroutineContext: CoroutineContext): ShortArray {
        val random = Random(48)
        var low = 0.0
        val size = RATE * 8
        return ShortArray(size) { index ->
            if (index % 4096 == 0) coroutineContext.ensureActive()
            val sample = when (sound) {
                Soundscape.RAIN -> {
                    val noise = random.nextDouble(-1.0, 1.0)
                    low = 0.96 * low + 0.04 * noise
                    low * 0.62 + noise * 0.1
                }
                Soundscape.NOISE -> random.nextDouble(-1.0, 1.0) * 0.16
                Soundscape.DRIFT -> {
                    val t = index.toDouble() / RATE
                    sin(t * 2 * PI * 110) * 0.075 +
                        sin(t * 2 * PI * 165) * (0.04 + 0.025 * sin(t * PI / 4)) +
                        sin(t * 2 * PI * 220) * 0.025
                }
                Soundscape.OFF -> 0.0
            }
            val edge = minOf(index, size - 1 - index, 64) / 64.0
            (sample * edge * Short.MAX_VALUE).toInt().toShort()
        }
    }

    companion object {
        private const val RATE = 22_050
    }
}
