package com.example

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

/**
 * SniffedVideo represents a detected streaming or media URL from the web page.
 */
data class SniffedVideo(
    val url: String,
    val title: String,
    val pageUrl: String,
    val mimeType: String,
    val formatType: String, // "HLS (.m3u8)", "DASH (.mpd)", "MP4 Video"
    val timestamp: Long = System.currentTimeMillis()
)

interface VideoSnifferListener {
    fun onVideoDetected(video: SniffedVideo)
    fun onPageTitleChanged(title: String, url: String)
    fun onLoadingProgress(progress: Int)
}

/**
 * BrowserWebViewClient intercepts all network requests to:
 * 1. Block advertisements and trackers via AdBlockEngine.
 * 2. Sniff media streams (.m3u8, .mpd, .mp4, etc.) for downloading.
 */
class BrowserWebViewClient(
    private val adBlockEngine: AdBlockEngine,
    private val listener: VideoSnifferListener
) : WebViewClient() {

    private val detectedUrls = HashSet<String>()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val requestUrl = request?.url?.toString() ?: return null

        // 1. Check AdBlock
        if (adBlockEngine.isAd(requestUrl)) {
            Log.d("BrowserWebViewClient", "Blocked Ad: $requestUrl")
            return adBlockEngine.createEmptyResponse()
        }

        // 2. Sniff Video / Streaming Media
        checkAndSniffMedia(view, requestUrl)

        return super.shouldInterceptRequest(view, request)
    }

    private fun checkAndSniffMedia(view: WebView?, url: String) {
        val lowerUrl = url.lowercase(Locale.ROOT)

        val isHls = lowerUrl.contains(".m3u8")
        val isDash = lowerUrl.contains(".mpd")
        val isMp4 = lowerUrl.contains(".mp4") || lowerUrl.contains(".m4v") || lowerUrl.contains(".webm")

        if (isHls || isDash || isMp4) {
            if (!detectedUrls.contains(url)) {
                detectedUrls.add(url)
                val formatType = when {
                    isHls -> "HLS Stream (.m3u8)"
                    isDash -> "DASH Stream (.mpd)"
                    else -> "Direct Video (.mp4)"
                }
                val mimeType = when {
                    isHls -> "application/x-mpegURL"
                    isDash -> "application/dash+xml"
                    else -> "video/mp4"
                }

                view?.post {
                    val pageTitle = view.title?.ifEmpty { "Web Video" } ?: "Web Video"
                    val pageUrl = view.url ?: ""
                    val video = SniffedVideo(
                        url = url,
                        title = pageTitle,
                        pageUrl = pageUrl,
                        mimeType = mimeType,
                        formatType = formatType
                    )
                    listener.onVideoDetected(video)
                }
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        listener.onLoadingProgress(20)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        listener.onLoadingProgress(100)
        view?.let {
            val title = it.title ?: url ?: ""
            listener.onPageTitleChanged(title, url ?: "")
        }
    }

    fun clearDetectedVideos() {
        detectedUrls.clear()
    }
}
