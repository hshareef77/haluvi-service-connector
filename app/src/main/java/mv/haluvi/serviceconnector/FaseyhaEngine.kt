package mv.haluvi.serviceconnector

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * A Faseyha browser the app drives in the background. It shares the signed-in
 * cookie session with the visible website screen, stays on faseyha.net and is
 * hidden from TalkBack unless the person chooses to show the provider page.
 */
@SuppressLint("SetJavaScriptEnabled")
class FaseyhaEngine(context: Context, private val onLoaded: (String) -> Unit) {
    val web: WebView = WebView(context)
    private var destroyed = false

    init {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !FaseyhaSite.trustedUrl(request.url.toString())

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (!destroyed) onLoaded(url)
            }
        }
    }

    val url: String get() = web.url ?: ""
    val path: String? get() = FaseyhaSite.pathOf(web.url)

    fun open(path: String) { if (!destroyed) web.loadUrl(FaseyhaSite.BASE + path) }
    fun openUrl(url: String) { if (!destroyed && FaseyhaSite.trustedUrl(url)) web.loadUrl(url) }
    fun reload() { if (!destroyed) web.reload() }

    fun eval(script: String, done: (JSONObject?) -> Unit) {
        if (destroyed) return
        web.evaluateJavascript(script) { raw -> if (!destroyed) done(FaseyhaSite.decode(raw)) }
    }

    fun after(ms: Long, work: () -> Unit) { web.postDelayed({ if (!destroyed) work() }, ms) }

    fun setShown(shown: Boolean) {
        web.importantForAccessibility = if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun destroy() {
        destroyed = true
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }
}

/**
 * Native screen with hidden engines behind it. Engines keep full size and stay
 * attached, so provider pages render normally, while the opaque native panel
 * covers them. "Show provider page" brings one engine to the front.
 */
class EngineScreen(private val activity: Activity, title: String) {
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(16))
    }
    private val scroll = ScrollView(activity).apply {
        isFillViewport = true
        setBackgroundColor(themeBackground())
        addView(panel)
    }
    private val stack = FrameLayout(activity)
    private val webTitle = TextView(activity).apply { textSize = 15f; setPadding(dp(4), dp(4), dp(4), dp(4)) }
    private val webBar = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(themeBackground())
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val engines = mutableListOf<FaseyhaEngine>()
    var shownEngine: FaseyhaEngine? = null
        private set
    var onNativeShown: (() -> Unit)? = null

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(webBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init {
        stack.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        heading(title)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun barButton(label: String, work: () -> Unit) = row.addView(Button(activity).apply {
            text = label; minHeight = dp(48); setOnClickListener { work() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barButton("Back to app") { showNative() }
        barButton("Page back") { shownEngine?.web?.let { if (it.canGoBack()) it.goBack() } }
        barButton("Reload") { shownEngine?.reload() }
        webBar.addView(row)
        webBar.addView(webTitle)
    }

    fun attach(engine: FaseyhaEngine) {
        engines += engine
        stack.addView(engine.web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    val isWebShown get() = shownEngine != null

    fun showWeb(engine: FaseyhaEngine, description: String) {
        shownEngine = engine
        engines.forEach { it.setShown(it === engine) }
        engine.web.bringToFront()
        scroll.visibility = View.GONE
        webBar.visibility = View.VISIBLE
        webTitle.text = description
        webBar.announceForAccessibility("Showing the Faseyha page. Use Back to app to return.")
    }

    fun showNative() {
        if (shownEngine == null) return
        shownEngine = null
        engines.forEach { it.setShown(false) }
        webBar.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        scroll.bringToFront()
        onNativeShown?.invoke()
    }

    fun heading(text: String, size: Float = 22f) = TextView(activity).apply {
        this.text = text; textSize = size
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        panel.addView(this)
    }

    fun text(initial: String, size: Float = 16f, live: Boolean = false, parent: LinearLayout = panel) = TextView(activity).apply {
        text = initial; textSize = size; setPadding(0, dp(6), 0, dp(6))
        if (live) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        parent.addView(this)
    }

    fun button(label: String, parent: LinearLayout = panel, work: () -> Unit) = Button(activity).apply {
        text = label; minHeight = dp(56); setOnClickListener { work() }
        parent.addView(this)
    }

    fun section(visible: Boolean = false) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = if (visible) View.VISIBLE else View.GONE
        panel.addView(this)
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun themeBackground(): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) value.data
        else 0xFFFFFFFF.toInt()
    }
}

fun Activity.notice(message: String, title: String? = null) {
    if (isFinishing) return
    AlertDialog.Builder(this).apply { if (title != null) setTitle(title) }
        .setMessage(message).setPositiveButton("OK", null).show()
}
