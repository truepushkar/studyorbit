package com.studyorbit.app

import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import java.net.IDN
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

internal data class ContentRules(
    val blockedHosts: Set<String> = emptySet(),
    val allowedHosts: Set<String> = emptySet(),
    val adultFilter: Boolean = false,
    val browserStudy: Boolean = false,
    val youtubeStudy: Boolean = false,
    val channels: Set<String> = emptySet(),
    val lockedUntil: Long = 0
) {
    val isLocked get() = lockedUntil > System.currentTimeMillis()

    fun hostBlock(host: String, studying: Boolean, isAdultDomain: (String) -> Boolean): String? = when {
        host.matchesDomains(blockedHosts) -> "Website blocked"
        adultFilter && isAdultDomain(host) -> "Adult website blocked"
        browserStudy && studying && !host.matchesDomains(allowedHosts) -> "This site is outside your study list"
        else -> null
    }
}

internal fun readContentRules(prefs: SharedPreferences) = ContentRules(
    blockedHosts = prefs.getStringSet("blocked_hosts", emptySet()).orEmpty(),
    allowedHosts = prefs.getStringSet("allowed_hosts", emptySet()).orEmpty(),
    adultFilter = prefs.getBoolean("adult_filter", false),
    browserStudy = prefs.getBoolean("browser_study", false),
    youtubeStudy = prefs.getBoolean("youtube_study", false),
    channels = prefs.getStringSet("study_channels", emptySet()).orEmpty(),
    lockedUntil = prefs.getLong("content_lock", 0)
)

internal fun FocusStore.saveContentRules(rules: ContentRules): Boolean {
    val previous = readContentRules(prefs)
    if (strictWindow() || previous.isLocked) return false
    if (rules.adultFilter && !previous.adultFilter && !DomainCatalog.isReady(appContext)) return false
    prefs.edit().putStringSet("blocked_hosts", rules.blockedHosts).putStringSet("allowed_hosts", rules.allowedHosts)
        .putStringSet("study_channels", rules.channels).putBoolean("adult_filter", rules.adultFilter)
        .putBoolean("browser_study", rules.browserStudy).putBoolean("youtube_study", rules.youtubeStudy)
        .putLong("content_lock", rules.lockedUntil).apply()
    return true
}

