package com.example

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class VideoFormat(
    val formatId: String,
    val resolution: String,
    val height: Int,
    val ext: String,
    val url: String = "",
    val filesize: Long = 0
)

data class VideoInfo(
    val success: Boolean,
    val title: String,
    val thumbnail: String,
    val duration: Int,
    val formats: List<VideoFormat>,
    val error: String? = null
)

interface DownloadCallback {
    fun onProgress(percent: Int, speed: String, eta: String)
    fun onFinished(filePath: String)
    fun onError(errorMessage: String)
}

/**
 * YtDlpBridge provides a thread-safe Kotlin API over Chaquopy Python yt-dlp module.
 */
class YtDlpBridge(private val context: Context) {

    private val tag = "YtDlpBridge"
    private var isPythonReady = false

    init {
        initPython()
    }

    private fun initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            isPythonReady = true
            Log.d(tag, "Chaquopy Python runtime initialized successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize Chaquopy Python: ${e.message}", e)
            isPythonReady = false
        }
    }

    /**
     * Extracts formats and resolutions from the URL using yt-dlp in Python.
     */
    suspend fun getVideoInfo(
        url: String,
        userAgent: String? = null,
        referer: String? = null,
        cookiesStr: String? = null
    ): VideoInfo = withContext(Dispatchers.IO) {
        if (!isPythonReady) {
            initPython()
        }

        if (!isPythonReady) {
            // Provide sensible fallback formats if Python is not ready
            return@withContext fallbackVideoInfo(url, "Python runtime unavailable")
        }

        try {
            val py = Python.getInstance()
            val downloaderModule = py.getModule("downloader")
            val pyResult = downloaderModule.callAttr(
                "get_video_info",
                url,
                userAgent,
                referer,
                cookiesStr
            )

            val jsonStr = pyResult.toString()
            val jsonObj = JSONObject(jsonStr)

            val success = jsonObj.optBoolean("success", false)
            if (!success) {
                val errorMsg = jsonObj.optString("error", "Failed to extract formats")
                return@withContext fallbackVideoInfo(url, errorMsg)
            }

            val title = jsonObj.optString("title", "Video Media")
            val thumbnail = jsonObj.optString("thumbnail", "")
            val duration = jsonObj.optInt("duration", 0)

            val formatsArray = jsonObj.optJSONArray("formats")
            val formatList = mutableListOf<VideoFormat>()

            if (formatsArray != null) {
                for (i in 0 until formatsArray.length()) {
                    val item = formatsArray.getJSONObject(i)
                    formatList.add(
                        VideoFormat(
                            formatId = item.optString("format_id", "best"),
                            resolution = item.optString("resolution", "Default"),
                            height = item.optInt("height", 0),
                            ext = item.optString("ext", "mp4"),
                            url = item.optString("url", ""),
                            filesize = item.optLong("filesize", 0)
                        )
                    )
                }
            }

            if (formatList.isEmpty()) {
                formatList.add(VideoFormat("best", "Original / Best", 1080, "mp4"))
            }

            VideoInfo(
                success = true,
                title = title,
                thumbnail = thumbnail,
                duration = duration,
                formats = formatList
            )
        } catch (e: Exception) {
            Log.e(tag, "Error extracting video info via yt-dlp", e)
            fallbackVideoInfo(url, e.localizedMessage)
        }
    }

    /**
     * Executes download of the chosen format to the target output path.
     */
    suspend fun downloadVideo(
        url: String,
        outputPath: String,
        formatId: String,
        userAgent: String?,
        referer: String?,
        cookiesStr: String?,
        callback: DownloadCallback
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isPythonReady) {
            initPython()
        }

        if (!isPythonReady) {
            callback.onError("Chaquopy Python is not initialized")
            return@withContext false
        }

        try {
            val py = Python.getInstance()
            val downloaderModule = py.getModule("downloader")

            // Create a Python proxy callback
            val pyCallback = object {
                @Suppress("unused")
                fun onProgress(percent: Int, speed: String, eta: String) {
                    callback.onProgress(percent, speed, eta)
                }

                @Suppress("unused")
                fun onFinished(filePath: String) {
                    callback.onFinished(filePath)
                }

                @Suppress("unused")
                fun onError(errorMessage: String) {
                    callback.onError(errorMessage)
                }
            }

            val result = downloaderModule.callAttr(
                "download_stream",
                url,
                outputPath,
                formatId,
                userAgent,
                referer,
                cookiesStr,
                pyCallback
            )

            result.toBoolean()
        } catch (e: Exception) {
            Log.e(tag, "Error in downloadVideo via yt-dlp", e)
            callback.onError(e.localizedMessage ?: "Unknown download error")
            false
        }
    }

    private fun fallbackVideoInfo(url: String, reason: String?): VideoInfo {
        val formats = listOf(
            VideoFormat("best", "Original / Best", 1080, "mp4", url),
            VideoFormat("1080p", "1080p Full HD", 1080, "mp4", url),
            VideoFormat("720p", "720p HD", 720, "mp4", url),
            VideoFormat("480p", "480p SD", 480, "mp4", url),
            VideoFormat("360p", "360p Low", 360, "mp4", url)
        )
        return VideoInfo(
            success = true,
            title = "Direct Video Stream",
            thumbnail = "",
            duration = 0,
            formats = formats,
            error = reason
        )
    }
}
