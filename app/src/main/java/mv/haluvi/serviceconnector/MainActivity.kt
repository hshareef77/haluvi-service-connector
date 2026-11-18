package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("services", MODE_PRIVATE) }
    private var browser: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home()
    }
    override fun onDestroy() {
        browser?.destroy()
        super.onDestroy()
    }
    private fun ui(title: String) {
        browser?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        browser = null
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        val heading = TextView(this).apply {
            text = title; textSize = 24f
            setAccessibilityHeadingCompat()
        }
        root.addView(heading)
    }
    private fun TextView.setAccessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label; minHeight = 56.dp
            setOnClickListener { action() }
        })
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun info(message: String) {
        root.addView(TextView(this).apply { text = message; textSize = 16f; setPadding(0, 10, 0, 10) })
    }
    private fun prompt(title: String, initial: String = "", callback: (String) -> Unit) {
        val field = EditText(this).apply {
            setSingleLine(true); setText(initial); hint = title
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("Save") { _, _ -> callback(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun entries(): JSONArray = try { JSONArray(prefs.getString("entries", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun saveEntries(data: JSONArray) { prefs.edit().putString("entries", data.toString()).apply() }
    private fun addEntry(kind: String, name: String, value: String) {
        val data = entries()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            if (obj.optString("kind") == kind && obj.optString("value") == value) {
                AlertDialog.Builder(this).setMessage("This service is already saved.").setPositiveButton("OK", null).show(); return
            }
        }
        data.put(JSONObject().put("kind", kind).put("name", name).put("value", value))
        saveEntries(data); services()
    }
    private fun home() {
        ui("Haluvi Service Connector")
        info("Connect supported installed apps and websites. Existing app sessions remain inside their original apps.")
        button("My services") { services() }
        button("Add installed application") { appPicker() }
        button("Add website service") { addWebsite() }
        button("Accessibility assistant") { accessibilityScreen() }
        button("Settings and privacy") { settingsScreen() }
        button("About and limitations") { about() }
    }
    private fun services() {
        ui("My services")
        button("Back to home") { home() }
        button("Add installed application") { appPicker() }
        button("Add website service") { addWebsite() }
        val data = entries()
        if (data.length() == 0) info("No services have been added.")
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            val name = o.optString("name")
            val value = o.optString("value")
            button("$name — ${if (kind == "app") "Installed app" else "Website"}") { serviceActions(name, kind, value) }
        }
    }
    private fun appPicker() {
        ui("Select installed application")
        button("Back") { services() }
        info("Only launchable applications appear here. Selecting an app does not unlock its private data or wallet.")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) info("No launchable applications were found.")
        apps.forEach { app ->
            val name = app.loadLabel(packageManager).toString()
            val pkg = app.activityInfo.packageName
            button("$name — $pkg") {
                AlertDialog.Builder(this).setTitle(name).setMessage("Save this app as a service?\n$pkg")
                    .setPositiveButton("Add") { _, _ -> addEntry("app", name, pkg) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }
    private fun addWebsite() {
        prompt("Service name") { name ->
            if (name.isBlank()) return@prompt
            prompt("Secure HTTPS website URL", "https://") { url ->
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                    AlertDialog.Builder(this).setMessage("Enter a valid HTTPS address.").setPositiveButton("OK", null).show()
                } else addEntry("web", name, uri.toString())
            }
        }
    }
    private fun serviceActions(name: String, kind: String, value: String) {
        ui(name)
        info("Type: ${if (kind == "app") "Installed application" else "Website"}\n$value")
        button("Back") { services() }
        if (kind == "app") {
            button("Open installed application") {
                val launch = packageManager.getLaunchIntentForPackage(value)
                if (launch == null) showMessage("This application cannot currently be opened.")
                else try { startActivity(launch) } catch (e: Exception) { showMessage("Unable to open: ${e.message}") }
            }
            button("Inspect accessibility controls") {
                prefs.edit().putString("inspect_package", value).apply()
                if (!InspectorService.isEnabled(this)) {
                    showMessage("Enable Haluvi Service Connector under Android Accessibility settings, then return and select Inspect again.")
                    return@button
                }
                InspectorService.requestedPackage = value
                InspectorService.lastSummary = "Open the target application to inspect its accessible controls."
                val launch = packageManager.getLaunchIntentForPackage(value)
                if (launch != null) startActivity(launch)
                else showMessage("Unable to launch this app.")
            }
            button("Read last inspection report") {
                showMessage(InspectorService.lastSummary)
            }
            info("Android does not provide access to this app's private login tokens. Payment or recharge actions require support from the provider and your confirmation.")
        } else {
            button("Open website in saved-session browser") { openBrowser(value, name) }
            button("Delete browser cookies and site storage") {
                AlertDialog.Builder(this).setTitle("Clear browser data?")
                    .setMessage("This signs you out of websites in this application's browser. Cookies are shared between this app's browser profiles.")
                    .setPositiveButton("Clear") { _, _ ->
                        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                        WebView(this).apply { clearCache(true); clearHistory(); destroy() }
                        showMessage("Browser cookies and cache cleared.")
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        button("Remove saved service") {
            AlertDialog.Builder(this).setTitle("Remove $name?")
                .setPositiveButton("Remove") { _, _ ->
                    val original = entries(); val output = JSONArray()
                    for (i in 0 until original.length()) {
                        val o = original.optJSONObject(i) ?: continue
                        if (!(o.optString("kind") == kind && o.optString("value") == value)) output.put(o)
                    }
                    saveEntries(output); services()
                }.setNegativeButton("Cancel", null).show()
        }
    }
    private fun openBrowser(url: String, name: String) {
        ui(name)
        button("Close browser") { browser?.loadUrl("about:blank"); services() }
        info("Only HTTPS navigation is permitted. Verify the address before signing in. This browser has a separate session from other installed apps.")
        val web = WebView(this)
        browser = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https") return false
                showMessage("Navigation blocked: only HTTPS pages are permitted.")
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { CookieManager.getInstance().flush() }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 480.dp))
        web.loadUrl(url)
        button("Browser back") { if (web.canGoBack()) web.goBack() }
        button("Browser forward") { if (web.canGoForward()) web.goForward() }
        button("Refresh page") { web.reload() }
    }
    private fun accessibilityScreen() {
        ui("Accessibility assistant")
        button("Back") { home() }
        info("Optional, explicitly enabled Android accessibility service. It can inspect visible accessibility controls when you open a selected app. It does not capture passwords, initiate payments, or bypass authentication.")
        info("Status: ${if (InspectorService.isEnabled(this)) "Enabled" else "Not enabled"}")
        button("Open Android Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Last inspection result") { showMessage(InspectorService.lastSummary) }
        button("My services") { services() }
    }
    private fun settingsScreen() {
        ui("Settings and privacy")
        button("Back") { home() }
        info("Service profiles are stored locally on this device. Website cookies are kept in this app's private WebView storage. The app does not collect passwords or transfer transaction data to a server.")
        button("Clear all saved service profiles") {
            AlertDialog.Builder(this).setTitle("Remove all saved services?")
                .setPositiveButton("Remove") { _, _ -> prefs.edit().remove("entries").apply(); home() }
                .setNegativeButton("Cancel", null).show()
        }
        button("Clear website cookies") {
            AlertDialog.Builder(this).setTitle("Sign out of websites?")
                .setMessage("Clears cookies across all websites in this application's WebView.")
                .setPositiveButton("Clear") { _, _ ->
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                    showMessage("Cookies cleared.")
                }.setNegativeButton("Cancel", null).show()
        }
    }
    private fun about() {
        ui("About Haluvi Service Connector")
        button("Back") { home() }
        info("Version 1.0. Native Kotlin Android application. Offline service profile manager, launcher and HTTPS WebView with persistent permitted cookies, plus opt-in accessibility inspection.")
        info("Generic third-party wallet payments cannot be made invisibly simply by installing an app. Provider-approved API, SDK, or deep-link integrations are required for reliable in-app transactions. No payment API is included in this release.")
    }
    private fun showMessage(s: String) { AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK", null).show() }
    override fun onBackPressed() {
        val web = browser
        if (web != null && web.canGoBack()) web.goBack()
        else home()
    }
}
