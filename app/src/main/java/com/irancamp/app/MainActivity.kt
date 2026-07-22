package com.irancamp.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar

class MainActivity : AppCompatActivity() {

    private val BASE_URL = "https://irancamp.online/"

    // دامنه‌هایی که داخل WebView باز می‌شوند (زرین‌پال حذف شد)
    private val allowedDomains = listOf(
        "irancamp.online",
        "idpay.ir",
        "zibal.ir",
        "nextpay.org",
        "shaparak.ir",
        "sep.shaparak.ir",
        "banktest.ir",
        "google.com",
        "accounts.google.com"
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult

        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                when {
                    data.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
        } else null

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                offlineLayout.visibility = View.GONE
                webView.reload()
            }
        }

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (intent?.data != null && intent.data?.scheme == "irancamp") {
            // اپ از طریق لینک بازگشت پرداخت باز شده
            handleDeepLink(intent)
        } else if (isOnline()) {
            webView.loadUrl(BASE_URL)
        } else {
            showOffline()
        }

        // وقتی اپ از قبل باز باشه (launchMode singleTask) و از لینک irancamp:// دوباره فراخوانی بشه
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null && intent.data?.scheme == "irancamp") {
            handleDeepLink(intent)
        }
    }

    // لینک irancamp://order-received?order_id=X&key=Y رو می‌گیره و صفحه‌ی نتیجه‌ی سفارش رو توی WebView لود می‌کنه
    private fun handleDeepLink(intent: Intent) {
        val data: Uri = intent.data ?: return
        val orderId = data.getQueryParameter("order_id")
        val key = data.getQueryParameter("key")

        val targetUrl = if (orderId != null && key != null) {
            "${BASE_URL}checkout/order-received/$orderId/?key=$key"
        } else {
            BASE_URL
        }
        webView.loadUrl(targetUrl)
    }

        swipeRefresh.setOnRefreshListener {
            if (isOnline()) webView.reload() else {
                swipeRefresh.isRefreshing = false
                showOffline()
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // هندل کردن بازگشت از مرورگر (Deep Link)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "irancamp") {   // یا هر اسکیمی که در سایت تعریف کردید
                webView.loadUrl(BASE_URL)     // یا آدرس صفحه مخصوص نتیجه پرداخت
                // می‌توانید وضعیت پرداخت را هم از uri بخوانید
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = "${settings.userAgentString} IranCampApp/1.0"

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val host = url.host ?: return false

                // === زرین‌پال را حتماً در مرورگر خارجی باز کن ===
                if (host.contains("zarinpal.com") || host.contains("www.zarinpal.com")) {
                    openInExternalBrowser(url)
                    return true
                }

                val isAllowed = allowedDomains.any { domain ->
                    host == domain || host.endsWith(".$domain")
                }

                return if (url.scheme == "http" || url.scheme == "https") {
                    if (isAllowed) false else {
                        openInExternalBrowser(url)
                        true
                    }
                } else {
                    openInExternalBrowser(url)
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) showOffline()
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) {
                handler.cancel()
            }
        }

        // بقیه کدهای WebChromeClient (آپلود فایل و window.open) بدون تغییر
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    return true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
            }
        }
    }

    private fun openInExternalBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {}
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    }

    private fun showOffline() {
        offlineLayout.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
