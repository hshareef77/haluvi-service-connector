package mv.haluvi.serviceconnector

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The only visible website screen: sign in to Faseyha (or another saved website)
 * and review pages manually. Payment, package and Wallet screens stay native and
 * reuse the session saved here.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebsiteActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_URL) ?: (FaseyhaSite.BASE + "/dashboard")
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Faseyha website"
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = title; textSize = 20f; setPadding((12 * density).toInt(), (8 * density).toInt(), 0, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun barButton(label: String, work: () -> Unit) = bar.addView(Button(this).apply {
            text = label; minHeight = (48 * density).toInt(); setOnClickListener { work() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barButton("Close") { finish() }
        barButton("Back") { if (web.canGoBack()) web.goBack() }
        barButton("Reload") { web.reload() }
        root.addView(bar)
        address = TextView(this).apply {
            textSize = 14f; setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (4 * density).toInt())
        }
        root.addView(address)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https") return false
                    address.text = "Blocked a non-secure link. Only HTTPS pages open here."
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    val uri = Uri.parse(url)
                    address.text = "Page: " + (uri.host ?: "") + (uri.path ?: "") +
                        if (uri.host != null && !FaseyhaSite.trusted(uri.host) && start.contains(FaseyhaSite.HOST)) ". Outside Faseyha; check the address before signing in." else ""
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (Uri.parse(start).scheme == "https") web.loadUrl(start) else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}

/** Shared helper for screens that need the person to sign in again. */
fun Activity.openFaseyhaWebsite(path: String = "/dashboard") {
    startActivity(android.content.Intent(this, WebsiteActivity::class.java)
        .putExtra(WebsiteActivity.EXTRA_URL, FaseyhaSite.BASE + path)
        .putExtra(WebsiteActivity.EXTRA_TITLE, "Faseyha website"))
}
