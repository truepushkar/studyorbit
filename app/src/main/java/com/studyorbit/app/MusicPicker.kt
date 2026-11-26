package com.studyorbit.app

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

private suspend fun downloadMusic(context: Context, track: Soundscape) = withContext(Dispatchers.IO) {
    val target = track.file(context)
    if (target.isFile) return@withContext
    val temp = File.createTempFile("music-", ".part", context.cacheDir)
    val connection = URL(track.url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        check(connection.responseCode == 200)
        val hash = MessageDigest.getInstance("SHA-256")
        connection.inputStream.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    hash.update(buffer, 0, count)
                }
            }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == track.digest)
        ensureActive()
        check(temp.renameTo(target) || target.isFile)
    } finally {
        connection.disconnect()
        temp.delete()
    }
}

@Composable
internal fun MusicPicker(current: Soundscape, onSelect: (Soundscape) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).orbitField()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.focus_music)) { open = true }
            .padding(horizontal = Gap.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.focus_music), Modifier.weight(1f), color = Ink, fontSize = 14.sp)
        Text(current.label, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OrbitIcon(R.drawable.ic_next, tint = Muted, size = 20.dp)
    }
    if (!open) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf<Soundscape?>(null) }
    var failed by remember { mutableStateOf<Soundscape?>(null) }
    var preview by remember { mutableStateOf<Soundscape?>(null) }
    var error by remember { mutableStateOf(false) }
    var savedTracks by remember(context) {
        mutableStateOf(Soundscape.entries.asSequence().drop(1).filter { it.file(context).isFile }.toSet())
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) preview = null }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(preview) {
        val item = preview ?: return@LaunchedEffect
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0 && preview == item) preview = null }.build()
        val player = MediaPlayer()
        var hasFocus = false
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(item.file(context).absolutePath)
            player.awaitPrepared()
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(hasFocus)
            player.setOnErrorListener { _, _, _ ->
                if (preview == item) {
                    error = true
                    preview = null
                }
                true
            }
            player.start()
            delay(minOf(15000L, player.duration.toLong()))
            if (preview == item) preview = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (preview == item) {
                error = true
                preview = null
            }
        } finally {
            player.release()
            if (hasFocus) audio.abandonAudioFocusRequest(focus)
        }
    }
    OrbitSheet({ open = false }) {
        OrbitHeading(stringResource(R.string.focus_music), level = HeadingLevel.Sheet)
        Text(stringResource(R.string.download_a_track_to_preview_it_and), color = Muted)
        if (error) Text(stringResource(R.string.preview_unavailable_try_again), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Off", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold)
                TextButton({ onSelect(Soundscape.OFF); open = false }, Modifier.heightIn(min = 48.dp)) {
                    Text(if (current == Soundscape.OFF) "Selected" else "Turn music off", fontWeight = FontWeight.SemiBold)
                }
            }
            Soundscape.entries.drop(1).forEach { item ->
                HorizontalDivider(color = Line)
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold)
                        if (item in savedTracks) {
                            TextButton({ error = false; preview = if (preview == item) null else item }, Modifier.heightIn(min = 48.dp)) {
                                Text(if (preview == item) "Stop preview" else "Preview")
                            }
                            TextButton({ onSelect(item); open = false }, Modifier.heightIn(min = 48.dp)) {
                                Text(if (current == item) "Selected" else "Use track", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            TextButton(enabled = loading == null, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                loading = item
                                failed = null
                                scope.launch {
                                    try {
                                        downloadMusic(context, item)
                                        savedTracks = savedTracks + item
                                    }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { failed = item }
                                    finally { loading = null }
                                }
                            }) { Text(when (item) { loading -> "Downloading…"; failed -> "Retry download"; else -> "Download" }, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    if (failed == item) Text(stringResource(R.string.download_failed_check_your_connection_and_retry),
                        Modifier.padding(bottom = Gap.md), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}

private suspend fun MediaPlayer.awaitPrepared() = suspendCancellableCoroutine<Unit> { continuation ->
    setOnPreparedListener {
        setOnPreparedListener(null)
        setOnErrorListener(null)
        if (continuation.isActive) continuation.resume(Unit)
    }
    setOnErrorListener { _, _, _ ->
        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Preview preparation failed"))
        true
    }
    prepareAsync()
}
