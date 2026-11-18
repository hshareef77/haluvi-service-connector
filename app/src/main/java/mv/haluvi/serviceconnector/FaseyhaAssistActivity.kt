package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
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
    private lateinit var operator: Spinner
    private lateinit var state: TextView
    private var homeUrl: String = "https://faseyha.net"
    private var prepared = false
    private var lastPreparedSignature: String? = null
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
            text = "Choose service and operator, enter recipient and (for Recharge/Bill Pay) amount. Prepare the form and review Faseyha order summary. Package and payment controls remain with Faseyha."
        })
        service = Spinner(this).apply {
            adapter = ArrayAdapter(this@FaseyhaAssistActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Recharge", "Package Recharge", "Bill Pay"))
            contentDescription = "Select Faseyha service to prepare"
        }
        layout.addView(service)
        operator = Spinner(this).apply {
            adapter = ArrayAdapter(this@FaseyhaAssistActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Ooredoo", "Dhiraagu"))
            contentDescription = "Choose preferred operator for Recharge or Packages"
        }
        layout.addView(operator)
        number = EditText(this).apply {
            hint = "Mobile number or bill account ID"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine(true)
        }
        layout.addView(number)
        amount = EditText(this).apply {
            hint = "Amount MVR"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true)
        }
        layout.addView(amount)
        service.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                operator.visibility = if (position == 2) View.GONE else View.VISIBLE
                val packageMode = position == 1
                amount.isEnabled = !packageMode
                amount.visibility = if (packageMode) View.GONE else View.VISIBLE
                if (packageMode) amount.setText("")
                number.hint = if (position == 2) "Bill account or service ID" else "Seven-digit mobile number"
            }
        }
        state = TextView(this).apply { text = "Status: Open the Faseyha dashboard, then navigate to the required form."; textSize = 15f }
        layout.addView(state)
        val primary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun action(label: String, work: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label; minHeight = 48.dp; setOnClickListener { work() }
            })
        }
        primary.addView(Button(this).apply { text = "Prepare selected service form — do not pay"; minHeight = 56.dp; setOnClickListener { prepareSelectedService() } })
        primary.addView(Button(this).apply { text = "Apply operator on website — no payment"; minHeight = 56.dp; setOnClickListener { applyOperator() } })
        primary.addView(Button(this).apply { text = "Read provider order summary"; minHeight = 56.dp; setOnClickListener { readOrderSummary() } })
        primary.addView(Button(this).apply { text = "Browse packages and select product card"; minHeight = 56.dp; setOnClickListener { inspectPackageSummary() } })
        primary.addView(Button(this).apply { text = "Transaction records and review"; minHeight = 56.dp; setOnClickListener { showRecords() } })
        primary.addView(Button(this).apply { text = "Payment test and confirmation guidance"; minHeight = 56.dp; setOnClickListener { showTestGuide() } })
        primary.addView(Button(this).apply { text = "More browser and inspection controls"; minHeight = 56.dp; setOnClickListener {
            actions.visibility = if (actions.visibility == View.GONE) View.VISIBLE else View.GONE
            text = if (actions.visibility == View.VISIBLE) "Hide extra browser controls" else "More browser and inspection controls"
        } })
        action("Open selected service") {
            val path = when (service.selectedItemPosition) {
                0 -> "/recharge"
                1 -> "/packages"
                else -> "/bills"
            }
            openFaseyhaPath(path)
        }
        action("Open dashboard") { openFaseyhaPath("/dashboard") }
        action("Inspect current website form") { inspect() }
        action("Inspect service choices and controls") { inspectChoices() }
                action("Fill current page form — do not pay") { prepare() }
        action("Open Faseyha homepage") { openFaseyhaPath("/") }
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
                    prepared = false
                    lastPreparedSignature = null
                    if (isTrusted()) state.text = if (Uri.parse(url).path.isNullOrBlank() || Uri.parse(url).path == "/") "Faseyha homepage loaded. Open Dashboard and a payment form in the website below before inspecting." else "Faseyha page loaded. Open the relevant number and amount form, then inspect."
                    else state.text = "This page is outside faseyha.net. The native field assistant is disabled."
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        layout.addView(primary)
        layout.addView(TextView(this).apply { text = "Provider website. If preparing opens a different service page, allow it to load and activate Prepare form again. The website may require further service selections. Additional browser and inspection controls are below." })
        layout.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460.dp))
        layout.addView(actions)
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(layout) }
        setContentView(scroller)
        web.loadUrl(homeUrl)
    }

    private fun selectedPath(): String = when (service.selectedItemPosition) {
        0 -> "/recharge"
        1 -> "/packages"
        else -> "/bills"
    }

    private fun prepareSelectedService() {
        val expected = selectedPath()
        if (!isTrusted()) {
            message("This page is outside Faseyha. Open your selected service again.")
            return
        }
        if (Uri.parse(web.url ?: "").path != expected) {
            openFaseyhaPath(expected)
            state.text = "Service page opened. After it loads, select Prepare form again. No payment was submitted."
            return
        }
        prepare()
    }

    private fun openFaseyhaPath(path: String) {
        if (path !in setOf("/", "/dashboard", "/recharge", "/packages", "/bills")) {
            message("Unsupported service path.")
            return
        }
        prepared = false
        lastPreparedSignature = null
        val destination = "https://faseyha.net" + path
        state.text = "Opening Faseyha " + (if (path == "/") "homepage" else path.removePrefix("/")) + "."
        web.loadUrl(destination)
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
        if (Uri.parse(web.url ?: "").path != selectedPath()) {
            message("The website is not on the selected service page. Use Prepare selected service form to open it first.")
            return
        }
        val target = number.text.toString().trim()
        val money = amount.text.toString().trim()
        val isBill = service.selectedItemPosition == 2
        val validTarget = if (isBill) target.matches(Regex("[A-Za-z0-9-]{5,30}"))
            else target.matches(Regex("[0-9]{7}"))
        if (!validTarget) {
            number.error = if (isBill) "Enter a valid account or service number, including any letters"
                           else "Enter a seven-digit Maldives mobile number"
            number.requestFocus()
            return
        }
        if (service.selectedItemPosition == 1) {
            AlertDialog.Builder(this).setTitle("Prepare package recipient")
                .setMessage("Recipient: $target\n\nIn the Faseyha website, choose Ooredoo or Dhiraagu and explicitly select a package. The provider controls the package price, GST and total. This assistant fills only the recipient number, not the amount, and never activates a package.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Fill recipient only") { _, _ -> fillPackageRecipient(target) }.show()
            return
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
    private fun fillPackageRecipient(target: String) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            message("Open the Faseyha Packages page before filling.")
            return
        }
        val js = """(function(value){
            const fields=Array.from(document.querySelectorAll('input')).filter(x=>x.getClientRects().length>0 && !x.disabled && !x.readOnly && !['hidden','password','submit','button'].includes((x.type||'').toLowerCase()));
            const label=x=>[(x.labels&&x.labels.length?x.labels[0].innerText:''),x.getAttribute('aria-label'),x.getAttribute('placeholder'),x.name,x.id].filter(Boolean).join(' ').toLowerCase();
            const choices=fields.filter(x=>/(mobile|phone|number|7xxxxxxx|7x{4,})/.test(label(x)));
            if(choices.length!==1) return JSON.stringify({ok:false,reason:'Found '+choices.length+' possible recipient fields. Need exactly one.'});
            const input=choices[0];
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,value);
            input.dispatchEvent(new Event('input',{bubbles:true}));
            input.dispatchEvent(new Event('change',{bubbles:true}));
            return JSON.stringify({ok:input.value===value,reason:input.value===value?'Recipient field matches. Package selection and price are NOT verified.':'The website did not retain the intended recipient.'});
        })(${JSONObject.quote(target)});"""
        web.evaluateJavascript(js) { raw ->
            try {
                val result = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                message(if (result.optBoolean("ok")) "Recipient matches the native number. Select operator and package on the Faseyha website and review the final total, including GST, before activating manually. No package purchase was submitted or verified."
                else "Package recipient not filled: " + result.optString("reason"))
            } catch (_: Exception) { message("Could not verify the package recipient. No payment was submitted.") }
        }
    }

    private fun fill(target: String, money: String) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) { message("The website changed. Reopen the selected Faseyha page."); return }
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
             return JSON.stringify({ok:false,n:num.length,a:amt.length,reason:'Found '+num.length+' number fields and '+amt.length+' amount fields; need exactly one of each'});
           function update(x,value){
              const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              setter.call(x,value);x.dispatchEvent(new Event('input',{bubbles:true}));
              x.dispatchEvent(new Event('change',{bubbles:true}));
           }
           update(num[0],number);update(amt[0],amount);
           const verified = num[0].value === number && amt[0].value === amount;
           return JSON.stringify({ok:verified,reason:verified?'Both website fields match the requested values. No payment submitted.':'Website altered a value; inspect and correct manually.'});
         })(${JSONObject.quote(target)},${JSONObject.quote(money)});"""
        web.evaluateJavascript(js) { raw ->
            try {
                val data = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val ok = data.optBoolean("ok")
                prepared = ok
                if (ok) {
                    val signature = listOf(service.selectedItem.toString(), target, money, web.url ?: "").joinToString("|")
                    if (signature != lastPreparedSignature) {
                        FaseyhaLedger.create(this, service.selectedItem.toString(), target, money)
                        lastPreparedSignature = signature
                    }
                }
                state.text = if (ok) "Fields match the requested details in the current website form. Review the site and submit manually if correct. Transaction not completed or verified." else "Form not prepared: ${data.optString("reason")}. Check the current service form and inspect its fields."
                message(state.text.toString())
            } catch (e: Exception) { message("Unable to verify form filling. Do not assume the fields were completed.") }
        }
    }

    private fun applyOperator() {
        if (service.selectedItemPosition == 2) { message("Bill Pay operator selection must be made on the website."); return }
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) {
            message("Open the selected Faseyha service page before choosing an operator.")
            return
        }
        val name = operator.selectedItem.toString()
        AlertDialog.Builder(this).setTitle("Apply operator $name?")
            .setMessage("Only a uniquely identified Ooredoo or Dhiraagu operator control may be activated. This cannot click Pay, Recharge, or Activate Package. Review the website operator and total afterward.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val js = """(function(name){
                   const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
                   const normalized=t=>(t||'').replace(/\s+/g,' ').trim().toLowerCase();
                   const expected=normalized(name);
                   const nodes=Array.from(document.querySelectorAll('button,[role="tab"],[role="radio"],[role="button"],label,input[type="radio"]'))
                       .filter(visible).filter(x=>!x.disabled);
                   const label=x=>normalized([x.getAttribute('aria-label'),x.getAttribute('title'),
                      x.innerText,x.getAttribute('value')].filter(Boolean).join(' '));
                   const safe=x=>!/(pay|recharge now|activate|submit|purchase|checkout|confirm)/.test(label(x));
                   const matches=nodes.filter(x=>safe(x) && (label(x)===expected ||
                        label(x)===expected+' raastas' || label(x)===expected+' reload'));
                   if(matches.length!==1) return JSON.stringify({ok:false,reason:'Found '+matches.length+' exact matching operator controls. Select operator manually on website if none or ambiguous.'});
                   matches[0].click();
                   return JSON.stringify({ok:true,reason:'Operator control activated. Verify operator selection in the actual provider order summary.'});
                 })(${JSONObject.quote(name)});"""
                web.evaluateJavascript(js) { raw ->
                    try {
                        val result = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                        state.text = if (result.optBoolean("ok")) "Operator selection requested. Verify the operator in the Faseyha order summary." else result.optString("reason")
                        message(state.text.toString())
                    } catch (_: Exception) { message("Operator selection could not be confirmed. Inspect the provider page manually.") }
                }
            }.show()
    }

    private fun inspectPackageSummary() {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            message("Open Package Recharge to browse packages.")
            return
        }
        val js = """(function(){
          const shown=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
          const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
          const nodes=Array.from(document.querySelectorAll('button,[role="option"],[role="radio"],[role="button"],label,article,[class*="package"],[class*="Package"]'));
          const found=nodes.filter(shown).map(x=>tidy(x.innerText||x.getAttribute('aria-label')||''))
            .filter(s=>s.length>=8 && s.length<=200 && /\b(gb|mb|data|minutes|days|package)\b/i.test(s))
            .filter(s=>!/(activate package|purchase now|confirm payment|pay now|checkout)/i.test(s))
            .filter((v,i,a)=>a.indexOf(v)===i).slice(0,30);
          return JSON.stringify({items:found});
        })();"""
        web.evaluateJavascript(js) { raw ->
            try {
                val obj = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val arr = obj.getJSONArray("items")
                if (arr.length() == 0) {
                    message("No unambiguous readable package cards found. Choose a package on Faseyha's website and review its summary.")
                    return@evaluateJavascript
                }
                val choices = Array(arr.length()) { i -> arr.getString(i) }
                AlertDialog.Builder(this).setTitle("Packages visible on Faseyha")
                    .setItems(choices) { _, i -> selectPackageCard(choices[i]) }
                    .setNegativeButton("Cancel", null).show()
            } catch (_: Exception) {
                message("Could not read package choices. Use the Faseyha website selector.")
            }
        }
    }

    private fun selectPackageCard(label: String) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            message("Page changed. Reopen Packages.")
            return
        }
        AlertDialog.Builder(this).setTitle("Select package?")
            .setMessage(label + "\n\nThis only attempts to select a product card, never Activate Package or Pay. Review final operator, recipient, package and price on Faseyha afterward.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select card") { _, _ ->
                val js = """(function(wanted){
                  const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
                  const shown=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
                  const nodes=Array.from(document.querySelectorAll('button,[role="option"],[role="radio"],[role="button"],label,article,[class*="package"],[class*="Package"]'));
                  const matches=nodes.filter(shown).filter(x=>tidy(x.innerText||x.getAttribute('aria-label')||'')===wanted)
                    .filter(x=>!/(activate package|purchase now|confirm payment|pay now|checkout)/i.test(tidy(x.innerText)));
                  if(matches.length!==1) return JSON.stringify({reason:'Found '+matches.length+' exact matching cards. Select directly on website if ambiguous.'});
                  const node=matches[0];
                  if(node.matches('form,[type="submit"],a[href]') || node.querySelector('button[type="submit"],a[href*="checkout"]'))
                    return JSON.stringify({reason:'Potential payment control inside card. Selection blocked.'});
                  node.click();
                  return JSON.stringify({reason:'Package card selection requested. Check provider summary and price.'});
                })(${JSONObject.quote(label)});"""
                web.evaluateJavascript(js) { raw ->
                    try {
                        val obj=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                        message(obj.optString("reason") + "\n\nThis is not a purchase or provider verification.")
                    } catch (_: Exception) {
                        message("Could not verify package selection. Check the Faseyha website.")
                    }
                }
            }.show()
    }

    private fun readOrderSummary() {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) {
            message("Open the selected Faseyha service page and its order summary first.")
            return
        }
        val js = """(function(){
          const txt=(document.body.innerText||'');
          const index=txt.toLowerCase().lastIndexOf('order summary');
          if(index<0) return JSON.stringify({found:false});
          const part=txt.slice(index,index+1500).replace(/\s+/g,' ').trim();
          return JSON.stringify({found:true,summary:part.slice(0,900)});
        })();"""
        web.evaluateJavascript(js) { raw ->
            try {
                val obj = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if (!obj.optBoolean("found")) message("No readable order summary is visible on this provider page.")
                else message("Faseyha website order summary (not a transaction result):\n" + obj.optString("summary") + "\n\nVerify operator, recipient, selected product, tax and total on the provider page. Do not rely on this text alone to authorize payment.")
            } catch (_: Exception) { message("Unable to read the provider order summary.") }
        }
    }

    private fun showTestGuide() {
        val guide = "For a first real transaction, use only a number or account you control and an amount you choose within Faseyha's minimum and available wallet balance.\n\n" +
            "1. Select Bill Pay, Recharge, or Package Recharge and open the correct provider page.\n" +
            "2. For Recharge choose the correct operator; for Packages choose the exact package on the provider site.\n" +
            "3. Enter the target and amount for Recharge/Bill Pay. For packages enter only the recipient; choose the actual package and price in the Faseyha website. Prepare only when the website exposes matching fields.\n" +
            "4. Confirm target, operator, product, actual provider price and possible fees on the Faseyha website.\n" +
            "5. Press the WEBSITE Pay button yourself only if you approve the real transaction.\n" +
            "6. If there is a confirmation or verification step, complete it personally. Check the provider receipt and final status.\n" +
            "7. Return to Transaction records and review and save a manual observation or receipt reference.\n\n" +
            "Never retry an uncertain payment without checking Faseyha's history and actual recipient delivery. Local notes are not independently verified success."
        message(guide)
    }

    private fun inspectChoices() {
        if (!isTrusted()) { message("Open a Faseyha page to inspect its service choices."); return }
        val script = """(function(){
          const visible=x=>x.getClientRects().length>0;
          const controls=Array.from(document.querySelectorAll('select,option,button,[role="button"],[role="option"],[role="combobox"],input[type=radio]'))
            .filter(visible).filter(x=>!x.closest('input[type=password]')).slice(0,65)
            .map(x=>({kind:x.tagName.toLowerCase(),label:(x.getAttribute('aria-label')||x.innerText||x.getAttribute('title')||'').trim().slice(0,90)}))
            .filter(x=>x.label.length>0);
          return JSON.stringify({path:location.pathname.slice(0,90),controls:controls});
        })();"""
        web.evaluateJavascript(script) { raw ->
            try {
                val result = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val controls = result.getJSONArray("controls")
                val lines = StringBuilder("Service page: " + result.optString("path") + "\nVisible selection controls: " + controls.length())
                for (i in 0 until controls.length()) {
                    val o = controls.getJSONObject(i)
                    lines.append("\n" + (i + 1) + ". " + o.optString("kind") + ": " + o.optString("label"))
                }
                lines.append("\n\nThis only inspects labels. It does not activate controls, submit transactions or inspect private payment data. Choose the operator or package on the website itself.")
                message(lines.toString())
            } catch (_: Exception) { message("Could not inspect service choices. Open the actual provider form and try again.") }
        }
    }

    private fun showRecords() {
        val rows = FaseyhaLedger.list(this)
        if (rows.length() == 0) {
            message("No transaction preparations recorded yet. These records do not prove a payment.")
            return
        }
        val labels = Array(rows.length()) { index ->
            val r = rows.getJSONObject(index)
            r.optString("service") + " — " + r.optString("target") +
                " — MVR " + r.optString("amount") + " — " + r.optString("status")
        }
        AlertDialog.Builder(this).setTitle("Local preparation and review history")
            .setItems(labels) { _, selected -> showRecord(rows.getJSONObject(selected).optString("id")) }
            .setNeutralButton("Clear records") { _, _ ->
                AlertDialog.Builder(this).setTitle("Clear all local records?")
                    .setMessage("This only deletes local notes, not provider transaction history.")
                    .setPositiveButton("Delete") { _, _ -> FaseyhaLedger.clear(this) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun showRecord(id: String) {
        val rows = FaseyhaLedger.list(this)
        var record: JSONObject? = null
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            if (item.optString("id") == id) { record = item; break }
        }
        if (record == null) { message("Record was not found."); return }
        val details = record.optString("service") + "\nTarget: " + record.optString("target") +
            "\nMVR " + record.optString("amount") + "\nStatus: " + record.optString("status") +
            "\nNote: " + record.optString("note") +
            "\n\nThis record contains only local preparation and user-reported observations. It is not provider-confirmed payment evidence."
        AlertDialog.Builder(this).setTitle("Faseyha transaction record").setMessage(details)
            .setPositiveButton("Update observation") { _, _ -> updateRecord(id) }
            .setNeutralButton("Delete record") { _, _ ->
                AlertDialog.Builder(this).setTitle("Delete this local record?")
                    .setPositiveButton("Delete") { _, _ -> FaseyhaLedger.delete(this, id) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun updateRecord(id: String) {
        val options = arrayOf("Prepared", "User reports submitted", "Needs review", "User reports completed", "User reports failed")
        AlertDialog.Builder(this).setTitle("Choose observed status")
            .setItems(options) { _, choice ->
                val note = EditText(this).apply {
                    hint = "Optional receipt reference or note. Never include a password."
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(false)
                    maxLines = 3
                }
                AlertDialog.Builder(this).setTitle(options[choice])
                    .setMessage("Manual note only. No provider status has been verified; do not use this as proof of successful HaluviPay fulfillment.")
                    .setView(note)
                    .setPositiveButton("Save observation") { _, _ ->
                        if (FaseyhaLedger.update(this, id, options[choice], note.text.toString().trim()))
                            message("Saved locally as a user-reported observation, not a verified provider result.")
                        else message("Could not save the observation.")
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
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
