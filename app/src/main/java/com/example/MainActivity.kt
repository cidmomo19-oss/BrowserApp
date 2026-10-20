package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogResolutionPickerBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.net.URI

class MainActivity : AppCompatActivity(), VideoSnifferListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var ytDlpBridge: YtDlpBridge

    // Tabs state
    data class BrowserTab(
        val id: Int,
        var title: String,
        val webView: WebView,
        val detectedVideos: MutableList<SniffedVideo> = mutableListOf()
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var nextTabId = 1

    // Anti-blocking User Agents
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private var isDesktopMode = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Notification permission needed for download progress", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adBlockEngine = AdBlockEngine(this)
        ytDlpBridge = YtDlpBridge(this)

        checkNotificationPermission()
        setupUI()

        // Create Tab 1 and Tab 2 (minimal 2 tabs supported)
        createNewTab("https://duckduckgo.com")
        createNewTab("https://commons.wikimedia.org/wiki/Category:Videos")

        // Switch to first tab
        selectTab(0)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupUI() {
        // Search / URL input
        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadEnteredUrl()
                true
            } else {
                false
            }
        }

        binding.btnReload.setOnClickListener {
            getActiveWebView()?.reload()
        }

        // User Agent switcher (Anti-Blocking Strategy 1)
        binding.btnUserAgent.setOnClickListener {
            isDesktopMode = !isDesktopMode
            binding.btnUserAgent.text = if (isDesktopMode) "💻 Desktop" else "📱 Mobile"
            updateUserAgentForActiveTab()
            getActiveWebView()?.reload()
            Toast.makeText(
                this,
                if (isDesktopMode) "Switched to Desktop User-Agent" else "Switched to Mobile User-Agent",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Adblock Toggle
        binding.btnAdblock.setOnClickListener {
            adBlockEngine.isEnabled = !adBlockEngine.isEnabled
            val status = if (adBlockEngine.isEnabled) "Enabled (Blocking Ads)" else "Disabled"
            binding.btnAdblock.setColorFilter(
                ContextCompat.getColor(this, if (adBlockEngine.isEnabled) android.R.color.holo_green_light else android.R.color.darker_gray)
            )
            Toast.makeText(this, "AdBlock $status", Toast.LENGTH_SHORT).show()
            getActiveWebView()?.reload()
        }

        // New Tab Button
        binding.btnNewTab.setOnClickListener {
            createNewTab("https://duckduckgo.com")
            selectTab(tabs.size - 1)
        }

        // Navigation buttons
        binding.btnBack.setOnClickListener {
            val webView = getActiveWebView()
            if (webView != null && webView.canGoBack()) {
                webView.goBack()
            }
        }

        binding.btnForward.setOnClickListener {
            val webView = getActiveWebView()
            if (webView != null && webView.canGoForward()) {
                webView.goForward()
            }
        }

        binding.btnHome.setOnClickListener {
            getActiveWebView()?.loadUrl("https://duckduckgo.com")
        }

        // Floating Action Button - Opens Resolution Picker for Sniffed Video
        binding.fabDownload.setOnClickListener {
            showDetectedVideosDialog()
        }
    }

    private fun loadEnteredUrl() {
        val query = binding.etUrl.text.toString().trim()
        if (query.isEmpty()) return

        val finalUrl = when {
            query.startsWith("http://") || query.startsWith("https://") -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        }
        getActiveWebView()?.loadUrl(finalUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(initialUrl: String): BrowserTab {
        val tabId = nextTabId++
        val webView = WebView(this)

        // Configure modern WebView capabilities
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
        }

        // Enable Cookies (Anti-Blocking Strategy 2)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val client = BrowserWebViewClient(adBlockEngine, this)
        webView.webViewClient = client

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onLoadingProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { onPageTitleChanged(it, view?.url ?: "") }
            }
        }

        val tab = BrowserTab(
            id = tabId,
            title = "Tab $tabId",
            webView = webView
        )
        tabs.add(tab)

        renderTabStrip()
        webView.loadUrl(initialUrl)
        return tab
    }

    private fun selectTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index

        // Update container view
        binding.webviewContainer.removeAllViews()
        val currentTab = tabs[activeTabIndex]
        binding.webviewContainer.addView(currentTab.webView)

        // Update URL bar
        binding.etUrl.setText(currentTab.webView.url ?: "")
        updateFabBadge()
        renderTabStrip()
    }

    private fun renderTabStrip() {
        binding.layoutTabContainer.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val chip = Chip(this).apply {
                text = tab.title.take(15)
                isCheckable = false
                isCloseIconVisible = tabs.size > 1
                setChipBackgroundColorResource(
                    if (index == activeTabIndex) android.R.color.holo_blue_dark else android.R.color.darker_gray
                )
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setOnClickListener { selectTab(index) }
                setOnCloseIconClickListener { closeTab(index) }
            }
            binding.layoutTabContainer.addView(chip)
        }
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        val tabToRemove = tabs[index]
        tabToRemove.webView.destroy()
        tabs.removeAt(index)

        if (activeTabIndex >= tabs.size) {
            activeTabIndex = tabs.size - 1
        }
        selectTab(activeTabIndex)
    }

    private fun updateUserAgentForActiveTab() {
        val webView = getActiveWebView() ?: return
        webView.settings.userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
    }

    private fun getActiveWebView(): WebView? {
        return if (activeTabIndex in tabs.indices) tabs[activeTabIndex].webView else null
    }

    // VideoSnifferListener implementation
    override fun onVideoDetected(video: SniffedVideo) {
        if (activeTabIndex in tabs.indices) {
            val currentTab = tabs[activeTabIndex]
            if (currentTab.detectedVideos.none { it.url == video.url }) {
                currentTab.detectedVideos.add(video)
                updateFabBadge()
                Toast.makeText(this, "🎬 Media detected: ${video.formatType}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPageTitleChanged(title: String, url: String) {
        if (activeTabIndex in tabs.indices) {
            tabs[activeTabIndex].title = title.ifEmpty { "Tab ${tabs[activeTabIndex].id}" }
            renderTabStrip()
            binding.etUrl.setText(url)
        }
    }

    override fun onLoadingProgress(progress: Int) {
        if (progress < 100) {
            binding.loadingProgress.visibility = View.VISIBLE
            binding.loadingProgress.progress = progress
        } else {
            binding.loadingProgress.visibility = View.GONE
        }
    }

    private fun updateFabBadge() {
        if (activeTabIndex in tabs.indices) {
            val count = tabs[activeTabIndex].detectedVideos.size
            binding.tvSnifferStatus.text = "Videos: $count"
            if (count > 0) {
                binding.fabDownload.visibility = View.VISIBLE
                binding.fabDownload.text = "Download ($count)"
            } else {
                binding.fabDownload.visibility = View.GONE
            }
        }
    }

    /**
     * Shows dialog with detected video streams and prompts for HLS Resolution Picker.
     */
    private fun showDetectedVideosDialog() {
        val currentTab = tabs.getOrNull(activeTabIndex) ?: return
        val videos = currentTab.detectedVideos
        if (videos.isEmpty()) {
            Toast.makeText(this, "No videos detected yet on this page", Toast.LENGTH_SHORT).show()
            return
        }

        val items = videos.map { "${it.formatType} - ${it.title.take(30)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Detected Media Streams (${videos.size})")
            .setItems(items) { _, which ->
                val selectedVideo = videos[which]
                openResolutionPickerDialog(selectedVideo)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Parses HLS / media stream formats and presents resolution options (1080p, 720p, 480p, etc.).
     */
    private fun openResolutionPickerDialog(video: SniffedVideo) {
        val dialogBinding = DialogResolutionPickerBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvDialogTitle.text = video.title.take(40)
        dialogBinding.tvDialogSubtitle.text = "Stream URL: ${video.url.take(60)}..."
        dialogBinding.pbDialogLoading.visibility = View.VISIBLE

        // Extract Cookies and Referer (Anti-Blocking Strategy 1 & 2)
        val cookies = CookieManager.getInstance().getCookie(video.pageUrl)
        val referer = try {
            val uri = URI(video.pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            video.pageUrl
        }
        val userAgent = getActiveWebView()?.settings?.userAgentString ?: mobileUserAgent

        var availableFormats = listOf<VideoFormat>()

        lifecycleScope.launch {
            // Use yt-dlp to inspect stream formats and resolutions
            val videoInfo = ytDlpBridge.getVideoInfo(
                url = video.url,
                userAgent = userAgent,
                referer = referer,
                cookiesStr = cookies
            )

            dialogBinding.pbDialogLoading.visibility = View.GONE
            availableFormats = videoInfo.formats

            dialogBinding.rgResolutions.removeAllViews()
            availableFormats.forEachIndexed { index, format ->
                val radioButton = RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = "${format.resolution} (${format.ext})"
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    isChecked = index == 0
                }
                dialogBinding.rgResolutions.addView(radioButton)
            }
        }

        dialogBinding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDialogDownload.setOnClickListener {
            val selectedRadioId = dialogBinding.rgResolutions.checkedRadioButtonId
            val selectedFormat = if (selectedRadioId != -1) {
                val index = dialogBinding.rgResolutions.indexOfChild(dialogBinding.rgResolutions.findViewById(selectedRadioId))
                availableFormats.getOrNull(index)?.formatId ?: "best"
            } else {
                "best"
            }

            // Start background DownloadService
            DownloadService.startDownload(
                context = this@MainActivity,
                url = video.url,
                title = video.title,
                formatId = selectedFormat,
                userAgent = userAgent,
                referer = referer,
                cookies = cookies
            )

            Toast.makeText(this@MainActivity, "Download started in background", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val webView = getActiveWebView()
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
