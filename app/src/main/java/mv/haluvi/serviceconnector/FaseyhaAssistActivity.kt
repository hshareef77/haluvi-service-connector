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
    private lateinit var service: Button
    private lateinit var operator: Button
    private var serviceIndex = 0
    private var operatorIndex = 0
    private val services = arrayOf("Recharge", "Package Recharge", "Bill Pay")
    private val operators = arrayOf("Ooredoo", "Dhiraagu")
    private val billOperators = arrayOf("Dhiraagu", "Ooredoo", "Medianet")
    private var billOperatorIndex = 0
    private var pendingOperator = false
    private lateinit var packageCategory: Button
    private lateinit var packageChoice: Button
    private var selectedPackageLabel: String? = null
    private var selectedPackagePrice: String? = null
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
            text = "Choose service and operator. For Packages, choose a category and browse provider packages. Enter recipient and amount where required. Confirm details before manually authorizing payment."
        })
        service = Button(this).apply {
            minHeight = 56.dp
            setOnClickListener { chooseService() }
        }
        layout.addView(service)
        operator = Button(this).apply {
            minHeight = 56.dp
            setOnClickListener { chooseOperator() }
        }
        layout.addView(operator)
        packageCategory = Button(this).apply {
            minHeight = 56.dp
            text = "Package category: All. Choose category"
            contentDescription = "Package category, selected All. Choose category."
            setOnClickListener { chooseNativePackageCategory() }
        }
        layout.addView(packageCategory)
        packageChoice = Button(this).apply {
            minHeight = 56.dp
            text = "Package: Not selected. Choose package"
            contentDescription = "Package, not selected. Choose package."
            setOnClickListener { inspectPackageSummary() }
        }
        layout.addView(packageChoice)
        updateSelections()
        number = EditText(this).apply {
            hint = "Mobile number"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine(true)
        }
        layout.addView(number)
        amount = EditText(this).apply {
            hint = "Amount MVR"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true)
        }
        layout.addView(amount)
        state = TextView(this).apply { text = "Status: Open the Faseyha dashboard, then navigate to the required form."; textSize = 15f }
        layout.addView(state)
        val primary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun action(label: String, work: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label; minHeight = 48.dp; setOnClickListener { work() }
            })
        }
        primary.addView(Button(this).apply { text = "Continue — prepare and review, do not pay"; minHeight = 56.dp; setOnClickListener { prepareSelectedService() } })


        primary.addView(Button(this).apply { text = "Faseyha Wallet and Transactions"; minHeight = 56.dp; setOnClickListener {
            startActivity(android.content.Intent(this@FaseyhaAssistActivity, FaseyhaWalletActivity::class.java))
        } })




        primary.addView(Button(this).apply { text = "More browser and inspection controls"; minHeight = 56.dp; setOnClickListener {
            actions.visibility = if (actions.visibility == View.GONE) View.VISIBLE else View.GONE
            text = if (actions.visibility == View.VISIBLE) "Hide extra browser controls" else "More browser and inspection controls"
        } })
        action("Apply operator again") { applyOperator() }
        action("Read provider order summary") { readOrderSummary() }
        action("Inspect current transaction result") { inspectResult() }
        action("Transaction records and review") { showRecords() }
        action("Open selected service") {
            val path = when (serviceIndex) {
                0 -> "/recharge"
                1 -> "/packages"
                else -> "/bills"
            }
            openFaseyhaPath(path)
        }
        action("Open dashboard") { openFaseyhaPath("/dashboard") }
        action("Payment test instructions") { showTestGuide() }
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
                    if (pendingOperator && isTrusted() && Uri.parse(url).path == selectedPath()) {
                        pendingOperator = false
                        web.postDelayed({ if (isTrusted() && Uri.parse(web.url ?: "").path == selectedPath()) applyOperator(false) }, 450)
                    }
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

    private fun updateSelections() {
        service.text = "Service: " + services[serviceIndex] + ". Select to change"
        service.contentDescription = "Service, selected " + services[serviceIndex] + ". Double tap to change."
        val currentOperator = if (serviceIndex == 2) billOperators[billOperatorIndex] else operators[operatorIndex]
        operator.text = "Operator: " + currentOperator + ". Select to change"
        operator.contentDescription = "Operator, selected " + currentOperator + ". Double tap to change."
        operator.visibility = View.VISIBLE
        if (::packageCategory.isInitialized) packageCategory.visibility = if (serviceIndex == 1 && operatorIndex == 1) View.VISIBLE else View.GONE
        if (::packageChoice.isInitialized) packageChoice.visibility = if (serviceIndex == 1) View.VISIBLE else View.GONE
        if (::amount.isInitialized) {
            amount.visibility = if (serviceIndex == 1) View.GONE else View.VISIBLE
            amount.isEnabled = serviceIndex != 1
        }
        if (::number.isInitialized) {
            number.hint = if (serviceIndex == 2) "Account or service number" else "Mobile number (7 digits)"
            number.contentDescription = number.hint
        }
    }

    private fun chooseService() {
        AlertDialog.Builder(this).setTitle("Choose service. Currently " + services[serviceIndex])
            .setSingleChoiceItems(services, serviceIndex) { dialog, selected ->
                dialog.dismiss()
                if (serviceIndex != selected) {
                    serviceIndex = selected
                    clearPackageSelection()
                    updateSelections()
                    pendingOperator = true
                    openFaseyhaPath(selectedPath())
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseOperator() {
        val choices = if (serviceIndex == 2) billOperators else operators
        val index = if (serviceIndex == 2) billOperatorIndex else operatorIndex
        AlertDialog.Builder(this).setTitle("Choose operator. Currently " + choices[index])
            .setSingleChoiceItems(choices, index) { dialog, selected ->
                dialog.dismiss()
                if (serviceIndex == 2) billOperatorIndex = selected else operatorIndex = selected
                clearPackageSelection()
                updateSelections()
                if (isTrusted() && Uri.parse(web.url ?: "").path == selectedPath()) {
                    applyOperator(false)
                } else {
                    pendingOperator = true
                    openFaseyhaPath(selectedPath())
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseNativePackageCategory() {
        if (serviceIndex != 1 || operatorIndex != 1) return
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            openFaseyhaPath("/packages")
            state.text = "Package page opening. Choose a category again after the website loads."
            return
        }
        val js = """(function(){
          const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
          const names=Array.from(document.querySelectorAll('button,[role="tab"],[role="button"]'))
            .filter(x=>x.getClientRects().length>0 && !x.disabled)
            .map(x=>tidy(x.innerText||x.getAttribute('aria-label')||''))
            .filter(x=>/^(all|special offer|mini data plans|weekly|monthly|data|call only|salhi|[0-9]+ ?gb)$/i.test(x))
            .filter((x,i,a)=>a.indexOf(x)===i);
          return JSON.stringify({categories:names});
        })();"""
        web.evaluateJavascript(js) { raw ->
            try {
                val arr=JSONObject(org.json.JSONArray("[$raw]").getString(0)).getJSONArray("categories")
                if (arr.length()==0) {
                    message("No readable category selector was found. Select the category on Faseyha's website, then browse packages.")
                } else choosePackageGroup(Array(arr.length()){i->arr.getString(i)})
            } catch (_: Exception) { message("Unable to retrieve package categories from Faseyha.") }
        }
    }

    private fun selectedPath(): String = when (serviceIndex) {
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
            pendingOperator = true
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
        val isBill = serviceIndex == 2
        val validTarget = if (isBill) target.matches(Regex("[A-Za-z0-9-]{5,30}"))
            else target.matches(Regex("[0-9]{7}"))
        if (!validTarget) {
            number.error = if (isBill) "Enter a valid account or service number, including any letters"
                           else "Enter a seven-digit Maldives mobile number"
            number.requestFocus()
            return
        }
        if (serviceIndex == 1) {
            if (selectedPackageLabel == null) {
                message("Choose a package first. The package selector is directly above the mobile number.")
                return
            }
            // Selected provider package is retained. Continue fills the recipient only.
            fillPackageRecipient(target)
            return
        }
        val parsed = money.toBigDecimalOrNull()
        if (parsed == null || parsed <= BigDecimal.ZERO || parsed > BigDecimal("100000") || parsed.scale() > 2) {
            amount.error = "Enter a positive amount with at most two decimal places"; amount.requestFocus(); return
        }
        AlertDialog.Builder(this).setTitle("Prepare website form")
            .setMessage("Service: ${services[serviceIndex]}\nNumber: $target\nAmount: MVR $money\n\nOnly visible matching fields will be filled. No Pay button will be pressed. Check the actual website carefully.")
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
                if (result.optBoolean("ok")) {
                    state.text = "Recipient prepared. Reading Faseyha order summary."
                    web.postDelayed({ reviewPreparedPackage(target) }, 400)
                } else message("Package recipient not filled: " + result.optString("reason"))
            } catch (_: Exception) { message("Could not verify the package recipient. No payment was submitted.") }
        }
    }

    private fun reviewPreparedPackage(target: String) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            message("The provider page changed. Package review stopped. No payment was submitted.")
            return
        }
        val chosen = selectedPackageLabel ?: run {
            message("Selected package was lost. Choose a package again.")
            return
        }
        val js = """(function(number){
           const t=(document.body.innerText||'');
           const i=t.toLowerCase().lastIndexOf('order summary');
           if(i<0) return JSON.stringify({ok:false,reason:'Order summary unavailable'});
           const summary=t.slice(i,i+950).replace(/\s+/g,' ').trim();
           const numberMatched=new RegExp('(^|\\D)'+number+'(\\D|$)').test(summary);
           const hasTotal=/total\s+to\s+pay|total\s*:?\s*(?:MVR|Rf|ރ)/i.test(summary);
           const hasPackage=/\bpackage\b/i.test(summary);
           return JSON.stringify({ok:numberMatched&&hasTotal&&hasPackage,recipientMatched:numberMatched,summary:summary});
        })(__NUMBER__);""".replace("__NUMBER__",JSONObject.quote(target))
        web.evaluateJavascript(js) { raw ->
            try {
                val result=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if (!result.optBoolean("ok")) {
                    state.text="Provider package or recipient could not be verified in order summary. Review Faseyha below. Nothing was paid."
                    message(state.text.toString())
                    return@evaluateJavascript
                }
                val summary=result.optString("summary")
                val shortName=chosen.substringBefore(" SPECIAL OFFER",chosen).take(55)
                state.text="Package recipient matches. Review provider details before payment."
                AlertDialog.Builder(this).setTitle("Review Faseyha package — no payment submitted")
                    .setMessage("Operator: ${operators[operatorIndex]}\nRecipient: $target\nSelected package: $shortName\n\nActual provider order summary:\n$summary\n\nConfirm product, taxes and total on the website before authorizing any payment. This assistant will not activate the package.")
                    .setPositiveButton("Review website",null)
                    .show()
            } catch (_:Exception) { message("Could not read the provider order summary. No payment was submitted.") }
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
                    val signature = listOf(services[serviceIndex], target, money, web.url ?: "").joinToString("|")
                    if (signature != lastPreparedSignature) {
                        FaseyhaLedger.create(this, services[serviceIndex], target, money)
                        lastPreparedSignature = signature
                    }
                }
                state.text = if (ok) "Fields match the requested details in the current website form. Review the site and submit manually if correct. Transaction not completed or verified." else "Form not prepared: ${data.optString("reason")}. Check the current service form and inspect its fields."
                message(state.text.toString())
            } catch (e: Exception) { message("Unable to verify form filling. Do not assume the fields were completed.") }
        }
    }

    private fun applyOperator(showDialog: Boolean = true) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) {
            message("Open the selected Faseyha service page before choosing an operator.")
            return
        }
        val name = if (serviceIndex == 2) billOperators[billOperatorIndex] else operators[operatorIndex]
        run {
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
                        if (showDialog || !result.optBoolean("ok")) message(state.text.toString())
                    } catch (_: Exception) { message("Operator selection could not be confirmed. Inspect the provider page manually.") }
                }
            }
    }

    private fun clearPackageSelection() {
        selectedPackageLabel = null
        selectedPackagePrice = null
        if (::packageChoice.isInitialized) {
            packageChoice.text = "Package: Not selected. Choose package"
            packageChoice.contentDescription = "Package not selected. Choose package."
        }
        if (::packageCategory.isInitialized) {
            packageCategory.text = "Package category: All. Choose category"
            packageCategory.contentDescription = "Package category, selected All. Choose category."
        }
    }

    private fun inspectPackageSummary() {
        if (serviceIndex != 1 || !isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            state.text = "Opening Package Recharge. Choose package again when the website loads."
            openFaseyhaPath("/packages")
            return
        }
        val js = """(function(){
          const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
          const tidy=t=>(t||'').replace(/\s+/g,' ').trim();
          const nodes=Array.from(document.querySelectorAll('button,[role="option"],[role="radio"],[role="button"],label,article,[class*="package"],[class*="Package"]'));
          const raw=nodes.filter(visible).map(x=>tidy(x.innerText||x.getAttribute('aria-label')||''))
            .filter(s=>s.length>=12 && s.length<=260 && /\b(gb|mb|data|minutes|days|package)\b/i.test(s))
            .filter(s=>!/(activate package|purchase now|confirm payment|pay now|checkout)/i.test(s));
          const unique=raw.filter((v,i,a)=>a.indexOf(v)===i).slice(0,160);
          const items=unique.map(s=>{
              const first=s.match(/^(.*?)(?=\s+(?:special offer|(?:\d+(?:\.\d+)?\s*(?:gb|mb|days))))/i);
              const title=(first?first[1]:s.split(/SPECIAL OFFER| \d+\s*(?:GB|MB)\s*\/day/i)[0]).trim().slice(0,85)||s.slice(0,85);
              const prices=[...s.matchAll(/(?:MVR|Rf|ރ)?\s*([\d,]+(?:\.\d{1,2})?)\s*incl\.?\s*GST/gi)];
              const price=prices.length?prices[prices.length-1][1]:'';
              return {title:title,text:s,price:price};
          });
          return JSON.stringify({items:items});
        })();"""
        web.evaluateJavascript(js) { raw ->
            try {
                val obj = JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val arr = obj.getJSONArray("items")
                if (arr.length() == 0) {
                    message("No package cards were identified. Check operator and category on Faseyha's site.")
                    return@evaluateJavascript
                }
                val names = Array(arr.length()) { i ->
                    val item = arr.getJSONObject(i)
                    item.optString("title") + (if (item.optString("price").isNotBlank()) ", MVR " + item.optString("price") + " including GST" else "")
                }
                AlertDialog.Builder(this).setTitle("Choose package — " + operators[operatorIndex])
                    .setItems(names) { _, index ->
                        val item=arr.getJSONObject(index)
                        selectPackageCard(item.optString("text"))
                    }.setNegativeButton("Cancel", null).show()
            } catch (_: Exception) { message("Unable to read available packages. Choose on provider website.") }
        }
    }

    private fun choosePackageGroup(groups:Array<String>) {
        AlertDialog.Builder(this).setTitle("Faseyha package category")
            .setItems(groups){_,index->
                if(!isTrusted() || Uri.parse(web.url ?: "").path!="/packages"){
                    message("Open the Faseyha Packages page first.")
                    return@setItems
                }
                val js="""(function(wanted){
                   const tidy=x=>(x||'').replace(/\s+/g,' ').trim().toLowerCase();
                   const shown=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
                   const nodes=Array.from(document.querySelectorAll('button,[role="tab"],[role="button"]')).filter(shown).filter(x=>!x.disabled);
                   const match=nodes.filter(x=>tidy(x.innerText||x.getAttribute('aria-label'))===tidy(wanted));
                   if(match.length!==1) return JSON.stringify({ok:false,reason:'Category not unique; select on the website.'});
                   match[0].click();
                   return JSON.stringify({ok:true,reason:'Category selected. Browse packages to review current results.'});
                })(__GROUP__);""".replace("__GROUP__",JSONObject.quote(groups[index]))
                web.evaluateJavascript(js){raw->
                    try {
                        val result=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                        if (result.optBoolean("ok")) {
                            clearPackageSelection()
                            packageCategory.text = "Package category: " + groups[index] + ". Choose category"
                            packageCategory.contentDescription = "Package category, selected " + groups[index] + ". Choose category."
                        }
                        state.text = result.optString("reason") + " No package activated."
                    }catch (_:Exception){message("Could not confirm category selection.")}
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun selectPackageCard(label: String) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") {
            message("Open Packages to select a provider package.")
            return
        }
        val js = """(function(wanted){
            const tidy=t=>(t||'').replace(/\s+/g,' ').trim();
            const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
            const nodes=Array.from(document.querySelectorAll('button,[role="option"],[role="radio"],[role="button"],label,article,[class*="package"],[class*="Package"]'));
            const match=nodes.filter(visible).filter(x=>tidy(x.innerText||x.getAttribute('aria-label')||'')===wanted)
                .filter(x=>!/(activate package|pay now|checkout|confirm payment|purchase now)/i.test(tidy(x.innerText)));
            if(match.length!==1) return JSON.stringify({ok:false,reason:'Ambiguous package card. Select on Faseyha website.'});
            const node=match[0];
            if(node.matches('form,[type="submit"],a[href]') || node.querySelector('button[type="submit"],a[href*="checkout"]'))
                return JSON.stringify({ok:false,reason:'Potential submit control. Selection blocked.'});
            node.click();
            return JSON.stringify({ok:true});
        })(__LABEL__);""".replace("__LABEL__",JSONObject.quote(label))
        web.evaluateJavascript(js) { raw ->
            try {
                val result=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if (!result.optBoolean("ok")) {
                    message(result.optString("reason","Package selection unavailable."))
                    return@evaluateJavascript
                }
                web.postDelayed({
                    if (!isTrusted() || Uri.parse(web.url ?: "").path != "/packages") return@postDelayed
                    val verify = """(function(){
                        const text=(document.body.innerText||'');
                        const i=text.lastIndexOf('Order summary');
                        if(i<0) return JSON.stringify({ok:false,reason:'Order summary unavailable.'});
                        const summary=text.slice(i,i+650).replace(/\s+/g,' ').trim();
                        return JSON.stringify({ok:/\bpackage\b/i.test(summary)&&/total/i.test(summary),summary:summary});
                    })();"""
                    web.evaluateJavascript(verify){v->
                        try {
                            val parsed=JSONObject(org.json.JSONArray("[$v]").getString(0))
                            if (!parsed.optBoolean("ok")) {
                                message("Package control was selected but provider summary is not ready. Check website manually.")
                                return@evaluateJavascript
                            }
                            selectedPackageLabel=label.take(90)
                            val prices=Regex("""([\d,]+(?:\.\d{1,2})?)\s*incl\.?\s*GST""", RegexOption.IGNORE_CASE).findAll(label).toList()
                            selectedPackagePrice=prices.lastOrNull()?.groupValues?.get(1)
                            val shortName=label.substringBefore(" SPECIAL OFFER",label).take(50)
                            val price=selectedPackagePrice
                            packageChoice.text = "Package: " + shortName + (if (price.isNullOrBlank()) "" else ", MVR " + price) + ". Change"
                            packageChoice.contentDescription = packageChoice.text
                            state.text = "Package applied to provider. Review the actual Faseyha summary before paying."
                        } catch (_:Exception) {message("Unable to verify provider package selection.") }
                    }
                },350)
            } catch (_:Exception) {message("Unable to select package on Faseyha.")}
        }
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

    private fun findHistory() {
        if (!isTrusted()) { message("Open Faseyha first."); return }
        val js="""(function(){
          const links=Array.from(document.querySelectorAll('a[href]')).map(x=>({
             title:(x.innerText||x.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,80),
             url:x.href
          })).filter(x=>{
             try{const u=new URL(x.url);return u.protocol==='https:' &&
               (u.hostname==='faseyha.net'||u.hostname.endsWith('.faseyha.net')) &&
               /history|transaction|activity|orders|receipts/i.test(u.pathname+' '+x.title);}
             catch(e){return false;}
          }).filter((v,i,a)=>a.findIndex(w=>w.url===v.url)===i).slice(0,20);
          return JSON.stringify({links:links});
        })();"""
        web.evaluateJavascript(js){raw->
            try {
                val arr=JSONObject(org.json.JSONArray("[$raw]").getString(0)).getJSONArray("links")
                if(arr.length()==0){
                    message("No visible history link on this page. Open the Faseyha website menu or dashboard, then search again. No history address was guessed.")
                    return@evaluateJavascript
                }
                val labels=Array(arr.length()){i->
                    val o=arr.getJSONObject(i)
                    o.optString("title").ifBlank{"History"}+" — "+Uri.parse(o.getString("url")).path
                }
                AlertDialog.Builder(this).setTitle("Faseyha history links")
                  .setItems(labels){_,i->
                    val url=arr.getJSONObject(i).getString("url")
                    val uri=Uri.parse(url)
                    if(uri.scheme=="https" && trusted(uri.host)){
                        AlertDialog.Builder(this).setTitle("Open provider history?")
                          .setMessage("Navigate to "+uri.path+" using the saved website session? This application will not automatically approve any transaction.")
                          .setPositiveButton("Open"){_,_->web.loadUrl(url)}
                          .setNegativeButton("Cancel",null).show()
                    }else message("History link is outside allowed website.")
                  }.setNegativeButton("Close",null).show()
            }catch (_:Exception){message("Unable to inspect Faseyha history links.")}
        }
    }

    private fun inspectResult() {
        if(!isTrusted()){message("Open the provider transaction result page first.");return}
        val js="""(function(){
          const lines=(document.body.innerText||'').split(/\n/).map(x=>x.replace(/\s+/g,' ').trim()).filter(Boolean);
          const selected=lines.filter(x=>x.length<=180 &&
             /\b(submitted|processing|pending|successful|completed|confirmed|failed|declined|cancelled|reference|receipt)\b/i.test(x)).slice(0,20);
          return JSON.stringify({path:location.pathname,lines:selected});
        })();"""
        web.evaluateJavascript(js){raw->
            try{
                val obj=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val lines=obj.getJSONArray("lines")
                val text=StringBuilder("Visible Faseyha status text on "+obj.optString("path")+":\n")
                if(lines.length()==0)text.append("No recognized status message found.")
                for(i in 0 until lines.length())text.append("\n"+(i+1)+". "+lines.getString(i))
                text.append("\n\nSubmitted or processing does not independently establish final delivery. Check Faseyha history and recipient before retrying. Local record statuses remain user-reported.")
                message(text.toString())
            }catch (_:Exception){message("Could not read a provider status message. Check transaction history directly.")}
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