internal fun websiteHost(value: String): String? {
    val input = value.trim()
    if (input.isBlank() || input.any(Char::isWhitespace)) return null
    return runCatching {
        val uri = URI(if ("://" in input) input else "https://$input")
        if (uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val rawHost = uri.host ?: uri.rawAuthority?.takeUnless { ':' in it || '@' in it } ?: return null
        IDN.toASCII(rawHost.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            .takeIf { '.' in it || it == "localhost" }
    }.getOrNull()
}

private fun String.matchesDomains(domains: Set<String>): Boolean {
    var suffix = this
    while ('.' in suffix) {
        if (suffix in domains) return true
        suffix = suffix.substringAfter('.')
    }
    return suffix in domains
}

internal fun channelKey(value: String) = value.trim()
    .removePrefix("https://www.youtube.com/").removePrefix("https://youtube.com/")
    .trimEnd('/').removePrefix("@").lowercase(Locale.ROOT)

internal data class ScreenView(
    val id: String, val text: String, val description: String, val editing: Boolean, val selected: Boolean
)

internal fun visibleScreen(
    root: AccessibilityNodeInfo,
    neededIds: Set<String>,
    readLabels: Set<String>,
    stopOnIds: Set<String>
): List<ScreenView> = buildList {
    val nodes = ArrayDeque<AccessibilityNodeInfo>()
    nodes.add(root)
    var inspected = 0
    while (nodes.isNotEmpty() && inspected++ < 500) {
        val node = nodes.removeFirst()
        if (node.isVisibleToUser) {
            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (id in neededIds || id in stopOnIds) {
                val text = if (id in readLabels) node.text?.toString().orEmpty() else ""
                val description = if (id in readLabels) node.contentDescription?.toString().orEmpty() else ""
                add(ScreenView(id, text, description, node.isEditable && node.isFocused, node.isSelected))
                if (id in stopOnIds) break
            }
        }
        for (index in 0 until node.childCount) {
            if (inspected + nodes.size >= 500) break
            node.getChild(index)?.let(nodes::addLast)
        }
    }
}

private val browserAddressIds = mapOf(
    "com.android.chrome" to setOf("url_bar"),
    "com.chrome.beta" to setOf("url_bar"),
    "com.brave.browser" to setOf("url_bar"),
    "com.microsoft.emmx" to setOf("url_bar"),
    "com.vivaldi.browser" to setOf("url_bar"),
    "com.sec.android.app.sbrowser" to setOf("location_bar_edit_text", "url_bar"),
    "org.mozilla.firefox" to setOf("mozac_browser_toolbar_url_view", "url_bar"),
    "org.mozilla.fenix" to setOf("mozac_browser_toolbar_url_view", "url_bar")
)

internal fun hasBrowserSupport(pkg: String) = pkg in browserAddressIds
internal val supportedBrowserPackages: Set<String> get() = browserAddressIds.keys
internal fun browserLabelIds(pkg: String) = browserAddressIds[pkg].orEmpty()

internal fun browserHost(pkg: String, views: List<ScreenView>): String? {
    val ids = browserAddressIds[pkg] ?: return null
    return views.asSequence().filter { it.id in ids && !it.editing }
        .mapNotNull { websiteHost(it.text.ifBlank { it.description }) }.firstOrNull()
}

private val youtubeOwnerIds = setOf("owner_name", "video_owner_name", "channel_name", "channel_handle")
internal val youtubeLabelIds = youtubeOwnerIds + setOf("channel_title", "pivot_bar_item")
private val youtubeSearchIds = setOf("search_edit_text", "search_container", "search_results", "search_box")
private val youtubeWatchIds = setOf("watch_panel", "watch_while_player", "player_view", "video_metadata_carousel")
private val youtubeHomeIds = setOf("home_feed", "home_container")
internal val youtubeStudyIds = youtubeLabelIds + youtubeSearchIds + youtubeWatchIds + youtubeHomeIds

internal fun youtubeStudyBlock(views: List<ScreenView>, channels: Set<String>): String? {
    val search = views.any { it.id in youtubeSearchIds }
    val watch = views.any { it.id in youtubeWatchIds }
    val home = views.any { it.id in youtubeHomeIds || (it.id == "pivot_bar_item" && it.selected &&
        (it.text.equals("Home", true) || it.description.equals("Home", true))) }
    val channelIds = if (watch) youtubeOwnerIds else youtubeLabelIds - "pivot_bar_item"
    val allowedChannel = views.any { view ->
        view.id in channelIds && listOf(view.text, view.description).any { channelKey(it) in channels }
    }
    return when {
        watch && !allowedChannel -> "Choose a video from a study channel"
        watch || search -> null
        home && !allowedChannel -> "Open search or a study channel"
        else -> null
    }
}

internal data class VideoTarget(val label: String, val pkg: String, val key: String, val ids: Set<String>) {
    fun mode(prefs: SharedPreferences) = prefs.getInt(key, 0)
}

internal val videoTargets = listOf(
    VideoTarget("YouTube Shorts", YOUTUBE, "shorts_mode", setOf(
        "reel_player_page_container", "reel_player_page", "reel_video_player", "reel_watch_fragment_root",
        "reel_player_overlay", "reel_recycler", "shorts_player_view", "shorts_player_page_container"
    )),
    VideoTarget("Instagram Reels", "com.instagram.android", "reels_mode", setOf("clips_viewer_view_pager")),
    VideoTarget("Snapchat Spotlight", "com.snapchat.android", "spotlight_mode", setOf("spotlight_container", "spotlight_feed_container")),
    VideoTarget("Facebook Reels", "com.facebook.katana", "facebook_reels_mode", setOf(
        "reels_viewer", "reels_viewer_container", "reels_video_player", "fb_shorts_player"
    ))
)
