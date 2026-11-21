package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Inspects any HTTPS page with the app's saved sign-ins: lists its headings, tabs,
 * buttons, fields, links, repeated lists, media and dialogs in short native lines.
 * Buttons, tabs and links can be pressed on the page (after confirmation), then the
 * page is inspected again. Field values and passwords are never read.
 */
class PageInspectorActivity : Activity() {
    companion object {
        /** Labels every actionable element with an id the app can press later. */
        val INSPECT = FaseyhaSite.script("""
const all=s=>Array.from(document.querySelectorAll(s));
const cut=(t,n)=>{t=H.tidy(t);return t.length>(n||80)?t.slice(0,(n||80)-1)+'…':t;};
let next=0;const tag=x=>{if(!x.dataset.haluviId)x.dataset.haluviId='h'+(next++)+'_'+Date.now()%100000;return x.dataset.haluviId;};
const flags=x=>(x.disabled||x.getAttribute('aria-disabled')==='true'?' [disabled]':'')+(H.isActive(x)?' [selected]':'');
const name=x=>{const a=x.getAttribute('aria-label')||x.getAttribute('title')||'',t=H.opLabel(x);return (!t||!/[\p{L}\p{N}]/u.test(t))&&a?a:(t||a);};
const item=(kind,x,text)=>({kind:kind,id:tag(x),text:cut(text)+flags(x)});
const out={title:document.title.slice(0,80),url:location.href.split('#')[0].slice(0,160),signedOut:H.signedOut(),sections:{}};
const add=(k,v)=>{(out.sections[k]=out.sections[k]||[]).push(v);};
H.all('h1,h2,h3,h4,[role="heading"]').slice(0,30).forEach(x=>add('Headings',{kind:'text',text:cut(x.innerText)}));
H.all('[role="tab"]').slice(0,30).forEach(x=>add('Tabs',item('press',x,name(x)||'(no label)')));
const tabs=new Set(H.all('[role="tab"]'));
H.buttons().filter(x=>!tabs.has(x)).slice(0,120).forEach(x=>add('Buttons',item('press',x,name(x)||'(icon button, no label)')));
H.all('textarea,[contenteditable="true"],input,select').filter(x=>!['hidden','password'].includes((x.type||'').toLowerCase())).slice(0,40)
  .forEach(x=>add('Text boxes and fields',{kind:'text',text:cut((x.tagName==='SELECT'?'choice':x.tagName==='INPUT'?(x.type||'text'):'text box')+': '+(H.fieldLabel(x)||x.getAttribute('placeholder')||'(no label)')+
    (x.maxLength>0?' (max '+x.maxLength+')':'')+(x.tagName==='SELECT'?' = '+H.tidy((x.selectedOptions[0]||{}).text||''):''))}));
H.all('a[href]').filter(x=>!x.closest('button')).slice(0,80).forEach(x=>{let p='';try{const u=new URL(x.href);p=(u.host===location.host?'':u.host)+u.pathname;}catch(e){}
  add('Links',item('press',x,(name(x)||'(no label)')+' → '+p));});
// Repeated lists: containers with 3+ children that share the same tag and class.
const groups=[];
for(const c of all('ul,ol,div,section,tbody,[role="list"],[role="listbox"]')){
  if(!H.visible(c)||c.children.length<3)continue;
  const sig=x=>x.tagName+'.'+String(x.className||'').split(/\s+/).slice(0,3).join('.');
  const kids=Array.from(c.children).filter(H.visible);const s=kids.length?sig(kids[0]):'';
  const same=kids.filter(k=>sig(k)===s);
  if(same.length>=3&&same.length>=kids.length*0.7&&same.every(k=>H.tidy(k.innerText).length>0))groups.push({c:c,items:same});
}
const top=groups.filter(g=>!groups.some(o=>o!==g&&g.c.contains(o.c)&&o.items.length>=g.items.length)).sort((a,b)=>b.items.length-a.items.length).slice(0,5);
top.forEach((g,i)=>{add('Lists',{kind:'text',text:'List '+(i+1)+': '+g.items.length+' items'});
  g.items.slice(0,40).forEach(k=>add('Lists',item('press',k,'  '+H.tidy(k.innerText).split('\n')[0])));});
H.all('audio,video').forEach(x=>add('Media',{kind:'text',text:x.tagName.toLowerCase()+': source '+((x.currentSrc||x.src||'').split(':')[0]||'none')+
  (Number.isFinite(x.duration)?', '+Math.round(x.duration)+' seconds':'')+(x.paused?', paused':', playing')}));
H.all('[role="dialog"],[aria-modal="true"],[role="alertdialog"]').forEach(x=>add('Dialogs',{kind:'text',text:cut(x.innerText,120)}));
H.all('[role="alert"],[role="status"]').map(x=>cut(x.innerText,120)).filter(Boolean).slice(0,5).forEach(t=>add('Messages',{kind:'text',text:t}));
H.body().split(/\n/).map(H.tidy).filter(x=>x&&x.length<80&&(/\b(characters?|credits?|balance)\b/i.test(x)||/^[\d,]+\s*\/\s*[\d,]+$/.test(x))).slice(0,6)
  .forEach(t=>add('Counters',{kind:'text',text:t}));
return JSON.stringify(out);""")

        fun press(id: String) = FaseyhaSite.script("""
const x=document.querySelector('[data-haluvi-id="'+CSS.escape(id)+'"]');
if(!x)return JSON.stringify({ok:false,reason:'That element is no longer on the page. Inspect again.'});
x.scrollIntoView({block:'center'});
const o={bubbles:true,cancelable:true,button:0,pointerType:'touch',isPrimary:true};
x.dispatchEvent(new PointerEvent('pointerdown',o));x.dispatchEvent(new MouseEvent('mousedown',o));if(x.focus)x.focus();
x.dispatchEvent(new PointerEvent('pointerup',o));x.dispatchEvent(new MouseEvent('mouseup',o));x.click();
return JSON.stringify({ok:true});""", "id" to id)

        private val ORDER = listOf("Headings", "Tabs", "Buttons", "Text boxes and fields", "Lists", "Links", "Media", "Dialogs", "Messages", "Counters")
    }

