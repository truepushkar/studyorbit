package com.studyorbit.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object DomainCatalog {
    private const val DOWNLOAD_URL =
        "https://github.com/truepushkar/studyorbit/releases/download/v2.3/StudyOrbit-domain-catalog.zip"
    private const val MAX_BYTES = 10 * 1024 * 1024
    private val downloadLock = Mutex()
    @Volatile private var ready: Boolean? = null

    private fun file(context: Context) = File(context.noBackupFilesDir, "domain-catalog-v1.zip")

    fun isReady(context: Context): Boolean = ready ?: synchronized(this) {
        ready ?: file(context).isFile.also { ready = it }
    }

    fun readEntry(context: Context, name: String): ByteArray? {
        if (!isReady(context)) return null
        return try {
            ZipFile(file(context)).use { zip ->
                val entry = zip.getEntry(name) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            if (isReady(context)) return@withLock
            val expected = context.assets.open("catalog-sha256.txt").bufferedReader().use { it.readText().trim() }
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val connection = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Catalog unavailable")
                val atomic = AtomicFile(file(context))
                val output = atomic.startWrite()
                var committed = false
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_BYTES) throw IOException("Catalog exceeded size limit")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    if (!MessageDigest.isEqual(digest.digest(), expected)) throw IOException("Catalog integrity check failed")
                    currentCoroutineContext().ensureActive()
                    atomic.finishWrite(output)
                    committed = true
                    if (!atomic.baseFile.isFile) throw IOException("Could not save catalog")
                    ready = true
                } finally {
                    if (!committed) atomic.failWrite(output)
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
