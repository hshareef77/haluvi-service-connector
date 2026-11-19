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
        add("Refresh Faseyha Wallet"){records.clear();list.removeAllViews();web.loadUrl("https://faseyha.net/wallet")}
        add("Retrieve displayed transactions"){scan()}
        add("Load older transactions"){loadMore()}
        add("Maximum records"){chooseLimit()}
        add("Open dashboard"){web.loadUrl("https://faseyha.net/dashboard")}
        add("Open Wallet"){web.loadUrl("https://faseyha.net/wallet")}
        add("Close Wallet"){finish()}
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
                    else state.text="Provider page opened: "+(Uri.parse(url).path?:"/")
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
                records.clear();list.removeAllViews()
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
          const body=document.body.innerText||'';
          const amount=(body.match(/available\s+balance[\s\S]{0,70}?(\d[\d,.]*)(?:\s|$)/i)||[])[1]||'unknown';
          const links=Array.from(document.querySelectorAll('a[href]'))
            .filter(a=>/^(receipt|tax invoice)$/i.test((a.innerText||'').trim())||/\/invoice\//i.test(a.href));
          const rows=[];
          for(const a of links){
            let n=a;
            for(let i=0;i<6&&n;i++,n=n.parentElement){
              const text=(n.innerText||'').replace(/\s+/g,' ').trim();
              if(text.length<22||text.length>550)continue;
              if(!/receipt|tax invoice|successful|completed|processing|failed/i.test(text))continue;
              const reference=(text.match(/\b(?:FR|BLA)[A-Z0-9]{8,40}\b/i)||[])[0]||'';
              const status=(text.match(/\b(successful|completed|processing|pending|failed|submitted)\b/i)||[])[1]||'Not shown';
              const url=a.href;
              if(!rows.some(r=>r.url===url))rows.push({reference:reference,status:status,description:text.slice(0,400),url:url});
              break;
            }
          }
          const more=Array.from(document.querySelectorAll('button,[role="button"]'))
             .some(x=>x.getClientRects().length&&/^load more$/i.test((x.innerText||x.getAttribute('aria-label')||'').trim()));
          return JSON.stringify({balance:amount,rows:rows.slice(0,500),more:more});
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
                val desc=row.optString("description").take(200)
                val kind=if(category=="Payments")"Wallet deposit"
                    else if(desc.contains("wholesale",true))"Wholesale, not a customer recharge"
                    else "Customer transaction candidate"
                list.addView(Button(this).apply{
                    minHeight=buttonHeight
                    text="$kind. $desc. View details"
                    setOnClickListener{detail(row,kind)}
                })
            }
        }
    }
    private fun detail(row:JSONObject,kind:String){
        val u=Uri.parse(row.optString("url"))
        AlertDialog.Builder(this).setTitle(kind)
            .setMessage(row.optString("description")+"\n\nProvider status: "+row.optString("status")+
                "\nReference: "+row.optString("reference")+
                "\nWebsite evidence only. Final delivery and order matching have not been verified.")
            .setPositiveButton("Open provider receipt"){_,_->
                if(u.scheme=="https"&&trusted(u.host))web.loadUrl(u.toString())
                else message("No safe same-site receipt URL was found.")
            }.setNegativeButton("Close",null).show()
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
