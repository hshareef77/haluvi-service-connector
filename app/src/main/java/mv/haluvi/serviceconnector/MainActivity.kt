package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("services", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home()
    }

    private fun ui(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        root.addView(TextView(this).apply {
            text = title; textSize = 24f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; minHeight = dp(56); setOnClickListener { action() } })
    }

    private fun info(message: String) {
        root.addView(TextView(this).apply { text = message; textSize = 16f; setPadding(0, dp(8), 0, dp(8)) })
    }

    private fun home() {
        ui("Haluvi Service Connector")
        button("Faseyha: recharge, packages and bills") { faseyha() }
        button("Thaana AI: text to speech") { thaana() }
        button("Other saved websites") { services() }
        button("Settings and privacy") { settingsScreen() }
        button("About") { about() }
    }

    private fun faseyha() {
        ui("Faseyha")
        button("Back to home") { home() }
        button("Payments") { startActivity(Intent(this, FaseyhaAssistActivity::class.java)) }
        button("Wallet") { startActivity(Intent(this, FaseyhaWalletActivity::class.java)) }
        button("Website and sign in") { openFaseyhaWebsite() }
    }

    private fun thaana() {
        ui("Thaana AI")
        button("Back to home") { home() }
        button("Text to speech") { startActivity(Intent(this, ThaanaSpeechActivity::class.java)) }
        button("Website and sign in") { openThaanaWebsite() }
    }

    // ---------- other saved services ----------

    private fun entries(): JSONArray = try { JSONArray(prefs.getString("entries", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun saveEntries(data: JSONArray) { prefs.edit().putString("entries", data.toString()).apply() }

    private fun addEntry(kind: String, name: String, value: String) {
        val data = entries()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optString("kind") == kind && o.optString("value") == value) { showMessage("This service is already saved."); return }
        }
        data.put(JSONObject().put("kind", kind).put("name", name).put("value", value))
        saveEntries(data)
        services()
    }

    private fun services() {
        ui("Other saved websites")
        button("Back to home") { home() }
        button("Add website") { addWebsite() }
        val data = entries()
        var shown = 0
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optString("kind") != "web") continue
            shown++
            button(o.optString("name")) { serviceActions(o.optString("name"), o.optString("value")) }
        }
        if (shown == 0) info("No other websites have been added.")
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

    private fun addWebsite() {
        prompt("Service name") { name ->
            if (name.isBlank()) return@prompt
            prompt("Secure HTTPS website address", "https://") { url ->
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) showMessage("Enter a valid HTTPS address.")
                else addEntry("web", name, uri.toString())
            }
        }
    }

    private fun serviceActions(name: String, value: String) {
        ui(name)
        button("Back") { services() }
        button("Open website") {
            startActivity(Intent(this, WebsiteActivity::class.java)
                .putExtra(WebsiteActivity.EXTRA_URL, value).putExtra(WebsiteActivity.EXTRA_TITLE, name))
        }
        button("Remove saved website") {
            AlertDialog.Builder(this).setTitle("Remove $name?")
                .setPositiveButton("Remove") { _, _ ->
                    val original = entries()
                    val output = JSONArray()
                    for (i in 0 until original.length()) {
                        val o = original.optJSONObject(i) ?: continue
                        if (!(o.optString("kind") == "web" && o.optString("value") == value)) output.put(o)
                    }
                    saveEntries(output)
                    services()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    // ---------- settings ----------

    private fun settingsScreen() {
        ui("Settings and privacy")
        button("Back") { home() }
        info("Everything is stored only on this phone. The Faseyha sign-in stays in this app's private browser storage. The app keeps a log of payments it sent and receipt details it read; it never stores passwords.")
        button("Sign out of Faseyha") { confirmSignOut("Faseyha", FaseyhaSite.BASE) }
        button("Sign out of Thaana AI") { confirmSignOut("Thaana AI", ThaanaSite.BASE) }
        button("Sign out of all websites") {
            AlertDialog.Builder(this).setTitle("Sign out of all websites?")
                .setMessage("This removes cookies, site storage and cache for every website in this app, including Faseyha.")
                .setPositiveButton("Sign out") { _, _ ->
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                    WebStorage.getInstance().deleteAllData()
                    WebView(this).apply { clearCache(true); destroy() }
                    showMessage("Signed out of all websites.")
                }.setNegativeButton("Cancel", null).show()
        }
        button("Clear app payment log and saved receipts") {
            AlertDialog.Builder(this).setTitle("Clear local records?")
                .setMessage("Faseyha's own history is not changed.")
                .setPositiveButton("Clear") { _, _ ->
                    FaseyhaLedger.clear(this)
                    getSharedPreferences("verified_faseyha_receipts", MODE_PRIVATE).edit().clear().apply()
                    showMessage("Local records cleared.")
                }.setNegativeButton("Cancel", null).show()
        }
        button("Remove other saved websites") {
            AlertDialog.Builder(this).setTitle("Remove all other saved websites?")
                .setPositiveButton("Remove") { _, _ -> prefs.edit().remove("entries").apply(); home() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun confirmSignOut(name: String, base: String) {
        AlertDialog.Builder(this).setTitle("Sign out of $name?")
            .setMessage("Other websites stay signed in.")
            .setPositiveButton("Sign out") { _, _ -> signOutOf(base); showMessage("Signed out of $name.") }
            .setNegativeButton("Cancel", null).show()
    }

    /** Expires one site's cookies and deletes its site storage, leaving other sites signed in. */
    private fun signOutOf(base: String) {
        val cookies = CookieManager.getInstance()
        val host = android.net.Uri.parse(base).host ?: return
        for (url in listOf(base, "https://www.$host")) {
            cookies.getCookie(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; Domain=$host")
            }
        }
        cookies.flush()
        WebStorage.getInstance().deleteOrigin(base)
        WebStorage.getInstance().deleteOrigin("https://www.$host")
    }

    private fun about() {
        ui("About")
        button("Back") { home() }
        info("Haluvi Service Connector version ${appVersion()}. Native Android app for Faseyha recharges, packages and bill payments, built for TalkBack and Jieshuo.")
        info("The app works through your own signed-in Faseyha website session. It fills the form, shows a native review, and after your confirmation presses Faseyha's button once. It then checks Faseyha's result, Wallet entry and receipt. It never repeats a payment automatically. Faseyha Wallet is the final record.")
        info("Website changes on Faseyha can stop a step. The app then stops without paying and explains why; More options, Copy page report helps diagnose it.")
    }

    private fun showMessage(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val heading = (root.getChildAt(0) as? TextView)?.text?.toString()
        if (heading == "Haluvi Service Connector") super.onBackPressed() else home()
    }
}
