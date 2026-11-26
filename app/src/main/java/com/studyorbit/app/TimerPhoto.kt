package com.studyorbit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object TimerPhoto {
    private const val MAX_EDGE = 1080
    private val writes = Mutex()

    fun file(context: Context) = File(context.filesDir, "timer-background.webp")

    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        writes.withLock {
            val bitmap = decode(context, uri)
            try {
                val atomic = AtomicFile(file(context))
                val stream = atomic.startWrite()
                try {
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                        else Bitmap.CompressFormat.WEBP
                    if (!bitmap.compress(format, 84, stream)) throw IOException("Photo compression failed")
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
                val prefs = FocusStore(context).prefs
                prefs.edit().putLong("timer_photo_revision", prefs.getLong("timer_photo_revision", 0L) + 1L).apply()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = info.size.let { it.width to it.height }
                val scale = (MAX_EDGE.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid photo")
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > MAX_EDGE) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Photo could not be opened")
    }
}
