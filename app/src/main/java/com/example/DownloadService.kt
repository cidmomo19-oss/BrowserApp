package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * DownloadService executes background video downloads with notifications.
 * Implements fallback chain: yt-dlp first, then OkHttp DoH for direct media files.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager
    private lateinit var ytDlpBridge: YtDlpBridge

    companion object {
        const val CHANNEL_ID = "browser_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.example.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.action.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FORMAT_ID = "extra_format_id"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_COOKIES = "extra_cookies"

        fun startDownload(
            context: Context,
            url: String,
            title: String,
            formatId: String,
            userAgent: String?,
            referer: String?,
            cookies: String?
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_COOKIES, cookies)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        ytDlpBridge = YtDlpBridge(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "video"
            val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "best"
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            val referer = intent.getStringExtra(EXTRA_REFERER)
            val cookies = intent.getStringExtra(EXTRA_COOKIES)

            startForeground(NOTIFICATION_ID, buildProgressNotification(title, 0, "Starting...", "N/A"))

            serviceScope.launch {
                executeDownload(url, title, formatId, userAgent, referer, cookies)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun executeDownload(
        url: String,
        title: String,
        formatId: String,
        userAgent: String?,
        referer: String?,
        cookies: String?
    ) {
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .take(50)
            .ifEmpty { "video_${System.currentTimeMillis()}" }

        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BrowserApp"
        )
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        val outputFile = File(downloadDir, "$sanitizedTitle.mp4")

        var ytDlpSucceeded = false

        // Attempt 1: yt-dlp via Chaquopy
        val callback = object : DownloadCallback {
            override fun onProgress(percent: Int, speed: String, eta: String) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(title, percent, "Speed: $speed", "ETA: $eta")
                )
            }

            override fun onFinished(filePath: String) {
                ytDlpSucceeded = true
                onDownloadCompleted(File(filePath))
            }

            override fun onError(errorMessage: String) {
                Log.w("DownloadService", "yt-dlp failed: $errorMessage. Initiating fallback chain...")
            }
        }

        ytDlpSucceeded = ytDlpBridge.downloadVideo(
            url = url,
            outputPath = outputFile.absolutePath,
            formatId = formatId,
            userAgent = userAgent,
            referer = referer,
            cookiesStr = cookies,
            callback = callback
        )

        // Attempt 2: Fallback to direct OkHttp DoH download if yt-dlp failed
        if (!ytDlpSucceeded) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadService, "yt-dlp fallback: Downloading via OkHttp DoH", Toast.LENGTH_SHORT).show()
            }
            downloadDirectFallback(url, outputFile, title, userAgent, referer, cookies)
        }
    }

    private suspend fun downloadDirectFallback(
        url: String,
        outputFile: File,
        title: String,
        userAgent: String?,
        referer: String?,
        cookies: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val client = DnsManager.getClient(this@DownloadService)
            val requestBuilder = Request.Builder().url(url)

            userAgent?.let { requestBuilder.addHeader("User-Agent", it) }
            referer?.let { requestBuilder.addHeader("Referer", it) }
            cookies?.let { requestBuilder.addHeader("Cookie", it) }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful || response.body == null) {
                showFailureNotification(title, "HTTP error ${response.code}")
                stopSelf()
                return@withContext
            }

            val body = response.body!!
            val contentLength = body.contentLength()
            var bytesReadTotal = 0L

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    var lastUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesReadTotal += bytes

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            val percent = if (contentLength > 0) ((bytesReadTotal * 100) / contentLength).toInt() else 0
                            val speedMb = (bytesReadTotal / (1024.0 * 1024.0))
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildProgressNotification(title, percent, String.format("%.1f MB", speedMb), "")
                            )
                        }
                    }
                }
            }

            onDownloadCompleted(outputFile)
        } catch (e: Exception) {
            Log.e("DownloadService", "Direct download failed", e)
            showFailureNotification(title, e.localizedMessage ?: "Download failed")
            stopSelf()
        }
    }

    private fun onDownloadCompleted(file: File) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4")
        ) { _, _ -> }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("Saved to Downloads/BrowserApp/${file.name}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showFailureNotification(title: String, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("$title: $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    private fun buildProgressNotification(
        title: String,
        progress: Int,
        speed: String,
        eta: String
    ): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading: $title")
            .setContentText("$speed $eta ($progress%)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of background video downloads"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
