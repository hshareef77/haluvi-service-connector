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
    private val serviceChoices = arrayOf(
        "Dhiraagu Reload", "Ooredoo Raastas", "Dhiraagu Bill Pay",
        "Ooredoo Bill Pay", "Medianet Bill Pay", "Dhiraagu Packages", "Ooredoo Packages"
    )
    private var selectedServiceChoice = 0
    private var serviceIndex = 0
    private var operatorIndex = 1
    private val services = arrayOf("Recharge", "Package Recharge", "Bill Pay")
    private val operators = arrayOf("Ooredoo", "Dhiraagu")
    private val billOperators = arrayOf("Dhiraagu", "Ooredoo", "Medianet")
    private var billOperatorIndex = 0
    private var pendingOperator = false
    private var pendingPreparation = false
    private var submissionAttempted = false
    private var observingProviderResult = false
    private var pendingPaymentRecipient: String? = null
    private var pendingPaymentAmount: String? = null
    private var pendingPaymentAction: String? = null
    private var providerResultNoticeShown = false
    private var submissionStartedAt: Long = 0L
    private lateinit var walletVerifier: WebView
    private lateinit var receiptVerifier: WebView
    private var walletCheckActive = false
    private var walletCheckComplete = false
    private var walletCheckAttempts = 0
    private var receiptCheckReference: String? = null
    private var receiptCandidate: JSONObject? = null
    private var walletCheckStartedAt = 0L
    private var approvedSummary: String? = null
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
        val title = TextView(this).apply { text = "Haluvi Service Connector"; textSize = 22f; if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        layout.addView(title)
        layout.addView(TextView(this).apply {
            text = "Choose service and recipient. Review the provider total before confirming any payment."
        })
        service = Button(this).apply {
            minHeight = 56.dp
            setOnClickListener { chooseService() }
        }
        layout.addView(service)
        operator = Button(this).apply { visibility = View.GONE }
        // The selected service determines the operator: a second selector is unnecessary.
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
            hint = "Mobile number"; inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        layout.addView(number)
        amount = EditText(this).apply {
            hint = "Amount MVR"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true)
        }
        layout.addView(amount)
        state = TextView(this).apply { text = "Status: Opening Faseyha"; textSize = 15f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(state)
        val primary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun action(label: String, work: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label; minHeight = 48.dp; setOnClickListener { work() }
            })
        }
        primary.addView(Button(this).apply { text = "Continue"; minHeight = 56.dp; setOnClickListener { prepareSelectedService() } })


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
                    if (observingProviderResult && isTrusted()) view.postDelayed({ checkProviderResult(0) },700)
                    prepared = false
                    lastPreparedSignature = null
                    if (pendingOperator && isTrusted() && Uri.parse(url).path == selectedPath()) {
                        pendingOperator = false
                        web.postDelayed({ if (isTrusted() && Uri.parse(web.url ?: "").path == selectedPath()) applyOperator(false) }, 450)
                    }
                    else if (pendingPreparation && !pendingOperator && isTrusted() && Uri.parse(url).path == selectedPath()) {
                        pendingPreparation = false
                        web.postDelayed({ prepareWhenReady(0) }, 1200)
                    }
                    if (!observingProviderResult && !submissionAttempted && isTrusted()) state.text = if (Uri.parse(url).path.isNullOrBlank() || Uri.parse(url).path == "/") "Faseyha homepage loaded. Open Dashboard and a payment form in the website below before inspecting." else "Faseyha page loaded. Open the relevant number and amount form, then inspect."
                    else if (!isTrusted()) state.text = "This page is outside faseyha.net. The native field assistant is disabled."
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        layout.addView(primary)
        layout.addView(TextView(this).apply { text = "Provider website. If preparing opens a different service page, allow it to load and activate Prepare form again. The website may require further service selections. Additional browser and inspection controls are below." })
        layout.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460.dp))
        layout.addView(actions)
        // Read-only background browser shares the authenticated Faseyha cookie jar.
        // It never submits transactions. Keep it out of accessibility focus.
        walletVerifier = WebView(this).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    return request.url.scheme!="https" || !trusted(request.url.host)
                }
                override fun onPageFinished(view:WebView,url:String) {
                    if(walletCheckActive && Uri.parse(url).path=="/wallet" && trusted(Uri.parse(url).host))
                        view.postDelayed({ scanVerificationWallet(0) },1000)
                }
            }
        }
        layout.addView(walletVerifier,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1.dp))
        receiptVerifier = WebView(this).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    return request.url.scheme!="https" || !trusted(request.url.host)
                }
                override fun onPageFinished(view:WebView,url:String) {
                    if(walletCheckActive && receiptCheckReference!=null &&
                        Uri.parse(url).path?.startsWith("/invoice/")==true && trusted(Uri.parse(url).host))
                        view.postDelayed({ inspectVerificationReceipt() },650)
                }
            }
        }
        layout.addView(receiptVerifier,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1.dp))
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(layout) }
        setContentView(scroller)
        web.loadUrl(homeUrl)
    }

    private fun updateSelections() {
        val name=serviceChoices[selectedServiceChoice]
        service.text="Service: $name. Change"
        service.contentDescription="Service, selected $name. Double tap to change."
        operator.visibility=View.GONE
        if (::packageCategory.isInitialized)
            packageCategory.visibility=if(serviceIndex==1 && operatorIndex==1) View.VISIBLE else View.GONE
        if (::packageChoice.isInitialized)
            packageChoice.visibility=if(serviceIndex==1) View.VISIBLE else View.GONE
        if (::amount.isInitialized) {
            amount.visibility=if(serviceIndex==1) View.GONE else View.VISIBLE
            amount.isEnabled=serviceIndex!=1
        }
        if (::number.isInitialized) {
            val bill=serviceIndex==2
            number.hint=if(bill) "Account or service number" else "Mobile number (7 digits)"
            number.contentDescription=number.hint
            number.inputType=if(bill)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                else InputType.TYPE_CLASS_PHONE
        }
    }

    private fun chooseService() {
        AlertDialog.Builder(this).setTitle("Choose service. Currently "+serviceChoices[selectedServiceChoice])
            .setSingleChoiceItems(serviceChoices,selectedServiceChoice){dialog,selected->
                dialog.dismiss()
                if(selected!=selectedServiceChoice) {
                    if(submissionAttempted) {
                        message("A payment was already attempted. Check Wallet before preparing another transaction.")
                        return@setSingleChoiceItems
                    }
                    selectedServiceChoice=selected
                    when(selected) {
                        0 -> {serviceIndex=0;operatorIndex=1}
                        1 -> {serviceIndex=0;operatorIndex=0}
                        2 -> {serviceIndex=2;billOperatorIndex=0}
                        3 -> {serviceIndex=2;billOperatorIndex=1}
                        4 -> {serviceIndex=2;billOperatorIndex=2}
                        5 -> {serviceIndex=1;operatorIndex=1}
                        else -> {serviceIndex=1;operatorIndex=0}
                    }
                    number.text?.clear()
                    amount.text?.clear()
                    clearPackageSelection()
                    updateSelections()
                    pendingOperator=true
                    openFaseyhaPath(selectedPath())
                }
            }.setNegativeButton("Cancel",null).show()
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
            pendingPreparation = true
            openFaseyhaPath(expected)
            state.text = "Opening provider service and preparing automatically."
            return
        }
        // The service selector may have scheduled a provider operator click.
        // Do not fill fields against the previous operator while that runs.
        if(pendingOperator) {
            pendingPreparation=true
            state.text="Waiting for selected provider operator before preparing the form."
            return
        }
        prepareWhenReady(0)
    }

    private fun prepareWhenReady(attempt:Int) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) return
        if(pendingOperator) {
            if(attempt<15)web.postDelayed({prepareWhenReady(attempt+1)},650)
            else message("Provider operator selection is incomplete. No payment submitted.")
            return
        }
        if (serviceIndex == 1) { prepare(); return }
        if(attempt>=15) { message("Provider form is not ready. Check operator selection and try Continue again. No payment was submitted."); return }
        val js = """(function(){return Array.from(document.querySelectorAll('input')).filter(x=>x.getClientRects().length>0 && !x.disabled && !x.readOnly && !/hidden|password|submit|button|radio|checkbox/.test(x.type)).length})()"""
        web.evaluateJavascript(js) { raw ->
            if ((raw.trim().toIntOrNull() ?: 0) >= 2) prepare()
            else web.postDelayed({ prepareWhenReady(attempt+1) },650)
        }
    }

    private fun openFaseyhaPath(path: String) {
        if (path !in setOf("/", "/dashboard", "/recharge", "/packages", "/bills")) {
            message("Unsupported service path.")
            return
        }
        prepared = false
        submissionAttempted = false
        observingProviderResult = false
        providerResultNoticeShown = false
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
        fill(target, parsed.toPlainString())
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
                    .setMessage("Operator: ${operators[operatorIndex]}\nRecipient: $target\nSelected package: $shortName\n\nActual provider order summary:\n$summary\n\nConfirm the amount and total before selecting Activate package.")
                    .setPositiveButton("Confirm"){_,_->submitOnce(target,null,summary)}
                    .setNegativeButton("Review website",null)
                    .show()
            } catch (_:Exception) { message("Could not read the provider order summary. No payment was submitted.") }
        }
    }

    private fun fill(target: String, money: String, attempt: Int = 0) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path != selectedPath()) { message("The website changed. Reopen the selected Faseyha page."); return }
        // Serialize arguments through JSONObject.quote, never interpolate raw user input into JavaScript.
        val js = """(function(number,amount){
           const visible=x=>x.getClientRects().length>0 && !x.disabled && !x.readOnly;
           const fields=Array.from(document.querySelectorAll('input')).filter(visible)
             .filter(x=>!['hidden','password','email','submit','button','checkbox','radio'].includes((x.type||'').toLowerCase()));
           const label=x=>[(x.labels&&x.labels.length?x.labels[0].innerText:''),x.getAttribute('aria-label'),
             x.getAttribute('placeholder'),x.name,x.id].filter(Boolean).join(' ').toLowerCase();
           let num=fields.filter(x=>/(mobile|phone|number|account|subscriber|service|7xxxxxxx)/.test(label(x)));
           let amt=fields.filter(x=>/(amount|price|pay|mvr)/.test(label(x)));
           if((num.length!==1||amt.length!==1||num[0]===amt[0])&&fields.length===2){
             const numeric=fields.filter(x=>/amount|mvr|price/i.test(label(x)));
             if(numeric.length===1){amt=numeric;num=fields.filter(x=>x!==amt[0]);}
             else if(fields.length===2){num=[fields[0]];amt=[fields[1]];}
           }
           if(num.length!==1 || amt.length!==1 || num[0]===amt[0])
             return JSON.stringify({ok:false,n:num.length,a:amt.length,reason:'Found '+num.length+' number fields and '+amt.length+' amount fields; need exactly one of each'});
           function update(x,value){
              const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              setter.call(x,value);x.dispatchEvent(new Event('input',{bubbles:true}));
              x.dispatchEvent(new Event('change',{bubbles:true}));
              // Some bill-pay forms validate after the field loses focus.
              x.dispatchEvent(new FocusEvent('blur'));
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
                state.text = if (ok) "Fields match. Preparing final review." else "Form not prepared: ${data.optString("reason")}. Check the current service form."
                if(ok) web.postDelayed({ reviewWhenReady(target,money,0) },600)
                else if(attempt<8 && data.optInt("n",0)==0 && data.optInt("a",0)==0) web.postDelayed({fill(target,money,attempt+1)},650)
                else message(state.text.toString())
            } catch (e: Exception) { message("Unable to verify form filling. Do not assume the fields were completed.") }
        }
    }


    // A reactive provider form can update its summary and enable the submit button
    // AFTER native input/change events. Wait for the visible provider state to settle
    // before displaying the final authorization dialog.
    // Wait for Faseyha's validation before allowing the native payment review.
    // This does not click any payment control.
    private fun reviewWhenReady(target:String,money:String,attempt:Int) {
        if (!isTrusted() || Uri.parse(web.url ?: "").path!=selectedPath()) {
            message("Provider page changed. Nothing was submitted.");return
        }
        val script="""(function(target,amount,operatorName){
            const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
            const body=document.body.innerText||'',i=body.toLowerCase().lastIndexOf('order summary');
            const summary=i<0?'':tidy(body.slice(i,i+900));
            const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
            const action=__ACTION__;
            const candidates=Array.from(document.querySelectorAll('button,input[type="submit"],[role="button"]'))
                .filter(visible).filter(x=>{
                    const labels=[x.innerText,x.value,x.getAttribute('aria-label')].filter(Boolean)
                        .map(v=>tidy(v).toLowerCase());
                    return labels.some(label=>action==='Recharge'
                        ?/^recharge(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/.test(label)
                        :/^pay(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/.test(label));
                });
            const enabled=candidates.filter(x=>!x.disabled &&
                x.getAttribute('aria-disabled')!=='true' && getComputedStyle(x).pointerEvents!=='none');
            const inputs=Array.from(document.querySelectorAll('input'))
                .filter(x=>visible(x)&&!x.disabled&&!['password','hidden','submit','button','radio','checkbox'].includes((x.type||'').toLowerCase()));
            const numberMatch=inputs.some(x=>tidy(x.value)===target);
            const amountMatch=inputs.some(x=>Number(x.value)===Number(amount));
            const invalid=inputs.filter(x=>x.required && !x.checkValidity())
                .map(x=>tidy(x.getAttribute('aria-label')||x.placeholder||x.name||x.id).slice(0,60));
            // Faseyha BillPay shows its operator in an active tab above the form.
            // The order summary may omit the operator, so verify the selected
            // tab instead of incorrectly treating its absence as a mismatch.
            const selector=Array.from(document.querySelectorAll('button,[role="tab"],[role="radio"],[role="button"]'))
                .filter(visible).filter(x=>{
                    const label=tidy(x.innerText||x.getAttribute('aria-label')||'').toLowerCase();
                    return ['dhiraagu','ooredoo','medianet'].includes(label);
                });
            function chosen(x){
                if(x.getAttribute('aria-selected')==='true'||x.getAttribute('aria-checked')==='true'||
                    x.getAttribute('data-state')==='active'||x.getAttribute('data-state')==='checked'||
                    x.getAttribute('aria-pressed')==='true') return true;
                const cls=(' '+(typeof x.className==='string'?x.className:'')+' ').toLowerCase();
                return /(?:^|[\s_-])(selected|active|checked|current)(?:[\s_-]|$)/.test(cls);
            }
            const selected=selector.filter(chosen);
            const explicitTab=selector.length===3 && selected.length===1 &&
                tidy(selected[0].innerText||selected[0].getAttribute('aria-label')).toLowerCase()===operatorName.toLowerCase();
            // BillPay renders "Ooredoo / Supernet" and a Quick Pay card instead of an
            // Order summary. Verify its provider-specific heading and card.
            const heading=Array.from(document.querySelectorAll('h1,h2,h3,h4,[role="heading"]'))
                .filter(visible).map(x=>tidy(x.innerText||x.textContent).toLowerCase());
            const providerCard=body.toLowerCase().includes('pay any '+operatorName.toLowerCase()) ||
                (operatorName==='Ooredoo' && body.toLowerCase().includes('pay any ooredoo postpaid'));
            const providerHeading=heading.some(x=>x===operatorName.toLowerCase() ||
                x.startsWith(operatorName.toLowerCase()+' /')) ||
                (operatorName==='Ooredoo' && body.toLowerCase().includes('ooredoo / supernet'));
            const billOperatorMatch=explicitTab || (providerHeading && providerCard);
            const operatorMatch=action==='Pay' ? billOperatorMatch :
                (summary.toLowerCase().includes(operatorName.toLowerCase()) || explicitTab);
            return JSON.stringify({summary:summary,found:candidates.length,enabled:enabled.length,
                numberMatch:numberMatch,amountMatch:amountMatch,invalid:invalid,
                operatorMatch:operatorMatch,selectedOperator:selected.length===1?tidy(selected[0].innerText):'',
                providerHeading:providerHeading,providerCard:providerCard,
                operatorControlCount:selector.length});
        })(__TARGET__,__MONEY__,__OP__);"""
            .replace("__ACTION__",JSONObject.quote(if(serviceIndex==0)"Recharge" else "Pay"))
            .replace("__TARGET__",JSONObject.quote(target))
            .replace("__MONEY__",JSONObject.quote(money))
            .replace("__OP__",JSONObject.quote(if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]))
        web.evaluateJavascript(script){raw->
            try{
                val data=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val summary=data.optString("summary")
                if((serviceIndex==2 || summary.isNotBlank()) && data.optInt("enabled")==1 &&
                    data.optBoolean("numberMatch") && data.optBoolean("amountMatch") &&
                    data.optBoolean("operatorMatch")) {
                    reviewFinalAction(target,money)
                } else if(attempt<19) {
                    if(attempt==4 || attempt==12)
                        state.text="Waiting for Faseyha to validate the account and enable payment. No payment has been submitted."
                    web.postDelayed({reviewWhenReady(target,money,attempt+1)},650)
                } else {
                    val invalid=data.optJSONArray("invalid")
                    val missing=if(invalid!=null && invalid.length()>0)
                        (0 until invalid.length()).joinToString(", "){invalid.optString(it)} else ""
                    val reason=when {
                        !data.optBoolean("numberMatch") || !data.optBoolean("amountMatch") ->
                            "The provider form did not retain the recipient or amount."
                        summary.isBlank() && serviceIndex!=2 ->
                            "Faseyha has not generated an order summary for this account."
                        !data.optBoolean("operatorMatch") ->
                            "Faseyha operator could not be verified from its order summary or active operator tab. No payment is authorized."
                        missing.isNotBlank() ->
                            "Faseyha requires additional information: $missing."
                        data.optInt("found")==0 ->
                            "The Faseyha payment control was not found."
                        data.optInt("enabled")==0 ->
                            "Faseyha's Pay button is still disabled. The provider may require account validation or an additional form step."
                        else -> "More than one payment control was found; automatic payment was blocked."
                    }
                    state.text="$reason No payment was submitted. Review the website below."
                    message(state.text.toString())
                }
            }catch(_:Exception){message("Unable to verify provider readiness. No payment submitted.")}
        }
    }

    private fun reviewFinalAction(target:String,money:String) {
        if(!isTrusted() || Uri.parse(web.url ?: "").path!=selectedPath()) {
            message("Provider page changed. Review cancelled.");return
        }
        val js="""(function(){
            const t=document.body.innerText||'',i=t.toLowerCase().lastIndexOf('order summary');
            return JSON.stringify({summary:i>=0?t.slice(i,i+900).replace(/\s+/g,' ').trim():''});
        })();"""
        web.evaluateJavascript(js){raw->
            try{
                val summary=JSONObject(org.json.JSONArray("[$raw]").getString(0)).optString("summary")
                val op=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
                val action=if(serviceIndex==0)"Recharge" else "Pay"
                val details="Service: "+serviceChoices[selectedServiceChoice]+
                    "\nRecipient/account: "+target+"\nAmount: MVR "+money+
                    (if(summary.isNotBlank())"\n\nFaseyha order summary:\n"+summary else
                        "\n\nFaseyha Quick Pay does not display an order summary. The selected provider, account, amount and enabled Pay control were verified.")+
                    "\n\nConfirm authorizes this payment once. No payment has been submitted yet."
                AlertDialog.Builder(this).setTitle("Review "+action+" — not submitted")
                    .setMessage(details)
                    .setPositiveButton("Confirm"){_,_->submitOnce(target,money,summary)}
                    .setNegativeButton("Review website",null).show()
            }catch(_:Exception){message("Provider summary cannot be read. No payment submitted.")}
        }
    }

    private fun confirmFinalSubmission(target:String,money:String?,reviewedSummary:String) {
        if(submissionAttempted){message("A provider action was already attempted. Check history before retrying.");return}
        val op=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
        val action=when(serviceIndex){0->"Recharge";1->"Activate package";else->"Pay"}
        val detail="This will press Faseyha's actual "+action+" button and may spend wallet funds immediately.\n\nService: "+
            services[serviceIndex]+"\nOperator: "+op+"\nRecipient/account: "+target+
            (if(serviceIndex==1)"\nPackage: "+selectedPackageLabel.orEmpty() else "\nAmount: MVR "+money.orEmpty())+
            "\n\nVerify provider total. Never retry an uncertain payment without checking its history."
        AlertDialog.Builder(this).setTitle("Final confirmation — "+action)
            .setMessage(detail).setNegativeButton("Cancel",null)
            .setPositiveButton("Confirm "+action){_,_->submitOnce(target,money,reviewedSummary)}.show()
    }

    private fun submitOnce(target:String,money:String?,reviewedSummary:String) {
        if(submissionAttempted || !isTrusted() || Uri.parse(web.url?:"").path!=selectedPath()
            || number.text.toString().trim()!=target
            || (money!=null && amount.text.toString().trim().toBigDecimalOrNull()!=money.toBigDecimalOrNull())) {
            message("Provider page or request values changed. No submission.");return
        }
        val op=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
        val action=when(serviceIndex){0->"Recharge";1->"Activate package";else->"Pay"}
        val js="""(function(path,recipient,money,operatorName,action,priorSummary){
            if(location.protocol!=='https:'||location.hostname!=='faseyha.net'||location.pathname!==path)
                return JSON.stringify({ok:false,reason:'Provider URL changed'});
            const clean=t=>(t||'').replace(/\s+/g,' ').trim();
            const text=document.body.innerText||'',i=text.toLowerCase().lastIndexOf('order summary');
            const summary=i<0?'':clean(text.slice(i,i+900));
            if(priorSummary && priorSummary!==summary) return JSON.stringify({ok:false,reason:'Order summary changed'});
            if(!new RegExp('(^|\\D)'+recipient+'(\\D|$)').test(summary||text))
                return JSON.stringify({ok:false,reason:'Recipient not confirmed'});
            const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
            const tabs=Array.from(document.querySelectorAll('button,[role="tab"],[role="radio"],[role="button"]'))
                .filter(visible).filter(x=>['dhiraagu','ooredoo','medianet'].includes(
                    clean(x.innerText||x.getAttribute('aria-label')).toLowerCase()));
            const active=x=>{
                if(['aria-selected','aria-checked','aria-pressed'].some(k=>x.getAttribute(k)==='true')||
                    ['active','checked'].includes(x.getAttribute('data-state')))return true;
                const cls=(' '+(typeof x.className==='string'?x.className:'')+' ').toLowerCase();
                return /(?:^|[\s_-])(selected|active|checked|current)(?:[\s_-]|$)/.test(cls);
            };
            const activeTabs=tabs.filter(active);
            const selectedTab=tabs.length===3 && activeTabs.length===1 &&
                clean(activeTabs[0].innerText||activeTabs[0].getAttribute('aria-label')).toLowerCase()===operatorName.toLowerCase();
            const headings=Array.from(document.querySelectorAll('h1,h2,h3,h4,[role="heading"]'))
                .filter(visible).map(x=>clean(x.innerText||x.textContent).toLowerCase());
            const providerHeading=headings.some(x=>x===operatorName.toLowerCase()||
                x.startsWith(operatorName.toLowerCase()+' /'))||
                (operatorName==='Ooredoo' && text.toLowerCase().includes('ooredoo / supernet'));
            const providerCard=text.toLowerCase().includes('pay any '+operatorName.toLowerCase())||
                (operatorName==='Ooredoo' && text.toLowerCase().includes('pay any ooredoo postpaid'));
            const billOperatorMatched=selectedTab || (providerHeading && providerCard);
            const operatorMatched=action==='Pay'?billOperatorMatched:
                (summary.toLowerCase().includes(operatorName.toLowerCase()) || selectedTab);
            if(!operatorMatched)
                return JSON.stringify({ok:false,reason:'Selected provider does not match the authorized transaction'});
            if(action==='Activate package'){
                if(!summary||!/\bpackage\b/i.test(summary)||!/\btotal\b/i.test(summary))
                    return JSON.stringify({ok:false,reason:'Provider package summary missing'});
            } else {
                const fields=Array.from(document.querySelectorAll('input')).filter(x=>x.getClientRects().length>0&&!x.disabled);
                if(!fields.some(x=>clean(x.value)===recipient)||!fields.some(x=>Number(x.value)===Number(money)))
                    return JSON.stringify({ok:false,reason:'Provider inputs no longer match'});
                if(action==='Recharge' && (!summary||!/\btotal\b/i.test(summary)))
                    return JSON.stringify({ok:false,reason:'Recharge summary missing'});
            }
            // The provider can display "Recharge 12.00" instead of just "Recharge".
            // Never click if missing, disabled or ambiguous.
            const candidates=Array.from(document.querySelectorAll('button,input[type="submit"],[role="button"]'))
                .filter(visible).filter(x=>{
                    const raw=[x.innerText,x.value,x.getAttribute('aria-label')].filter(Boolean).map(clean);
                    return raw.some(label=>{
                        const value=label.toLowerCase();
                        return action==='Recharge'?/^recharge(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/.test(value):
                          action==='Pay'?/^pay(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/.test(value):
                          /^activate package(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/.test(value);
                    });
                });
            const matches=candidates.filter(x=>!x.disabled && x.getAttribute('aria-disabled')!=='true' && getComputedStyle(x).pointerEvents!=='none');
            if(matches.length!==1)return JSON.stringify({ok:false,reason:matches.length===0?
              'Faseyha action not enabled. Verify operator, recipient, amount or package on provider form (matching '+candidates.length+' disabled controls).':
              'Ambiguous enabled provider actions: '+matches.length});
            window.__haluviBeforeSubmit=(document.body.innerText||"").split(/\n/).map(x=>x.trim()).filter(Boolean);
            try{sessionStorage.setItem('haluvi_before_submit',JSON.stringify(window.__haluviBeforeSubmit));}catch(e){}
            matches[0].click();return JSON.stringify({ok:true});
        })(__PATH__,__TARGET__,__MONEY__,__OP__,__ACTION__,__SUMMARY__);"""
            .replace("__PATH__",JSONObject.quote(selectedPath()))
            .replace("__TARGET__",JSONObject.quote(target))
            .replace("__MONEY__",JSONObject.quote(money ?: ""))
            .replace("__OP__",JSONObject.quote(op))
            .replace("__ACTION__",JSONObject.quote(action))
            .replace("__SUMMARY__",JSONObject.quote(reviewedSummary))
        web.evaluateJavascript(js){raw->
            try{
                val obj=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if(obj.optBoolean("ok")) {
                    pendingPaymentRecipient=target
                    pendingPaymentAmount=money
                    pendingPaymentAction=action
                    submissionAttempted=true
                    submissionStartedAt=System.currentTimeMillis()
                    approvedSummary=reviewedSummary
                    walletCheckActive=false
                    walletCheckComplete=false
                    walletCheckAttempts=0
                    observingProviderResult=true
                    providerResultNoticeShown=false
                    state.text="Provider "+action+" button pressed once. Checking Faseyha response. Do not retry."
                    web.postDelayed({ checkProviderResult(0) },750)
                } else {
                    state.text="Submission blocked: "+obj.optString("reason")+". No provider transaction was confirmed."
                    message(state.text.toString())
                }
            }catch(_:Exception){submissionAttempted=true;message("Submission result uncertain. Check Wallet; do not retry automatically.")}
        }
    }


    // Inspect visible changes after the user's confirmed provider action.
    // Never turn a button-click acknowledgment into a success claim.
    private fun checkProviderResult(attempt:Int) {
        if (!observingProviderResult || providerResultNoticeShown || isFinishing) return
        if (attempt > 35) {
            observingProviderResult = false
            providerResultNoticeShown = true
            state.text = "Request processed. Checking Wallet internally; do not retry."
            startInternalWalletCheck()
            return
        }
        if(!isTrusted()) {
            observingProviderResult=false
            providerResultNoticeShown=true
            state.text="Provider page changed. Checking Wallet internally; no payment will be repeated."
            startInternalWalletCheck()
            return
        }
        val js="""(function(){
            const clean=x=>(x||'').replace(/\s+/g,' ').trim();
            let old=window.__haluviBeforeSubmit||[];
            if(!old.length){try{old=JSON.parse(sessionStorage.getItem("haluvi_before_submit")||"[]");}catch(e){}}
            const oldSet=new Set(old.map(clean));
            // The first Recharge click can open a provider confirmation modal.
            const visible=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
            const desired=__ACTION__.toLowerCase();
            const titleRe=desired==='Recharge'?/^confirm recharge$/i:desired==='Pay'?/^confirm (?:bill )?pay(?:ment)?$/i:/^confirm (?:package|activation)$/i;
            // Locate the actual Faseyha confirmation by its unique visible
            // action pair. Do not depend on a heading tag: the provider often
            // renders its "Confirm recharge" title in a plain div.
            const label=x=>clean(x.innerText||x.value||x.getAttribute('aria-label')||x.textContent);
            const enabled=x=>visible(x)&&!x.disabled&&x.getAttribute('aria-disabled')!=='true';
            const buttons=Array.from(document.querySelectorAll('button,[role="button"],input[type="submit"]')).filter(visible);
            const positive=buttons.filter(x=>/^(confirm\s*(?:&|and)\s*recharge|confirm\s+recharge|confirm\s*(?:&|and)\s*pay|confirm\s+payment|confirm\s+activation)$/i.test(label(x)));
            let confirmation=null;
            if(positive.length===1&&enabled(positive[0])){
              let p=positive[0].parentElement;
              for(let d=0;p&&d<9;d++,p=p.parentElement){
                const body=clean(p.innerText||'');
                if(body.length>1200)break;
                const siblings=Array.from(p.querySelectorAll('button,[role="button"],input[type="submit"]')).filter(visible);
                if(siblings.some(x=>/^(cancel|back|close|no)$/i.test(label(x)))
                   &&siblings.filter(x=>x===positive[0]).length===1
                   &&/confirm\s+(?:recharge|payment|bill|activation)/i.test(body)){
                  confirmation={summary:body,positive:label(positive[0])};break;
                }
              }
            }
            if(confirmation)return JSON.stringify({classification:'Awaiting confirmation',message:confirmation.summary,actionLabel:confirmation.positive,pathname:location.pathname});
            const notices=Array.from(document.querySelectorAll('[role="alert"],[role="status"],[aria-live],.toast,.notification,.alert,[role="dialog"]'))
                .filter(x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden')
                .map(x=>clean(x.innerText||x.textContent)).filter(x=>x && x.length<500 && !oldSet.has(x));
            const fresh=(document.body.innerText||'').split(/\n/).map(clean)
                .filter(x=>x && x.length<350 && !oldSet.has(x));
            const all=[...new Set([...notices,...fresh])];
            let classification='Unknown',message='';
            for(const line of all){
                if(/insufficient (?:wallet )?(?:balance|funds)|not enough (?:balance|funds)/i.test(line)){
                    classification='Insufficient balance';message=line;break;
                }
                if(/\b(failed|declined|unsuccessful|rejected|payment error|transaction error)\b/i.test(line)){
                    classification='Failed';message=line;break;
                }
                if(/\bbill\s*payment\s*successful\b/i.test(line) &&
                   /\b(being processed|submitted|notify you once|once it.s confirmed)\b/i.test(document.body.innerText||'')){
                    classification='Provider accepted payment';message=line;break;
                }
                if(/\b(successful|successfully|payment completed|transaction completed|activated successfully|recharge complete)\b/i.test(line)){
                    classification='Provider reported success';message=line;break;
                }
                if(/\b(processing|pending|submitted|being processed)\b/i.test(line)){
                    classification='Processing';message=line;break;
                }
            }
            return JSON.stringify({classification:classification,message:message,pathname:location.pathname});
        })();""".replace("__ACTION__",JSONObject.quote(pendingPaymentAction ?: ""))
        web.evaluateJavascript(js){raw->
            if(!observingProviderResult || providerResultNoticeShown || isFinishing)return@evaluateJavascript
            try{
                val result=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val classification=result.optString("classification","Unknown")
                if(classification=="Awaiting confirmation"){
                    observingProviderResult=false
                    showSecondStageConfirmation(result.optString("message"), result.optString("actionLabel"))
                } else if(classification=="Processing"){
                    // Processing is not a final result. Continue observing without resubmitting.
                    if(attempt==0 || attempt==10 || attempt==25)
                        state.text="Faseyha is processing. Waiting for a final result; do not retry."
                    if(attempt==7) startInternalWalletCheck()
                    web.postDelayed({checkProviderResult(attempt+1)},1100)
                } else if(classification!="Unknown"){
                    observingProviderResult=false
                    providerResultNoticeShown=true
                    val providerMessage=result.optString("message").take(350)
                    state.text="Faseyha response: $classification. Verify the provider transaction in Wallet."
                    if(classification=="Provider reported success" || classification=="Provider accepted payment") {
                        state.text="Faseyha accepted the request. Verifying final Wallet status without resubmitting."
                        web.postDelayed({if(!isFinishing)startInternalWalletCheck()},800)
                    }else {
                        AlertDialog.Builder(this).setTitle("Faseyha response — $classification")
                            .setMessage("Provider displayed:\n$providerMessage\n\nCheck Wallet and receipt before retrying. A website response alone is not proof of delivery.")
                            .setPositiveButton("Check Wallet internally"){_,_->startInternalWalletCheck()}
                            .setNegativeButton("Close",null).show()
                    }
                }else web.postDelayed({checkProviderResult(attempt+1)},900)
            }catch(_:Exception){
                web.postDelayed({checkProviderResult(attempt+1)},900)
            }
        }
    }

    // Internal read-only reconciliation. No navigation and no payment retries.
    private fun startInternalWalletCheck() {
        if (walletCheckComplete || isFinishing || submissionStartedAt==0L) return
        if (!::walletVerifier.isInitialized) return
        if (walletCheckActive) return
        walletCheckActive=true
        walletCheckStartedAt=System.currentTimeMillis()
        state.text="Your request was submitted. Verifying transaction in Faseyha Wallet. Please wait."
        walletVerifier.loadUrl("https://faseyha.net/wallet")
    }

    private fun scanVerificationWallet(pass:Int) {
        if (!walletCheckActive || walletCheckComplete || isFinishing || !::walletVerifier.isInitialized) return
        val uri=Uri.parse(walletVerifier.url ?: "")
        if(uri.scheme!="https" || !trusted(uri.host) || uri.path!="/wallet"){
            scheduleWalletRetry()
            return
        }
        // Select the read-only Recharges category where necessary. Never click a
        // recharge/pay action in this browser.
        val js=StringBuilder().append("""(function(){
            const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
            if(__CHOOSE__){
              const tabs=Array.from(document.querySelectorAll('button,[role="tab"],[role="button"]'))
                .filter(x=>x.getClientRects().length>0 && tidy(x.innerText||x.getAttribute('aria-label')).toLowerCase()==='recharges');
              if(tabs.length===1)tabs[0].click();
              return JSON.stringify({rows:[]});
            }
            const links=Array.from(document.querySelectorAll('a[href]'))
                .filter(x=>/receipt|tax invoice/i.test(tidy(x.innerText||x.getAttribute('aria-label')))||
                   /\/invoice\//i.test(x.href));
            const statusPattern=/\b(successful|completed|processing|pending|failed|submitted)\b/i;
            const referencePattern=/\b(?:FR|BLA)[A-Z0-9]{8,40}\b/i;
            const datePattern=/\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}(?:,\s*|\s+)\d{1,2}:\d{2}\s*(?:AM|PM)\b/i;
            const rows=[],seen=new Set();
            for(const a of links){
              let item=null,p=a;
              for(let n=0;n<10&&p;n++,p=p.parentElement){
                const t=tidy(p.innerText||'');
                if(t.length>=20&&t.length<=900&&referencePattern.test(t)&&
                    statusPattern.test(t)&&datePattern.test(t)){item=p;break;}
              }
              if(!item)continue;
              const t=tidy(item.innerText||'');
              const ref=(t.match(referencePattern)||[])[0]||'';
              if(!ref||seen.has(ref))continue;
              seen.add(ref);
              const status=(t.match(statusPattern)||[])[1]||'Unknown';
              const date=(t.match(datePattern)||[])[0]||'';
              const service=tidy(t.slice(0,t.indexOf(ref))).slice(0,100);
              const leaves=Array.from(item.querySelectorAll('*'))
                .filter(x=>x.getClientRects().length&&x.children.length===0)
                .map(x=>tidy(x.textContent)).filter(x=>x.length&&x.length<45);
              const values=leaves.filter(x=>/^[+−-]?\s*(?:MVR|Rf|ރ|ރ\.)?\s*[\d,]+(?:\.\d{1,2})?$/.test(x))
                .filter(x=>/[+−-]|(?:MVR|Rf|ރ)/i.test(x)||/\d{1,3},\d{3}/.test(x))
                .map(x=>x.replace(/[^\d.,]/g,''));
              let amount=values.length===1?values[0]:'';
              if(!amount){
                const v=t.match(/(?:MVR|Rf|ރ|ރ\.)\s*([+-]?\s*[\d,]+(?:\.\d{1,2})?)/i);
                if(v)amount=v[1].trim().replace(/^[+−-]\s*/,'');
              }
              if(!amount){
                const ix=t.search(statusPattern),pre=ix>=0?t.slice(Math.max(0,ix-36),ix):'';
                const v=pre.match(/(?:^|\s)([+-]?\s*\d[\d,]*(?:\.\d{1,2})?)\s*$/);
                if(v)amount=v[1].trim().replace(/^[+−-]\s*/,'');
              }
              let url='';
              try{
                const u=new URL(a.href);
                if(u.protocol==='https:'&&u.hostname==='faseyha.net'&&u.pathname.startsWith('/invoice/'))
                  url=u.href;
              }catch(e){}
              rows.push({reference:ref,service:service,status:status,date:date,amount:amount,url:url});
            }
            return JSON.stringify({rows:rows.slice(0,75)});
        })();""").toString().replace("__CHOOSE__",if(pass==0)"true" else "false")
        walletVerifier.evaluateJavascript(js){raw->
            if(!walletCheckActive || walletCheckComplete || isFinishing)return@evaluateJavascript
            try {
                val outer=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if(pass==0){
                    walletVerifier.postDelayed({scanVerificationWallet(1)},950)
                    return@evaluateJavascript
                }
                val rows=outer.optJSONArray("rows") ?: org.json.JSONArray()
                val matches=mutableListOf<JSONObject>()
                for(i in 0 until rows.length()){
                    val row=rows.optJSONObject(i) ?: continue
                    if(isMatchingWalletCandidate(row))matches.add(row)
                }
                if(matches.size>1){
                    state.text="Several recent transactions match. Automatic verification is uncertain. Check receipts before retrying."
                    walletCheckActive=false
                    walletCheckComplete=true
                }else if(matches.size==1){
                    receiptCandidate=matches.first()
                    val url=matches.first().optString("url")
                    if(url.startsWith("https://faseyha.net/invoice/") && ::receiptVerifier.isInitialized){
                        receiptCheckReference=matches.first().optString("reference")
                        receiptVerifier.loadUrl(url)
                    }else {
                        // A unique wallet row without a matching recipient receipt
                        // is suggestive, but not proof of this specific recipient.
                        state.text="Faseyha Wallet shows a recent "+matches.first().optString("status")+
                            " charge matching the service and amount. Recipient not verified; review receipt."
                        walletCheckActive=false
                        walletCheckComplete=true
                    }
                } else scheduleWalletRetry()
            }catch (_:Exception){scheduleWalletRetry()}
        }
    }

    private fun isMatchingWalletCandidate(row:JSONObject):Boolean {
        val recipient=pendingPaymentRecipient ?: return false
        if(recipient.isBlank() || submissionStartedAt<=0L)return false
        val operator=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
        val name=row.optString("service")
        if(!name.contains(operator,true))return false
        val kind=when(serviceIndex){
            0->name.contains("reload",true)||name.contains("raastas",true)||
                (name.contains("recharge",true)&&!name.contains("package",true))
            1->name.contains("package",true)||name.contains("data",true)
            else->name.contains("bill",true)
        }
        if(!kind)return false
        val amountExpected=(pendingPaymentAmount ?: selectedPackagePrice)?.replace(",","")
            ?.toBigDecimalOrNull()
        val amountSeen=row.optString("amount").replace(",","").toBigDecimalOrNull()
        if(amountExpected==null || amountSeen==null ||
            amountExpected.compareTo(amountSeen)!=0)return false
        val date=row.optString("date")
        val epoch=try {
            val format=java.text.SimpleDateFormat("MMM d, h:mm a",java.util.Locale.ENGLISH)
            format.isLenient=false
            val parsed=format.parse(date.replace(Regex("\\s+")," ").trim()) ?: return false
            val cal=java.util.Calendar.getInstance().apply {
                time=parsed
                set(java.util.Calendar.YEAR,java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
            }
            if(cal.timeInMillis>System.currentTimeMillis()+86400000L)
                cal.add(java.util.Calendar.YEAR,-1)
            cal.timeInMillis
        }catch (_:Exception){return false}
        return (epoch-submissionStartedAt) in -120000L..1800000L
    }

    private fun inspectVerificationReceipt(){
        if(!walletCheckActive || walletCheckComplete || receiptCheckReference.isNullOrBlank() ||
            !::receiptVerifier.isInitialized || isFinishing)return
        val uri=Uri.parse(receiptVerifier.url ?: "")
        if(uri.scheme!="https" || !trusted(uri.host) || uri.path?.startsWith("/invoice/")!=true){
            scheduleWalletRetry();return
        }
        val js="""(function(){
            const text=document.body.innerText||'';
            const ref=(text.match(/receipt\s*(?:no\.?|number)\s*:?\s*([A-Z0-9]{8,45})/i)||[])[1]||'';
            const total=(text.match(/(?:total amount|amount paid)\s*(?:MVR|Rf|ރ|ރ\.)?\s*([\d,]+(?:\.\d{1,2})?)/i)||[])[1]||'';
            const service=(text.match(/description\s+([\s\S]{0,120}?)(?:total amount|amount paid)/i)||[])[1]||'';
            const billTo=(text.match(/bill to\s+([A-Z0-9-]{5,32})/i)||[])[1]||'';
            const candidates=(service.match(/\b[A-Z0-9-]{7,30}\b/gi)||[]).filter(x=>/\d/.test(x));
            const recipient=candidates.find(x=>/^BB/i.test(x))||candidates[candidates.length-1]||billTo;
            return JSON.stringify({reference:ref,total:total,recipient:recipient,paid:/\bpaid in full\b/i.test(text)});
        })();"""
        receiptVerifier.evaluateJavascript(js){raw->
            if(!walletCheckActive || walletCheckComplete || isFinishing)return@evaluateJavascript
            try {
                val detail=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                val candidate=receiptCandidate ?: return@evaluateJavascript
                val reference=receiptCheckReference.orEmpty()
                val actual=detail.optString("reference")
                val input=pendingPaymentRecipient.orEmpty().filter(Char::isLetterOrDigit)
                    .removePrefix("960").uppercase(java.util.Locale.ROOT)
                val account=detail.optString("recipient").filter(Char::isLetterOrDigit)
                    .removePrefix("960").uppercase(java.util.Locale.ROOT)
                val expected=(pendingPaymentAmount ?: selectedPackagePrice)
                    ?.replace(",","")?.toBigDecimalOrNull()
                val paid=detail.optString("total").replace(",","").toBigDecimalOrNull()
                if(reference.equals(actual,true) && input.isNotBlank() && input==account &&
                    expected!=null && paid!=null && expected.compareTo(paid)==0){
                    walletCheckActive=false
                    walletCheckComplete=true
                    observingProviderResult=false
                    val status=candidate.optString("status")
                    state.text="Faseyha Wallet verification: $status. "+
                        "Service: "+serviceChoices[selectedServiceChoice]+
                        ". Recipient: "+pendingPaymentRecipient.orEmpty()+
                        ". MVR "+expected+". Reference: "+reference+
                        ". This confirms the provider record, not network delivery."
                }else {
                    walletCheckActive=false
                    walletCheckComplete=true
                    state.text="Wallet has a recent matching charge, but the recipient or receipt could not be verified. "+
                        "Status uncertain for this recipient; do not retry before reviewing history."
                }
            }catch (_:Exception){scheduleWalletRetry()}
        }
    }

    private fun scheduleWalletRetry(){
        if(!walletCheckActive || walletCheckComplete || isFinishing)return
        receiptCheckReference=null
        receiptCandidate=null
        walletCheckAttempts++
        if(walletCheckAttempts>=7 || System.currentTimeMillis()-walletCheckStartedAt>90000L){
            walletCheckActive=false
            walletCheckComplete=true
            state.text="No matching recent Faseyha Wallet receipt could be verified. Status unknown. "+
                "Review Wallet before making another payment."
        }else {
            state.text="Checking transaction history. No verified result yet; do not retry."
            walletVerifier.postDelayed({
                if(walletCheckActive && !isFinishing)
                    walletVerifier.loadUrl("https://faseyha.net/wallet")
            },6500)
        }
    }

    private fun openWalletVerification() {
        val verify = android.content.Intent(this, FaseyhaWalletActivity::class.java)
        verify.putExtra("verify_service", services[serviceIndex])
        verify.putExtra("verify_operator", if(serviceIndex==2) billOperators[billOperatorIndex] else operators[operatorIndex])
        verify.putExtra("verify_recipient", pendingPaymentRecipient.orEmpty())
        verify.putExtra("verify_amount", pendingPaymentAmount.orEmpty())
        verify.putExtra("verify_started_at", submissionStartedAt)
        verify.putExtra("verify_package", selectedPackageLabel.orEmpty())
        startActivity(verify)
    }

    // A separate native confirmation is required for Faseyha's own modal.
    // The final provider modal must be present, visible, uniquely actionable and
    // contain the originally reviewed recipient, operator and amount.
    // The user authorized this exact transaction once on our native review.
    // Provider's later confirmation is accepted only when all details still match.
    private fun showSecondStageConfirmation(providerText:String, buttonLabel:String) {
        val target=pendingPaymentRecipient ?: return
        val action=pendingPaymentAction ?: return
        val money=pendingPaymentAmount
        if(!submissionAttempted || approvedSummary==null ||
            !isTrusted() || Uri.parse(web.url ?: "").path!=selectedPath()) {
            state.text="Provider confirmation cannot be safely matched. Review Wallet; do not retry."
            return
        }
        val normalized=providerText.replace(Regex("\\s+")," ")
        if(!normalized.replace(Regex("[^0-9]"),"").contains(target.filter(Char::isDigit))) {
            state.text="Provider recipient changed. Confirmation stopped; check Wallet."
            return
        }
        val op=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
        if(!normalized.contains(op,ignoreCase=true)) {
            state.text="Provider operator changed. Confirmation stopped; check Wallet."
            return
        }
        val totalMatch=Regex("total\\s+to\\s+pay\\s*(?:MVR|Rf|ރ)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            RegexOption.IGNORE_CASE).find(normalized)
        val total=totalMatch?.groupValues?.get(1)?.replace(",","")?.toBigDecimalOrNull()
        val approvedTotal=if(money!=null) money.toBigDecimalOrNull()
            else selectedPackagePrice?.replace(",","")?.toBigDecimalOrNull()
        // Never silently accept an unknown price or a modified provider total.
        if(total==null || approvedTotal==null || total.compareTo(approvedTotal)!=0) {
            state.text="Provider final total differs or is unavailable. Nothing confirmed. Review website and Wallet."
            return
        }
        state.text="Provider confirmation matches your authorization. Submitting once, then verifying."
        pressSecondStageConfirmation(target,money,buttonLabel)
    }

    private fun pressSecondStageConfirmation(target:String,money:String?,expectedLabel:String) {
        val action=pendingPaymentAction ?: return
        if(!submissionAttempted || !isTrusted() || Uri.parse(web.url ?: "").path!=selectedPath()
            || number.text.toString().trim()!=target ||
            (money!=null && amount.text.toString().trim().toBigDecimalOrNull()!=money.toBigDecimalOrNull())) {
            message("Request changed. Final confirmation cancelled; check Wallet.");return
        }
        val op=if(serviceIndex==2)billOperators[billOperatorIndex] else operators[operatorIndex]
        val js="""(function(target,operatorName,action,expectedLabel){
           if(location.protocol!=='https:'||location.hostname!=='faseyha.net'||location.pathname!==__PATH__)
               return JSON.stringify({ok:false,reason:'Provider page changed'});
           const tidy=x=>(x||'').replace(/\s+/g,' ').trim();
           const shown=x=>x.getClientRects().length>0 && getComputedStyle(x).visibility!=='hidden';
           const heading=action==='Recharge'?/^confirm recharge$/i:
                action==='Pay'?/^confirm (?:bill )?pay(?:ment)?$/i:/^confirm (?:package|activation)$/i;
           // The provider's final modal may not have semantic heading tags.
           // Select only one visible, enabled exact confirmation control.
           const buttons=Array.from(document.querySelectorAll('button,[role="button"],input[type="submit"]')).filter(shown);
           const label=b=>tidy(b.innerText||b.value||b.getAttribute('aria-label')||b.textContent);
           const positives=buttons.filter(b=>label(b)===expectedLabel&&!b.disabled
              &&b.getAttribute('aria-disabled')!=='true'&&getComputedStyle(b).pointerEvents!=='none');
           let found=[];
           if(positives.length===1){
              let p=positives[0].parentElement;
              for(let depth=0;p&&depth<9;depth++,p=p.parentElement){
                  const summary=tidy(p.innerText||'');
                  if(summary.length>1200)break;
                  const siblings=Array.from(p.querySelectorAll('button,[role="button"],input[type="submit"]')).filter(shown);
                  const totalMatch=summary.match(/total\s+to\s+pay\s*(?:MVR|Rf|ރ)?\s*([0-9,]+(?:\.[0-9]{1,2})?)/i);
                  const providerTotal=totalMatch?Number(totalMatch[1].replace(/,/g,'')):NaN;
                  if(siblings.some(b=>/^(cancel|back|close|no)$/i.test(label(b)))
                     &&summary.replace(/[^0-9]/g,'').includes(target.replace(/[^0-9]/g,''))
                     &&summary.toLowerCase().includes(operatorName.toLowerCase())
                     &&(__MONEY__===''||(Number.isFinite(providerTotal)&&providerTotal===Number(__MONEY__)))
                     &&/confirm\s+(?:recharge|payment|bill|activation)/i.test(summary)){
                     found=[positives[0]];break;
                  }
              }
           }
           found=[...new Set(found)];
           if(found.length!==1)return JSON.stringify({ok:false,reason:'Provider confirmation missing, changed or ambiguous. Review the website; do not retry blindly.'});
           window.__haluviBeforeSubmit=(document.body.innerText||'').split(/\n/).map(x=>x.trim()).filter(Boolean);
           try{sessionStorage.setItem('haluvi_before_submit',JSON.stringify(window.__haluviBeforeSubmit));}catch(e){}
           found[0].click();
           return JSON.stringify({ok:true});
        })(__TARGET__,__OP__,__ACTION__,__LABEL__);"""
            .replace("__PATH__",JSONObject.quote(selectedPath()))
            .replace("__TARGET__",JSONObject.quote(target))
            .replace("__OP__",JSONObject.quote(op))
            .replace("__ACTION__",JSONObject.quote(action))
            .replace("__MONEY__",JSONObject.quote(money ?: selectedPackagePrice ?: ""))
            .replace("__LABEL__",JSONObject.quote(expectedLabel))
        web.evaluateJavascript(js){raw->
            try{
                val obj=JSONObject(org.json.JSONArray("[$raw]").getString(0))
                if(obj.optBoolean("ok")){
                    observingProviderResult=true
                    providerResultNoticeShown=false
                    state.text="Final provider confirmation pressed once. Checking actual Faseyha response. Do not retry."
                    web.postDelayed({checkProviderResult(0)},750)
                } else {
                    state.text=obj.optString("reason")
                    message(state.text.toString())
                }
            }catch(_:Exception){message("Final confirmation outcome uncertain. Check Faseyha Wallet before retrying.")}
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
                        if (pendingPreparation && !showDialog) {
                            pendingPreparation = false
                            if (result.optBoolean("ok")) {
                                state.text = "Operator selected. Preparing provider form…"
                                web.postDelayed({ if(isTrusted() && Uri.parse(web.url ?: "").path==selectedPath()) prepareWhenReady(0) },1100)
                            } else message("Operator selection was not confirmed. No form or payment submitted. Select the operator on the website and tap Continue again.")
                        } else if (showDialog || !result.optBoolean("ok")) message(state.text.toString())
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
        walletCheckActive=false
        if (::walletVerifier.isInitialized) {
            (walletVerifier.parent as? ViewGroup)?.removeView(walletVerifier)
            walletVerifier.destroy()
        }
        if (::receiptVerifier.isInitialized) {
            (receiptVerifier.parent as? ViewGroup)?.removeView(receiptVerifier)
            receiptVerifier.destroy()
        }
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
