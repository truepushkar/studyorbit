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
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal enum class Soundscape(val label: String, val digest: String = "") {
    OFF("Off"),
    BINAURAL_BIRD_BROOK("Binaural Bird Brook", "2d8bef55a55e3fb055576d33aea82f30627dc30fbea3f20d08a97b755fa554d8"),
    BINAURAL_BIRD_RAIN("Binaural Bird Rain", "cb888129c2303cc15b36cc4db201f8e1c55a8a66be33210f54b716fc01b174d8"),
    BINAURAL_MUSIC("Binaural Music", "e5c6ba9830926cae7a206f6ddc066c747c0483b784de8c4e390a54abf93e5bf2"),
    BROWN_NOISE("Brown Noise", "d87979ef0f02b20f52397d384fe98e41e24b58bc06c342b7f7b07b545303006f"),
    CAFE_JAZZ("Cafe Jazz", "75719ed83250b816d82bfce0f6f203d4cbe02e0413e3e7d050fca65b454b6b05"),
    DEEP_FOCUS_AMBIENCE("Deep Focus Ambience", "b9348e53b724f04cc41830b9e3e17e16a5a2ad06868ad012cda836fd74b224be"),
    LOFI_BEATS("Lofi Beats", "f4846b090ae69a6ba19da5459420d443f097ad2d21eaf46cc0a0a6673f8ab928"),
    PINK_NOISE("Pink Noise", "6b9bf9e70f1189bdb85cda38f5d65646482d001b7406b868cddcd61e7d695a8f"),
    RAIN_FOCUS_AMBIENCE("Rain Focus Ambience", "c983251517e9c06d4e859a320018f7d6c356d05bdf0d7cab95d93cd082eccd49"),
    WHITE_NOISE("White Noise", "846caeee0102673f13015ae63b1a6c023fb87c178283d6beca9decdb29b19261");

    val filename get() = name.lowercase(java.util.Locale.ROOT) + ".mp3"
    fun file(context: android.content.Context) = java.io.File(context.filesDir, filename)
    val url get() = "https://github.com/truepushkar/studyorbit/releases/download/music-v1/$filename"

}

internal enum class TimerTheme(val label: String) {
    ORBIT("Orbit"), FOREST("Forest"), DUSK("Dusk"), OCEAN("Ocean")
}

internal fun FocusStore.soundscape() = Soundscape.entries.firstOrNull { it.name == prefs.getString("music_track", null) } ?: Soundscape.OFF

class FocusAudio : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { FocusStore(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var track: android.media.MediaPlayer? = null
    private var preparedSound = Soundscape.OFF
    private var preparingSound = Soundscape.OFF
    private var wantsPlayback = false
    private var ownsFocus = false
    private var notified: String? = null
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (wantsPlayback) track?.start()
                AudioManager.AUDIOFOCUS_LOSS -> if (ownsFocus) stopSelf()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.pause()
            }
        }, handler).build()
    private val refresh = Runnable { updateAudio() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!isSessionPreference(key) && key != "music_track") return@OnSharedPreferenceChangeListener
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (notified == null) publish("Preparing focus music", foreground = true)
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
            if (preparingSound != Soundscape.OFF) releaseTrack() else pauseTrack()
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
            track?.start()
            publish(sound.label)
        }.onFailure { stopSelf() }
    }

    private fun prepareTrack(sound: Soundscape) {
        cancelPreparation()
        preparingSound = sound
        val player = android.media.MediaPlayer()
        track = player
        runCatching {
            player.setAudioAttributes(attributes)
            player.setDataSource(sound.file(this).absolutePath)
            player.isLooping = true
            player.setOnErrorListener { _, _, _ -> stopSelf(); true }
            player.setOnPreparedListener {
                if (track === player && wantsPlayback && preparingSound == sound) {
                    preparingSound = Soundscape.OFF
                    preparedSound = sound
                    playTrack(sound)
                }
            }
            player.prepareAsync()
        }.onFailure { stopSelf() }
    }

    private fun cancelPreparation() {
        preparingSound = Soundscape.OFF
    }

    private fun publish(label: String, foreground: Boolean = false) {
        if (notified == label) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("focus_audio", "Focus music", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.SHOW_FOCUS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "focus_audio")
            .setSmallIcon(R.drawable.ic_timer).setContentTitle("Focus music").setContentText(label)
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
        wantsPlayback = false
        releaseTrack()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

}
