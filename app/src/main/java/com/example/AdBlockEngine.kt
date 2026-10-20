package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * AdBlockEngine handles network request filtering using EasyList rules
 * while explicitly protecting video CDN delivery domains.
 */
class AdBlockEngine(private val context: Context) {

    private val blockedDomains = HashSet<String>()
    private val blockedPatterns = ArrayList<String>()
    var isEnabled: Boolean = true

    // Whitelisted CDN and media hosting domains that must NEVER be blocked
    private val videoCdnWhitelist = listOf(
        "googlevideo.com",
        "youtube.com",
        "ytimg.com",
        "akamaized.net",
        "cloudfront.net",
        "fastly.net",
        "vimeocdn.com",
        "fbcdn.net",
        "cdninstagram.com",
        "tiktokcdn.com",
        "byteoversea.com",
        "twimg.com",
        "dailymotion.com",
        "m3u8",
        ".mp4",
        ".mpd"
    )

    init {
        loadFilterList()
    }

    private fun loadFilterList() {
        try {
            context.assets.open("easylist.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
                            continue
                        }
                        if (trimmed.startsWith("||")) {
                            val domainRule = trimmed.removePrefix("||").removeSuffix("^")
                            blockedDomains.add(domainRule.lowercase(Locale.ROOT))
                        } else if (trimmed.startsWith("/") && trimmed.endsWith("/")) {
                            blockedPatterns.add(trimmed)
                        } else {
                            blockedDomains.add(trimmed.lowercase(Locale.ROOT))
                        }
                    }
                }
            }
            Log.d("AdBlockEngine", "Loaded ${blockedDomains.size} adblock rules from EasyList")
        } catch (e: Exception) {
            Log.e("AdBlockEngine", "Failed to load easylist.txt", e)
        }
    }

    /**
     * Checks if the given URL should be blocked as an advertisement or tracker.
     */
    fun isAd(url: String): Boolean {
        if (!isEnabled) return false
        val lowerUrl = url.lowercase(Locale.ROOT)

        // 1. Strict whitelist check: Never block video streams or trusted CDNs
        for (whitelistDomain in videoCdnWhitelist) {
            if (lowerUrl.contains(whitelistDomain)) {
                return false
            }
        }

        // 2. Check host domain against blocked domains
        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (host != null) {
                if (blockedDomains.contains(host)) return true

                // Check subdomains
                for (domain in blockedDomains) {
                    if (host == domain || host.endsWith(".$domain")) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Check pattern matching
        for (pattern in blockedPatterns) {
            val cleanPattern = pattern.trim('/')
            if (lowerUrl.contains("/$cleanPattern/")) {
                return true
            }
        }

        return false
    }

    /**
     * Creates an empty WebResourceResponse to effectively block the request.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
