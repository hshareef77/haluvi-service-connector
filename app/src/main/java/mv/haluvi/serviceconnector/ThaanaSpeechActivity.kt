package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

/** Thaana AI (thaana.ai) addresses and the scripts run inside its pages. */
object ThaanaSite {
    const val BASE = "https://thaana.ai"
    const val SPEECH_PATH = "/text-to-speech"

    /**
     * Structure of the current page for building native controls: text boxes, voice
     * and setting controls with their options and limits, buttons, file inputs and
     * audio players. Typed text and passwords are not included.
     */
    val PAGE_REPORT = FaseyhaSite.script("""
const all=s=>Array.from(document.querySelectorAll(s));
const lab=x=>H.tidy([H.fieldLabel(x),x.getAttribute('title')].filter(Boolean).join(' ')).slice(0,90);
const flags=x=>(H.visible(x)?'':' [hidden]')+(x.disabled?' [disabled]':'')+(H.isActive(x)?' [active]':'');
return JSON.stringify({path:location.pathname,title:document.title.slice(0,80),signedOut:H.signedOut(),
  headings:H.all('h1,h2,h3,h4,[role="heading"]').map(x=>H.tidy(x.innerText).slice(0,80)).filter(Boolean).slice(0,20),
  buttons:H.buttons().map(x=>(H.opLabel(x)||'(no label)').slice(0,60)+flags(x)).slice(0,80),
  textBoxes:all('textarea,[contenteditable="true"],[role="textbox"]').map(x=>(x.tagName.toLowerCase())+': '+lab(x)+
    (x.maxLength>0?' [max '+x.maxLength+']':'')+(x.value||x.innerText?' [filled]':' [empty]')+flags(x)).slice(0,10),
  inputs:all('input').filter(x=>!['hidden','password'].includes((x.type||'').toLowerCase())).map(x=>(x.type||'text')+': '+lab(x)+
    (x.type==='range'||x.type==='number'?' [min '+x.min+' max '+x.max+' step '+x.step+' value '+x.value+']':'')+
    (x.type==='file'?' [accept '+(x.accept||'any')+(x.multiple?' multiple':'')+']':'')+
    ((x.type==='checkbox'||x.type==='radio')?(x.checked?' [checked]':' [not checked]'):'')+flags(x)).slice(0,40),
  selects:all('select').map(x=>lab(x)+' = '+H.tidy((x.selectedOptions[0]||{}).text||'')+' | options: '+
    Array.from(x.options).map(o=>H.tidy(o.text)).slice(0,40).join(', ')+flags(x)).slice(0,10),
  widgets:all('[role="listbox"],[role="combobox"],[role="radiogroup"],[role="slider"],[role="option"],[role="switch"],[role="tab"],[role="menuitem"]')
    .map(x=>x.getAttribute('role')+': '+(H.opLabel(x)||lab(x)).slice(0,70)+
      (x.getAttribute('aria-valuenow')!=null?' [value '+x.getAttribute('aria-valuenow')+' of '+x.getAttribute('aria-valuemin')+'-'+x.getAttribute('aria-valuemax')+']':'')+
      (x.getAttribute('aria-expanded')!=null?' [expanded '+x.getAttribute('aria-expanded')+']':'')+flags(x)).slice(0,60),
  media:all('audio,video').map(x=>x.tagName.toLowerCase()+': source '+((x.currentSrc||x.src||'').split(':')[0]||'none')+
    ' duration '+(Number.isFinite(x.duration)?x.duration.toFixed(1):'unknown')+(x.controls?' [controls]':'')+(x.paused?' [paused]':' [playing]')),
  downloads:all('a[download],a[href*=".mp3"],a[href*=".wav"],a[href*=".ogg"],a[href^="blob:"]')
    .map(x=>(H.tidy(x.innerText)||x.getAttribute('aria-label')||'(no label)').slice(0,60)+' -> '+(x.getAttribute('href')||'').split(':')[0]+
      (x.hasAttribute('download')?' [download '+(x.getAttribute('download')||'')+']':'')).slice(0,10),
  progress:all('progress,[role="progressbar"],[aria-busy="true"]').map(x=>x.tagName.toLowerCase()+' '+(x.getAttribute('aria-valuenow')||x.value||'')).slice(0,5),
  alerts:H.all('[role="alert"],[role="status"],[aria-live]').map(x=>H.tidy(x.innerText).slice(0,160)).filter(Boolean).slice(0,5)});""")
}

