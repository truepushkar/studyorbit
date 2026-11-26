package com.studyorbit.app

import android.content.Context
import android.util.LruCache

private val fallbackAdultSites = setOf(
    "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "youporn.com", "redtube.com",
    "tube8.com", "spankbang.com", "eporner.com", "youjizz.com", "brazzers.com", "tnaflix.com",
    "chaturbate.com", "stripchat.com", "livejasmin.com", "cam4.com", "camsoda.com", "myfreecams.com"
)

internal class AdultDomains(private val context: Context) {
    private val blocks = LruCache<Int, DomainBlock>(8)
    @Volatile private var lowerBounds: List<String>? = null

    fun cachedMatch(host: String): Boolean? = match(host, readAssets = false)

    fun loadAndMatch(host: String): Boolean = match(host, readAssets = true) == true

    private fun match(host: String, readAssets: Boolean): Boolean? {
        val available = DomainCatalog.isReady(context)
        val bounds = lowerBounds ?: if (available && readAssets) loadBounds() else null
        var suffix = host
        var missing = false
        while ('.' in suffix) {
            if (suffix in fallbackAdultSites) return true
            if (bounds == null) {
                missing = available
            } else {
                val position = bounds.binarySearch(suffix)
                val blockId = if (position >= 0) position else -position - 2
                if (blockId >= 0) {
                    val block = blocks.get(blockId) ?: if (readAssets) loadBlock(blockId) else null
                    if (block == null) missing = true else if (suffix in block) return true
                }
            }
            suffix = suffix.substringAfter('.')
        }
        return if (missing) null else false
    }

    private fun loadBounds(): List<String> = synchronized(this) {
        lowerBounds ?: DomainCatalog.readEntry(context, "index.txt")
            ?.inputStream()?.bufferedReader(Charsets.US_ASCII)?.use { it.readLines() }
            .orEmpty().also { lowerBounds = it }
    }

    private fun loadBlock(id: Int): DomainBlock {
        val bytes = DomainCatalog.readEntry(context, "$id.txt") ?: ByteArray(0)
        return DomainBlock(bytes).also { blocks.put(id, it) }
    }
}
