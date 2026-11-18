package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Assisted Faseyha website form preparation.
 *
 * Deliberately does NOT submit forms or invoke private provider endpoints.
 * Every transaction must be verified and submitted by the user on the actual website.
 * Field inspection does not extract typed values, cookies, balances or screen text.
 */
class FaseyhaAssistActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var number: EditText
    private lateinit var amount: EditText
    private lateinit var service: Spinner
    private lateinit var state: TextView
    private var homeUrl: String = "https://faseyha.net"
    private val allowedHost = "faseyha.net"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getStringExtra("url") ?: homeUrl
        val uri = Uri.parse(selected)
        if (uri.scheme != "https" || !trusted(uri.host)) {
            AlertDialog.Builder(this).setTitle("Unsupported website")
                .setMessage("The Faseyha assistant works only with faseyha.net HTTPS pages.")
                .setPositiveButton("Close") { _, _ -> finish() }.setCancelable(false).show()
            return
        }
        homeUrl = selected

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
        }
        val title = TextView(this).apply { text = "Faseyha assisted recharge"; textSize = 22f; if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        layout.addView(title)
        layout.addView(TextView(this).apply {
            text = "Step 1: Navigate in the website below: Open Dashboard, choose Recharge or Bill Pay, and open its number and amount form. Step 2: Enter details above. Step 3: Inspect and fill. No payment is submitted automatically."
        })
        service = Spinner(this).apply {
            adapter = ArrayAdapter(this@FaseyhaAssistActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Recharge", "Package Recharge", "Bill Pay"))
            contentDescription = "Select Faseyha service to prepare"
        }
        layout.addView(service)
        number = EditText(this).apply {
            hint = "Mobile or account number"; inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        layout.addView(number)
        amount = EditText(this).apply {
            hint = "Amount MVR"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true)
        }
        layout.addView(amount)
        state = TextView(this).apply { text = "Status: Open the Faseyha dashboard, then navigate to the required form."; textSize = 15f }
        layout.addView(state)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun action(label: String, work: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label; minHeight = 48.dp; setOnClickListener { work() }
            })
        }
        action("Inspect current website form") { inspect() }
        action("Fill visible form fields — do not pay") { prepare() }
        action("Open Faseyha homepage") { web.loadUrl(homeUrl) }
        action("Browser back") { if (web.canGoBack()) web.goBack() }
        action("Browser forward") { if (web.canGoForward()) web.goForward() }
        action("Refresh") { web.reload() }
        action("Close assistant") { finish() }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https") return false
                    message("Only HTTPS links can open inside this browser.")
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    if (isTrusted()) state.text = if (Uri.parse(url).path.isNullOrBlank() || Uri.parse(url).path == "/") "Faseyha homepage loaded. Open Dashboard and a payment form in the website below before inspecting." else "Faseyha page loaded. Open the relevant number and amount form, then inspect."
                    else state.text = "This page is outside faseyha.net. The native field assistant is disabled."
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        layout.addView(TextView(this).apply { text = "Website below. First open Dashboard, then choose your service, then open its form. Inspecting the homepage will show zero fields." })
        layout.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460.dp))
        layout.addView(actions)
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(layout) }
        setContentView(scroller)
        web.loadUrl(homeUrl)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun trusted(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == allowedHost || h.endsWith(".$allowedHost")
    }
    private fun isTrusted(): Boolean {
        val uri = Uri.parse(web.url ?: "")
        return uri.scheme == "https" && trusted(uri.host)
    }
    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }
    private fun inspect() {
        if (!isTrusted()) { message("Field inspection is only enabled on the Faseyha website."); return }
        // No field contents or credential values are read; report only visible, non-sensitive control descriptors.
        val script = """(function(){
          const fields=Array.from(document.querySelectorAll('input,select,textarea'))
            .filter(x=>x.getClientRects().length && !['hidden','password','email'].includes((x.type||'').toLowerCase()))
            .slice(0,40).map(x=>{
              const label=x.labels&&x.labels.length?x.labels[0].innerText:'';
              const desc=[label,x.getAttribute('aria-label'),x.getAttribute('placeholder'),x.name,x.id]
                .filter(Boolean).join(' ').slice(0,90);
              return {type:(x.type||x.tagName).slice(0,24),label:desc};
            });
          return JSON.stringify({path:location.pathname.slice(0,120),fields:fields});
        })();"""
        web.evaluateJavascript(script) { raw ->
            try {
                val parsed = org.json.JSONArray("[$raw]").getString(0)
                val obj = JSONObject(parsed)
                val fields = obj.getJSONArray("fields")
                val report = StringBuilder("Visible form controls: ${fields.length()}\nCurrent page: ${obj.optString("path")}\n")
                for (i in 0 until fields.length()) {
                    val f = fields.getJSONObject(i)
                    report.append("\n${i + 1}. ${f.optString("type")}: ${f.optString("label")}")
                }
                if (fields.length() == 0) report.append("\nNo visible form inputs on this page. This usually means you are on the Faseyha homepage or dashboard, not the payment form. Open Dashboard in the website, choose Bill Pay or Recharge, and open its number and amount fields before trying again.")
                report.append("\n\nNo field values, login tokens, or passwords were collected.")
                message(report.toString())
            } catch (e: Exception) { message("Could not inspect the current form. Try again after it loads.") }
        }
    }
    private fun prepare() {
        if (!isTrusted()) { message("You can only fill fields on the Faseyha website."); return }
        val target = number.text.toString().trim()
        val money = amount.text.toString().trim()
        if (!target.matches(Regex("[0-9]{5,15}"))) {
            number.error = "Enter a valid numeric phone or account number"; number.requestFocus(); return
        }
        val parsed = money.toBigDecimalOrNull()
        if (parsed == null || parsed <= BigDecimal.ZERO || parsed > BigDecimal("100000") || parsed.scale() > 2) {
            amount.error = "Enter a positive amount with at most two decimal places"; amount.requestFocus(); return
        }
        AlertDialog.Builder(this).setTitle("Prepare website form")
            .setMessage("Service: ${service.selectedItem}\nNumber: $target\nAmount: MVR $money\n\nOnly visible matching fields will be filled. No Pay button will be pressed. Check the actual website carefully.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Fill fields") { _, _ -> fill(target, parsed.toPlainString()) }.show()
    }
    private fun fill(target: String, money: String) {
        if (!isTrusted()) { message("The website changed. Reopen the Faseyha page."); return }
        // Serialize arguments through JSONObject.quote, never interpolate raw user input into JavaScript.
        val js = """(function(number,amount){
           const visible=x=>x.getClientRects().length>0 && !x.disabled && !x.readOnly;
           const fields=Array.from(document.querySelectorAll('input')).filter(visible)
             .filter(x=>!['hidden','password','email','submit','button','checkbox','radio'].includes((x.type||'').toLowerCase()));
           const label=x=>[(x.labels&&x.labels.length?x.labels[0].innerText:''),x.getAttribute('aria-label'),
             x.getAttribute('placeholder'),x.name,x.id].filter(Boolean).join(' ').toLowerCase();
           const num=fields.filter(x=>/(mobile|phone|number|account|subscriber|service)/.test(label(x)));
           const amt=fields.filter(x=>/(amount|price|pay)/.test(label(x)));
           if(num.length!==1 || amt.length!==1 || num[0]===amt[0])
             return JSON.stringify({ok:false,n:num.length,a:amt.length,reason:'Fields not uniquely identifiable'});
           function update(x,value){
              const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              setter.call(x,value);x.dispatchEvent(new Event('input',{bubbles:true}));
              x.dispatchEvent(new Event('change',{bubbles:true}));
           }
           update(num[0],number);update(amt[0],amount);
           return JSON.stringify({ok:true,reason:'Filled two fields. Review the website. No payment submitted.'});
         })(${JSONObject.quote(target)},${JSONObject.quote(money)});"""
        web.evaluateJavascript(js) { raw ->
            try {
                val data = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val ok = data.optBoolean("ok")
                state.text = if (ok) "Fields prepared. Review the website and submit manually if correct." else "Form not filled: ${data.optString("reason")}. First open the correct Faseyha payment form in the website below, then inspect its fields."
                message(state.text.toString())
            } catch (e: Exception) { message("Unable to verify form filling. Do not assume the fields were completed.") }
        }
    }
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