    private lateinit var screen: EngineScreen
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private var page: FaseyhaEngine? = null
    private var lastReport: JSONObject? = null
    private var inspectToken = 0
    private val prefs by lazy { getSharedPreferences("page_inspector", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EngineScreen(this, "Inspect a web page")
        screen.text("Enter an HTTPS address. The page opens with your saved sign-ins, and its parts are listed below. Tap a button, tab, link or list item to press it on the page.")
        address = EditText(this).apply {
            hint = "Web address, for example https://thaana.ai/text-to-speech"
            contentDescription = "Web address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(intent.getStringExtra("url") ?: prefs.getString("last", "https://"))
        }
        screen.panel.addView(address)
        screen.buttonRow(screen.panel, "Inspect" to { load() }, "Inspect again" to { inspect(0) })
        screen.buttonRow(screen.panel, "Copy report" to { copyReport() }, "Show page" to { page?.let { screen.showWeb(it, "Page being inspected") } })
        status = screen.text("No page inspected yet.", 16f, live = true)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.panel.addView(results)
        setContentView(screen.root)
    }

    private fun load() {
        val url = address.text.toString().trim()
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) { address.error = "Enter a full https:// address"; return }
        prefs.edit().putString("last", url).apply()
        page?.destroy()
        val engine = FaseyhaEngine(this, "https://" + uri.host) { inspect(0) }
        engine.web.settings.mediaPlaybackRequiresUserGesture = false
        page = engine
        screen.attach(engine)
        results.removeAllViews()
        status.text = "Loading ${uri.host}${uri.path ?: ""}…"
        engine.openUrl(url)
    }

    /** Pages often finish drawing after loading, so inspect once now and again shortly after. */
    private fun inspect(attempt: Int) {
        val engine = page ?: run { status.text = "Enter an address and press Inspect first."; return }
        val token = ++inspectToken
        engine.after(if (attempt == 0) 1500 else 2000) {
            if (token != inspectToken) return@after
            engine.eval(INSPECT) { r ->
                if (token != inspectToken) return@eval
                if (r == null) { status.text = "The page could not be read."; return@eval }
                show(r)
                val count = (0 until ORDER.size).sumOf { r.optJSONObject("sections")?.optJSONArray(ORDER[it])?.length() ?: 0 }
                if (count < 3 && attempt < 3) inspect(attempt + 1) // still drawing
            }
        }
    }

    private fun show(r: JSONObject) {
        lastReport = r
        results.removeAllViews()
        val sections = r.optJSONObject("sections") ?: JSONObject()
        val summary = ORDER.mapNotNull { k -> sections.optJSONArray(k)?.length()?.takeIf { it > 0 }?.let { "$it ${k.lowercase()}" } }
        status.text = (if (r.optBoolean("signedOut")) "Signed out on this site. " else "") +
            r.optString("title").ifBlank { "Page" } + ": " + summary.joinToString(", ").ifBlank { "nothing found yet; press Inspect again" } + "."
        for (key in ORDER) {
            val list = sections.optJSONArray(key) ?: continue
            if (list.length() == 0) continue
            results.addView(TextView(this).apply {
                text = "$key (${list.length()})"; textSize = 19f; setPadding(0, screen.dp(12), 0, screen.dp(4))
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val text = item.optString("text")
                if (item.optString("kind") == "press") results.addView(Button(this).apply {
                    this.text = text; isAllCaps = false; minHeight = screen.dp(48); gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { confirmPress(item.optString("id"), text) }
                }) else results.addView(TextView(this).apply {
                    this.text = text; textSize = 16f; setPadding(screen.dp(4), screen.dp(4), 0, screen.dp(4)); isFocusable = true
                })
            }
        }
    }

    /** Pressing acts like tapping the real website, so it always asks first. */
    private fun confirmPress(id: String, label: String) {
        AlertDialog.Builder(this).setTitle("Press on page?")
            .setMessage("\"${label.trim()}\"\n\nThis acts like tapping it on the website. Payment, delete or send buttons really act.")
            .setPositiveButton("Press on page") { _, _ ->
                page?.eval(press(id)) { r ->
                    if (r?.optBoolean("ok") == true) { status.text = "Pressed. Inspecting again…"; inspect(0) }
                    else status.text = r?.optString("reason") ?: "It could not be pressed."
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun copyReport() {
        val r = lastReport ?: run { status.text = "Inspect a page first."; return }
        val text = buildString {
            append("Page inspector report\nApp version: ").append(appVersion()).append('\n')
            append("Address: ").append(r.optString("url")).append("\nTitle: ").append(r.optString("title"))
                .append("\nSigned out: ").append(r.optBoolean("signedOut")).append('\n')
            val sections = r.optJSONObject("sections") ?: JSONObject()
            for (key in ORDER) {
                val list = sections.optJSONArray(key) ?: continue
                if (list.length() == 0) continue
                append('\n').append(key).append(":\n")
                for (i in 0 until list.length()) append("- ").append(list.optJSONObject(i)?.optString("text")).append('\n')
            }
            append("\nField values and passwords are not included.")
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Page inspector report", text))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.isWebShown) screen.showNative() else super.onBackPressed()
    }

    override fun onDestroy() {
        page?.destroy()
        super.onDestroy()
    }
}
