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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    // آدرس اصلی سایت - اگر بعداً خواستی تغییرش بدی فقط همینجا رو عوض کن
    private val BASE_URL = "https://irancamp.online/"

    // آدرس صفحه‌ی علاقه‌مندی‌ها - خود سایت اگر کاربر وارد نشده باشد به صفحه‌ی ورود ریدایرکت می‌کند
    private val FAVORITE_URL = "https://irancamp.online/account/favorite_camps/"

    // الگوی تشخیص صفحات محصول/تور ووکامرس: irancamp.online/product/xxx/
    private val PRODUCT_URL_REGEX = Regex(
        "^https://irancamp\\.online/product/[^/?#]+/?(?:[?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    // دامنه‌هایی که اجازه دارن داخل خود اپ (WebView) باز بشن
    private val allowedDomains = listOf(
        "irancamp.online"
    )

    // درصدی که به اون رسیدیم، لودینگ (اسپلش / بین صفحات) مخفی می‌شه
    private val HIDE_LOADING_AT_PROGRESS = 95

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var backButtonCard: MaterialCardView

    // نوار جستجوی خانه
    private lateinit var homeSearchBar: MaterialCardView
    private lateinit var homeSearchInput: EditText
    private lateinit var homeSearchIcon: ImageView

    // نوار ابزار صفحه محصول
    private lateinit var productTopBar: LinearLayout
    private lateinit var productBackButtonCard: MaterialCardView
    private lateinit var shareButtonCard: MaterialCardView
    private lateinit var favoriteButtonCard: MaterialCardView

    // نوع صفحه‌ی فعلی، برای تصمیم‌گیری در مورد نمایش هدرها
    private enum class PageMode { HOME, PRODUCT, OTHER }
    private var currentPageMode: PageMode = PageMode.OTHER

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
        // نصب Splash Screen استاندارد اندروید - باید قبل از super.onCreate و setContentView باشه
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)
        loadingLayout = findViewById(R.id.loadingLayout)
        backButtonCard = findViewById(R.id.backButtonCard)

        homeSearchBar = findViewById(R.id.homeSearchBar)
        homeSearchInput = findViewById(R.id.homeSearchInput)
        homeSearchIcon = findViewById(R.id.homeSearchIcon)

        productTopBar = findViewById(R.id.productTopBar)
        productBackButtonCard = findViewById(R.id.productBackButtonCard)
        shareButtonCard = findViewById(R.id.shareButtonCard)
        favoriteButtonCard = findViewById(R.id.favoriteButtonCard)

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                offlineLayout.visibility = View.GONE
                showLoadingOverlay()
                webView.reload()
            }
        }

        // دکمه‌ی بازگشت شناور (صفحات غیر از محصول) - یه پله توی تاریخچه‌ی وب‌ویو برمی‌گرده
        backButtonCard.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        // دکمه‌ی بازگشت داخل نوار ابزار محصول - همون رفتار، فقط جای دیگه
        productBackButtonCard.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        // دکمه‌ی اشتراک‌گذاری - از سیستم Share بومی اندروید استفاده می‌کنه
        shareButtonCard.setOnClickListener {
            shareCurrentPage()
        }

        // دکمه‌ی علاقه‌مندی - می‌ره به صفحه‌ی علاقه‌مندی‌های سایت
        // (خود سایت اگه کاربر وارد نشده باشه به صفحه‌ی ورود ریدایرکت می‌کنه)
        favoriteButtonCard.setOnClickListener {
            webView.loadUrl(FAVORITE_URL)
        }

        // نوار جستجوی خانه: هم دکمه‌ی اینتر کیبورد هم آیکون ذره‌بین کار می‌کنن
        homeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performHomeSearch()
                true
            } else {
                false
            }
        }
        homeSearchIcon.setOnClickListener {
            performHomeSearch()
        }

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            handleLaunchIntent(intent)
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

    // وقتی اپ از قبل باز باشه (launchMode singleTask) و از لینکی دوباره فراخوانی بشه
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, isNewIntent = true)
    }

    // تصمیم می‌گیره اپ با چه آدرسی باز بشه: لینک بازگشت از درگاه، App Link سایت، یا صفحه‌ی خانه
    private fun handleLaunchIntent(intent: Intent?, isNewIntent: Boolean = false) {
        val data = intent?.data

        when {
            data != null && data.scheme == "irancamp" -> {
                handlePaymentDeepLink(intent)
            }
            data != null && (data.scheme == "https" || data.scheme == "http") && data.host == "irancamp.online" -> {
                // App Link: کاربر روی یه لینک irancamp.online زده (مثلاً از تلگرام) و اپ مستقیم بازش می‌کنه
                webView.loadUrl(data.toString())
            }
            !isNewIntent -> {
                // اجرای عادی اپ از صفحه‌ی هوم
                if (isOnline()) {
                    webView.loadUrl(BASE_URL)
                } else {
                    showOffline()
                }
            }
        }
    }

    // لینک irancamp://order-received?order_id=X&key=Y رو می‌گیره و صفحه‌ی نتیجه‌ی سفارش رو توی WebView لود می‌کنه
    private fun handlePaymentDeepLink(intent: Intent) {
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

    // جستجوی صفحه‌ی خانه: متن رو می‌گیره و به آدرس جستجوی سایت هدایت می‌کنه
    private fun performHomeSearch() {
        val query = homeSearchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            homeSearchInput.requestFocus()
            return
        }
        hideKeyboard()
        val searchUrl = BASE_URL + "?s=" + Uri.encode(query)
        webView.loadUrl(searchUrl)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(homeSearchInput.windowToken, 0)
    }

    // اشتراک‌گذاری لینک صفحه‌ی فعلی با سیستم Share بومی اندروید
    private fun shareCurrentPage() {
        val currentUrl = webView.url ?: BASE_URL
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        } catch (e: ActivityNotFoundException) { }
    }

    // آدرس رو با الگوهای صفحه خانه/محصول مقایسه می‌کنه و نوع صفحه رو برمی‌گردونه
    private fun detectPageMode(url: String?): PageMode {
        if (url == null) return PageMode.OTHER
        val normalized = url.trim()
        val homeVariants = listOf(BASE_URL, BASE_URL.trimEnd('/'))
        return when {
            homeVariants.contains(normalized) -> PageMode.HOME
            PRODUCT_URL_REGEX.matches(normalized) -> PageMode.PRODUCT
            else -> PageMode.OTHER
        }
    }

    // بر اساس نوع صفحه، نوار جستجو / نوار ابزار محصول / هیچ‌کدام رو نشون می‌ده
    private fun updateTopBarForUrl(url: String?) {
        currentPageMode = detectPageMode(url)
        when (currentPageMode) {
            PageMode.HOME -> {
                homeSearchBar.visibility = View.VISIBLE
                productTopBar.visibility = View.GONE
            }
            PageMode.PRODUCT -> {
                homeSearchBar.visibility = View.GONE
                productTopBar.visibility = View.VISIBLE
            }
            PageMode.OTHER -> {
                homeSearchBar.visibility = View.GONE
                productTopBar.visibility = View.GONE
            }
        }
        updateBackButtonVisibility()
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

    // دکمه‌ی بازگشت شناور رو فقط توی صفحاتی نشون می‌ده که نه «خانه»ان نه «محصول»
    // (خانه فقط نوار جستجو داره، محصول دکمه‌ی بازگشت خودش رو توی productTopBar داره)
    private fun updateBackButtonVisibility() {
        val canGoBack = webView.canGoBack()
        backButtonCard.visibility =
            if (canGoBack && currentPageMode == PageMode.OTHER) View.VISIBLE else View.GONE
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
                updateTopBarForUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
                // فالبک: اگه به هر دلیلی onProgressChanged به ۹۵٪ نرسیده باشه، همینجا مخفی می‌کنیم
                hideLoadingOverlay()
                updateTopBarForUrl(url)
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