fun Activity.openThaanaWebsite(path: String = ThaanaSite.SPEECH_PATH) {
    startActivity(Intent(this, WebsiteActivity::class.java)
        .putExtra(WebsiteActivity.EXTRA_URL, ThaanaSite.BASE + path)
        .putExtra(WebsiteActivity.EXTRA_TITLE, "Thaana AI website"))
}

/**
 * Native Thaana AI text to speech. This first version opens the signed-in page in the
 * background and reports its structure, so its controls can be built natively.
 */
class ThaanaSpeechActivity : Activity() {
    private lateinit var screen: EngineScreen
    private lateinit var page: FaseyhaEngine
    private lateinit var status: TextView
    private var signedOut = false
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EngineScreen(this, "Thaana AI text to speech")
        status = screen.text("Opening Thaana AI…", 17f, live = true)
        screen.text("Native text box, voice, settings, Generate, playback and download controls come in the next version. " +
            "Sign in, then use Copy page report and send it, so they can be built for this page.")
        screen.button("Copy page report") { copyReport() }
        screen.button("Show website page") { screen.showWeb(page, "Thaana AI text to speech page") }
        screen.button("Reload page") { status.text = "Reloading…"; page.open(ThaanaSite.SPEECH_PATH) }
        screen.button("Open Thaana AI website and sign in") { openThaanaWebsite() }
        page = FaseyhaEngine(this, ThaanaSite.BASE) { onLoaded(it) }
        screen.attach(page)
        setContentView(screen.root)
        page.open(ThaanaSite.SPEECH_PATH)
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (signedOut) { signedOut = false; status.text = "Reloading after sign-in…"; page.open(ThaanaSite.SPEECH_PATH) }
    }

    private fun onLoaded(url: String) {
        // Single-page sites render after the load event; check a moment later.
        page.after(1500) {
            page.eval(FaseyhaSite.SIGNED_OUT) { r ->
                if (r?.optBoolean("signedOut") == true) {
                    signedOut = true
                    status.text = "You are signed out of Thaana AI. Choose Open Thaana AI website and sign in, then come back."
                    AlertDialog.Builder(this).setTitle("Sign in to Thaana AI")
                        .setMessage("Sign in with your email and password on the website screen, then return here.")
                        .setPositiveButton("Open website") { _, _ -> openThaanaWebsite() }
                        .setNegativeButton("Later", null).show()
                } else status.text = "Text to speech page loaded (${r?.optString("path") ?: page.path}). Use Copy page report."
            }
        }
    }

    private fun copyReport() {
        page.eval(ThaanaSite.PAGE_REPORT) { r ->
            if (r == null) { notice("The page could not be read. Try Reload page."); return@eval }
            val text = buildString {
                append("Thaana AI page report\nApp version: ").append(appVersion()).append('\n')
                append("Path: ").append(r.optString("path")).append("; title: ").append(r.optString("title"))
                    .append("; signed out: ").append(r.optBoolean("signedOut")).append('\n')
                for (key in listOf("headings", "textBoxes", "inputs", "selects", "widgets", "buttons", "media", "downloads", "progress", "alerts")) {
                    val list = r.optJSONArray(key) ?: continue
                    if (list.length() == 0) continue
                    append('\n').append(key.replaceFirstChar { it.uppercase() }).append(":\n")
                    for (i in 0 until list.length()) append("- ").append(list.optString(i)).append('\n')
                }
                append("\nTyped text and passwords are not included.")
            }
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Thaana AI page report", text))
            Toast.makeText(this, "Page report copied", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.isWebShown) screen.showNative() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::page.isInitialized) page.destroy()
        super.onDestroy()
    }
}
