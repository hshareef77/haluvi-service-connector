package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Read-only, best-effort rendering of provider wallet history. No API credentials captured. */
class FaseyhaWalletActivity : Activity() {
    private lateinit var web:WebView
    private lateinit var state:TextView
    private lateinit var list:LinearLayout
    private lateinit var selector:Button
    private var category="Recharges"
    private var limit=50
    private var busy=false
    private var autoLoads=0
    private var previousAutoCount=-1
    private var receiptReference: String? = null
    private var receiptReturnToWallet = false
    private val receiptCache = mutableMapOf<String,JSONObject>()
    private lateinit var advanced: LinearLayout
    private val records=linkedMapOf<String,JSONObject>()
    private val buttonHeight:Int get()=(56*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12)}
        root.addView(TextView(this).apply{text="Faseyha Wallet and Transactions";textSize=23f
            if(android.os.Build.VERSION.SDK_INT>=28)isAccessibilityHeading=true})
        root.addView(TextView(this).apply{text="Read-only provider history. Recharges includes customer and wholesale purchases. Payments contains deposits. Never use a history entry alone to fulfill an HaluviPay order."})
        state=TextView(this).apply{text="Opening Wallet...";textSize=17f;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        root.addView(state)
        fun add(label:String,fn:()->Unit)=root.addView(Button(this).apply{text=label;minHeight=buttonHeight;setOnClickListener{fn()}})
        selector=Button(this).apply{text="Category: Recharges. Change";minHeight=buttonHeight;setOnClickListener{chooseCategory()}}
        root.addView(selector)
        add("Refresh transactions"){autoLoads=0;previousAutoCount=-1;records.clear();list.removeAllViews();web.loadUrl("https://faseyha.net/wallet")}
        add("Load older transactions"){loadMore()}
        add("Close Wallet"){finish()}
        advanced=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        root.addView(Button(this).apply{text="Advanced wallet controls";minHeight=buttonHeight;setOnClickListener{
            advanced.visibility=if(advanced.visibility==View.GONE)View.VISIBLE else View.GONE
            text=if(advanced.visibility==View.GONE)"Advanced wallet controls" else "Hide advanced wallet controls"
        }})
        root.addView(advanced)
        fun diagnostic(label:String,fn:()->Unit)=advanced.addView(Button(this).apply{text=label;minHeight=buttonHeight;setOnClickListener{fn()}})
        diagnostic("Retrieve displayed transactions"){scan()}
        diagnostic("Maximum records"){chooseLimit()}
        diagnostic("Open dashboard"){web.loadUrl("https://faseyha.net/dashboard")}
        diagnostic("Open wallet website"){web.loadUrl("https://faseyha.net/wallet")}
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(list)
        root.addView(TextView(this).apply{text="Provider website, for sign-in and original receipt viewing:"})
        web=WebView(this).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            webChromeClient=WebChromeClient()
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                    val u=request.url
                    if(u.scheme!="https" || !trusted(u.host)){state.text="Unsupported website navigation blocked.";return true}
                    return false
                }
                override fun onPageFinished(view:WebView,url:String){
                    CookieManager.getInstance().flush()
                    if(wallet())web.postDelayed({if(wallet())selectCategory{scan()}},350)
                    else if(Uri.parse(url).path?.startsWith("/invoice/")==true) {
                        web.postDelayed({inspectReceipt()},300)
                    } else state.text="Provider page opened: "+(Uri.parse(url).path?:"/")
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        root.addView(web,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(330*resources.displayMetrics.density).toInt()))
        setContentView(ScrollView(this).apply{addView(root)})
        web.loadUrl("https://faseyha.net/wallet")
    }
    private fun trusted(h:String?)=h=="faseyha.net" || h?.endsWith(".faseyha.net")==true
    private fun wallet():Boolean{
        val u=Uri.parse(web.url?:"")
        return u.scheme=="https" && trusted(u.host) && u.path=="/wallet"
    }
    private fun message(text:String){AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK",null).show()}
    private fun decode(raw:String):JSONObject?=try{JSONObject(JSONArray("[$raw]").getString(0))}catch(_:Exception){null}
    private fun chooseCategory(){
        val options=arrayOf("Recharges","Payments")
        AlertDialog.Builder(this).setTitle("Wallet category, currently "+category)
            .setSingleChoiceItems(options,if(category=="Recharges")0 else 1){dialog,i->
                dialog.dismiss()
                category=options[i]
                selector.text="Category: "+category+". Change"
                selector.contentDescription="Wallet category selected: "+category+". Double tap to change"
                autoLoads=0;previousAutoCount=-1;records.clear();list.removeAllViews()
                if(wallet())selectCategory{scan()} else web.loadUrl("https://faseyha.net/wallet")
            }.setNegativeButton("Cancel",null).show()
    }
    private fun selectCategory(after:()->Unit){
        if(!wallet()){state.text="Open Wallet before selecting a category.";return}
        val js="""(function(wanted){
          const tidy=x=>(x||'').replace(/\s+/g,' ').trim().toLowerCase();
          const matches=Array.from(document.querySelectorAll('button,[role="tab"],[role="button"]'))
            .filter(x=>x.getClientRects().length && !x.disabled)
            .filter(x=>tidy(x.innerText||x.getAttribute('aria-label'))===tidy(wanted));
          if(matches.length!==1)return JSON.stringify({ok:false});
          matches[0].click();return JSON.stringify({ok:true});
        })(__CATEGORY__);""".replace("__CATEGORY__",JSONObject.quote(category))
        web.evaluateJavascript(js){raw->
            if(decode(raw)?.optBoolean("ok")==true)web.postDelayed({if(wallet())after()},350)
            else state.text="Couldn't select "+category+" automatically. Choose it on Faseyha's website and select Retrieve displayed transactions."
        }
    }
    private fun chooseLimit(){
        val values=arrayOf(20,50,100,200,500)
        AlertDialog.Builder(this).setTitle("Maximum transaction records")
            .setSingleChoiceItems(values.map{"Latest "+it}.toTypedArray(),values.indexOf(limit)){d,i->
                d.dismiss();limit=values[i];state.text="Limit: "+limit+". Refresh or Load older transactions to continue."
            }.setNegativeButton("Cancel",null).show()
    }
    private fun scan(){
        if(!wallet()){state.text="Open Faseyha Wallet before retrieving transactions.";return}
        val js="""(function(){
          const pageText=document.body.innerText||'';
          const balance=(pageText.match(/available\s+balance[\s\S]{0,90}?(\d[\d,.]*)(?:\s|$)/i)||[])[1]||'unknown';
          const seen=new Set(),rows=[];
          const trim=s=>(s||'').replace(/\s+/g,' ').trim();
          const moneyPattern=/(?:MVR|Rf|ރ|ރ\.|MRf)?\s*([+-]?\s*\d[\d,]*(?:\.\d{1,2})?)/i;
          const references=/\b(?:FR|BLA)[A-Z0-9]{8,40}\b/i;
          const statusPattern=/\b(successful|completed|processing|pending|failed|submitted)\b/i;
          const dates=/\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}(?:,\s*|\s+)\d{1,2}:\d{2}\s*(?:AM|PM)\b/i;
          // Find a single transaction container containing receipt, reference and status,
          // rather than reading the receipt link's parent only.
          const links=Array.from(document.querySelectorAll('a[href]'))
            .filter(x=>/receipt|tax invoice/i.test(trim(x.innerText||x.getAttribute('aria-label'))) || /\/invoice\//i.test(x.href));
          for(const a of links){
            let item=null,ancestor=a;
            for(let depth=0;ancestor&&depth<10;depth++,ancestor=ancestor.parentElement){
              const text=trim(ancestor.innerText);
              if(text.length<20||text.length>900)continue;
              if(references.test(text)&&statusPattern.test(text)&&dates.test(text)){item=ancestor;break;}
            }
            if(!item)continue;
            const text=trim(item.innerText);
            const reference=(text.match(references)||[])[0]||'';
            if(!reference||seen.has(reference))continue;
            seen.add(reference);
            const status=(text.match(statusPattern)||[])[1]||'Not shown';
            const date=(text.match(dates)||[])[0]||'';
            const first=text.indexOf(reference);
            const service=trim(first>=0?text.slice(0,first):'').slice(0,100)||'Unknown service';
            // Amount is often in a separate right-hand column or a sibling element.
            // Extract from leaf nodes only (avoid dates and reference digits).
            const leaves=Array.from(item.querySelectorAll('*')).filter(x=>x.getClientRects().length && x.children.length===0)
              .map(x=>trim(x.textContent)).filter(x=>x.length && x.length<45);
            const values=leaves.filter(x=>/^[+−-]?\s*(?:MVR|Rf|ރ|ރ\.)?\s*[\d,]+(?:\.\d{1,2})?$/.test(x))
              .filter(x=>/[+−-]|(?:MVR|Rf|ރ)/i.test(x) || /\d{1,3},\d{3}/.test(x));
            let price='';
            const currencyValues=values.map(x=>x.replace(/[^\d.,]/g,'')).filter(Boolean);
            if(currencyValues.length===1)price=currencyValues[0];
            // If no leaf amount is explicitly marked, inspect short text immediately
            // before the provider status badge, but never invent an amount.
            if(!price){
              const around=text.match(/(?:MVR|Rf|ރ|ރ\.)\s*([+-]?\s*[\d,]+(?:\.\d{1,2})?)/i);
              if(around)price=around[1].trim().replace(/[+−]/g,'');
            }
            if(!price){
              const statusIdx=text.search(statusPattern);
              const preceding=statusIdx>=0?text.slice(Math.max(0,statusIdx-36),statusIdx):'';
              const num=preceding.match(/(?:^|\s)([+-]?\s*\d[\d,]*(?:\.\d{1,2})?)\s*$/);
              if(num && /\b\d{1,2}:\d{2}/.test(num[1])===false)price=num[1].trim().replace(/^[+−-]\s*/,'');
            }
            let url='';
            try{const u=new URL(a.href);if(u.protocol==='https:'&&(u.hostname==='faseyha.net'||u.hostname.endsWith('.faseyha.net')))url=u.href}catch(e){}
            rows.push({reference:reference,status:status,service:service,date:date,price:price,
                description:text.slice(0,550),url:url});
          }
          const more=Array.from(document.querySelectorAll('button,[role="button"]'))
            .some(x=>x.getClientRects().length && /^load more$/i.test(trim(x.innerText||x.getAttribute('aria-label'))));
          return JSON.stringify({balance:balance,rows:rows.slice(0,500),more:more});
        })();"""
        web.evaluateJavascript(js){raw->
            val obj=decode(raw)
            if(obj==null){state.text="Unable to parse Faseyha's history. Review the provider website.";return@evaluateJavascript}
            val rows=obj.optJSONArray("rows")?:JSONArray()
            for(i in 0 until rows.length()){
                val row=rows.optJSONObject(i)?:continue
                val key=row.optString("reference").ifBlank{row.optString("url")}
                if(key.isNotBlank()&&records.size<limit)records[key]=row
            }
            state.text="Wallet category: "+category+". Balance displayed: MVR "+
                obj.optString("balance","unknown")+". "+records.size+" entries. "+
                (if(obj.optBoolean("more"))"Load More available."else"No Load More button detected.")
            list.removeAllViews()
            if(records.isEmpty())list.addView(TextView(this).apply{
                text="No transactions recognized. Select the category on Faseyha's website and try again."
            })
            records.values.forEach{row->
                val service=row.optString("service","Unknown service")
                val wholesale=service.contains("wholesale",true)
                val kind=if(category=="Payments")"Wallet deposit"
                    else if(wholesale)"Wholesale "
                    else ""
                val date=row.optString("date","Date not identified")
                val price=row.optString("price").ifBlank{receiptCache[row.optString("reference")]?.optString("amount")?.takeIf{it.isNotBlank()} ?: "amount unavailable"}
                val status=row.optString("status","Not shown")
                val details=receiptCache[row.optString("reference")]
                val account=details?.optString("account")?.takeIf{it.isNotBlank()} ?: ""
                val summary="$kind$service. " + (if(account.isNotBlank())"$account. " else "") + "MVR $price. $status. $date."
                list.addView(Button(this).apply{
                    minHeight=buttonHeight
                    text="$summary View details"
                    contentDescription="$summary View details and transaction reference."
                    setOnClickListener{detail(row,kind)}
                })
            }
            if(obj.optBoolean("more") && records.size<limit && autoLoads<6 && records.size>previousAutoCount) {
                previousAutoCount=records.size
                autoLoads++
                web.postDelayed({ if(wallet() && !busy && records.size<limit) loadMore() },700)
            }
        }
    }
    private fun detail(row:JSONObject,kind:String){
        val u=Uri.parse(row.optString("url"))
        AlertDialog.Builder(this).setTitle(kind)
            .setMessage("Service: "+row.optString("service")+"\nAmount: MVR "+row.optString("price").ifBlank{"not identified"}+"\nDate: "+row.optString("date")+"\n\nProvider status: "+row.optString("status")+
                "\nTransaction reference: "+row.optString("reference")+
                "\nWebsite evidence only. Final delivery and order matching have not been verified.")
            .setPositiveButton("Open provider receipt"){_,_->
                if(u.scheme=="https"&&trusted(u.host)) {
                    receiptReference=row.optString("reference")
                    web.loadUrl(u.toString())
                }
                else message("No safe same-site receipt URL was found.")
            }.setNegativeButton("Close",null).show()
    }
    private fun inspectReceipt() {
        val uri=Uri.parse(web.url?:"")
        if(uri.scheme!="https" || !trusted(uri.host) || uri.path?.startsWith("/invoice/")!=true)return
        val ref=receiptReference
        val js="""(function(){
          const text=(document.body.innerText||'');
          const norm=s=>(s||'').replace(/\s+/g,' ').trim();
          const receipt=(text.match(/receipt\s*(?:no\.?|number)\s*:?\s*([A-Z0-9]{8,45})/i)||[])[1]||'';
          const paid=/\bpaid in full\b/i.test(text)?'Paid in full':'Not identified';
          const total=text.match(/(?:total amount|amount paid)\s*(?:MVR|Rf|ރ|ރ\.)?\s*([\d,]+(?:\.\d{1,2})?)/i);
          const service=(text.match(/description\s+([\s\S]{0,120}?)(?:total amount|amount paid)/i)||[])[1]||'';
          const billTo=(text.match(/bill to\s+([A-Z0-9-]{5,32})/i)||[])[1]||'';
          const candidates=(service.match(/\b[A-Z0-9-]{7,30}\b/gi)||[]).filter(x=>/\d/.test(x));
          const account=candidates.find(x=>/^BB/i.test(x))||candidates[candidates.length-1]||billTo;
          return JSON.stringify({reference:receipt,account:account,amount:total?total[1]:'',status:paid});
        })();"""
        web.evaluateJavascript(js){raw->
            val parsed=decode(raw)
            if(parsed==null){state.text="Could not inspect original Faseyha receipt.";return@evaluateJavascript}
            val reported=parsed.optString("reference")
            if(ref!=null && reported.isNotBlank() && !ref.equals(reported,true)){
                state.text="Receipt reference does not match selected history transaction. No data was merged."
                return@evaluateJavascript
            }
            if(ref!=null){
                receiptCache[ref]=parsed
                state.text="Receipt details retrieved for the selected transaction. Return to Wallet to view updated details."
                message("Receipt details from provider:\\nReference: "+ref+
                    "\\nRecipient/account: "+parsed.optString("account").ifBlank{"Not identified"}+
                    "\\nAmount: MVR "+parsed.optString("amount").ifBlank{"Not identified"}+
                    "\\nStatus: "+parsed.optString("status")+
                    "\\n\\nPaid in Full confirms payment to provider, not independently mobile recharge delivery.")
            }
        }
    }

    private fun loadMore(){
        if(!wallet()){state.text="Open Wallet before loading older entries.";return}
        if(busy){state.text="Already loading.";return}
        if(records.size>=limit){state.text="Reached maximum of "+limit+" records.";return}
        busy=true
        val js="""(function(){
          const matching=Array.from(document.querySelectorAll('button,[role="button"]'))
            .filter(x=>x.getClientRects().length&&!x.disabled)
            .filter(x=>/^load more$/i.test((x.innerText||x.getAttribute('aria-label')||'').trim()));
          if(matching.length!==1)return JSON.stringify({ok:false});
          matching[0].click();return JSON.stringify({ok:true});
        })();"""
        web.evaluateJavascript(js){raw->
            if(decode(raw)?.optBoolean("ok")!=true){
                busy=false;state.text="Load More wasn't available. Existing entries remain."
            }else web.postDelayed({busy=false;if(wallet())scan()},900)
        }
    }
    override fun onDestroy(){
        (web.parent as? ViewGroup)?.removeView(web);web.destroy();super.onDestroy()
    }
}
