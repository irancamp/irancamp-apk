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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    // آدرس اصلی سایت - اگر بعداً خواستی تغییرش بدی فقط همینجا رو عوض کن
    private val BASE_URL = "https://irancamp.online/"

    // دامنه‌هایی که اجازه دارن داخل خود اپ (WebView) باز بشن
    private val allowedDomains = listOf(
        "irancamp.online",
        "google.com",
        "accounts.google.com"
    )

    // درصدی که به اون رسیدیم، لودینگ (اسپلش / بین صفحات) مخفی می‌شه
    private val HIDE_LOADING_AT_PROGRESS = 95

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var backButtonCard: MaterialCardView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
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
        loadingLayout = findViewById(R.id.loadingLayout)
        backButtonCard = findViewById(R.id.backButtonCard)

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                offlineLayout.visibility = View.GONE
                showLoadingOverlay()
                webView.reload()
            }
        }

        // دکمه‌ی بازگشت گوشه‌ی صفحه - دقیقاً همون رفتار دکمه‌ی بک اندروید/مرورگر: یه پله توی تاریخچه‌ی وب‌ویو برمی‌گرده
        backButtonCard.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
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

        swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                webView.reload()
            } else {
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

    // لودینگ تمام‌صفحه (لوگو + اسپینر نارنجی) رو نشون می‌ده - هم برای اسپلش اولیه، هم بین صفحات
    private fun showLoadingOverlay() {
        loadingLayout.animate().cancel()
        loadingLayout.alpha = 1f
        loadingLayout.visibility = View.VISIBLE
    }

    // لودینگ تمام‌صفحه رو با یه فید محو می‌کنه
    private fun hideLoadingOverlay() {
        if (loadingLayout.visibility != View.VISIBLE) return
        loadingLayout.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                loadingLayout.visibility = View.GONE
                loadingLayout.alpha = 1f
            }
            .start()
    }

    // دکمه‌ی بازگشت گوشه رو فقط وقتی نشون می‌ده که توی وب‌ویو صفحه‌ای برای برگشتن وجود داشته باشه
    // (دقیقاً مثل رفتار دکمه‌ی بک مرورگر که توی اولین صفحه غیرفعال/مخفیه)
    private fun updateBackButtonVisibility() {
        backButtonCard.visibility = if (webView.canGoBack()) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
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
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = settings.userAgentString + " IranCampApp/1.0"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: ActivityNotFoundException) { }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val host = url.host ?: return false

                val isAllowed = allowedDomains.any { domain ->
                    host == domain || host.endsWith(".$domain")
                }

                return if (url.scheme == "http" || url.scheme == "https") {
                    if (isAllowed) {
                        false
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (e: ActivityNotFoundException) { }
                        true
                    }
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: ActivityNotFoundException) { }
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // شروع لود هر صفحه (چه اولین بار، چه رفتن به صفحه‌ی دیگه‌ی سایت) -> نمایش لودینگ نارنجی
                showLoadingOverlay()
                updateBackButtonVisibility()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
                // فالبک: اگه به هر دلیلی onProgressChanged به ۹۵٪ نرسیده باشه، همینجا مخفی می‌کنیم
                hideLoadingOverlay()
                updateBackButtonVisibility()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    hideLoadingOverlay()
                    showOffline()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: android.net.http.SslError?
            ) {
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE

                // به محض رسیدن به ۹۵٪ لود صفحه، لودینگ نارنجی مخفی می‌شه و صفحه نمایش داده می‌شه
                if (newProgress >= HIDE_LOADING_AT_PROGRESS) {
                    hideLoadingOverlay()
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.javaScriptEnabled = true
                newWebView.settings.domStorageEnabled = true

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                newWebView.webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                        view.loadUrl(url)
                    }
                }
                return true
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOffline() {
        offlineLayout.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        hideLoadingOverlay()
        swipeRefresh.isRefreshing = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
