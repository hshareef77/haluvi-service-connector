package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Thaana AI (thaana.ai) addresses and the scripts run inside its pages. */
object ThaanaSite {
    const val BASE = "https://thaana.ai"
    const val SPEECH_PATH = "/text-to-speech"

    // Items of an open popup: a voice menu, list box or settings dialog.
    private const val POPUP = """
const popupRoots=()=>H.all('[role="dialog"],[role="menu"],[role="listbox"],[data-radix-popper-content-wrapper],[aria-modal="true"]');
const popupItems=()=>{const items=[];for(const r of popupRoots()){for(const x of r.querySelectorAll('[role="option"],[role="menuitem"],[role="menuitemradio"],[role="radio"],button,label')){
  if(!H.visible(x))continue;const l=H.opLabel(x);if(l&&l.length<=80&&!items.includes(l))items.push(l);}}return items;};
// Thaana AI shows voices in its Settings side panel ("Voice Library"); older layouts use a menu.
const panelRoots=()=>{const p=popupRoots();if(p.length)return p;
  const vl=Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,div,span,p')).find(x=>x.children.length===0&&H.visible(x)&&/^voice library$/i.test(H.tidy(x.textContent)));
  if(!vl)return [];let r=vl;
  for(let i=0;i<10&&r.parentElement&&r.parentElement!==document.body;i++){r=r.parentElement;if(/\bhistory\b/i.test(r.innerText||'')&&/\bsettings\b/i.test(r.innerText||''))break;}
  return [r];};
const BADGE=/^(male|female|locked|neutral|child)$/i;
const libraryRows=()=>{const out=[],seen=new Set();
  for(const root of panelRoots()){
    const badges=Array.from(root.querySelectorAll('*')).filter(x=>x.children.length<=1&&H.visible(x)&&BADGE.test(H.tidy(x.textContent)));
    for(const b of badges){
      let row=b;
      for(let i=0;i<7&&row.parentElement&&row!==root;i++){row=row.parentElement;
        if((row.innerText||'').split(/\n/).map(H.tidy).filter(Boolean).length>=3)break;}
      const lines=(row.innerText||'').split(/\n/).map(H.tidy).filter(Boolean);
      const count=Array.from(row.querySelectorAll('*')).filter(x=>x.children.length<=1&&BADGE.test(H.tidy(x.textContent))).length;
      const name=(lines[0]||'').slice(0,60);
      if(count!==1||!name||seen.has(name))continue;
      seen.add(name);
      const tag=H.tidy(b.textContent);
      const inner=Array.from(row.querySelectorAll('button,[role="button"],img,[class*="avatar" i],[class*="rounded-full"]')).filter(x=>x!==row&&H.visible(x));
      const preview=inner.find(x=>/preview|play|listen|sample/i.test(H.opLabel(x)+' '+(x.getAttribute('title')||'')+' '+(x.getAttribute('alt')||'')))||
        inner.find(x=>!/locked|info/i.test(H.opLabel(x)+' '+(x.getAttribute('aria-label')||''))&&!H.tidy(x.innerText||''));
      out.push({name:name,node:row,preview:preview||null,active:H.isActive(row),
        tag:tag,description:(lines[1]||'').slice(0,90),locked:/^locked$/i.test(tag)});
    }
  }
  return out;};
const menuRows=()=>{const out=[],seen=new Set();
  for(const r of popupRoots()){
    let items=Array.from(r.querySelectorAll('[role="option"],[role="menuitem"],[role="menuitemradio"],[role="radio"],li'));
    if(!items.length)items=Array.from(r.querySelectorAll('button,label'));
    for(const x of items){
      if(!H.visible(x))continue;
      const name=H.tidy((x.innerText||x.getAttribute('aria-label')||'').split(/\n/)[0]).slice(0,60);
      if(!name||seen.has(name)||/^(preview|play|pause|stop|listen|close|cancel)\b/i.test(name))continue;
      seen.add(name);
      const inner=Array.from(x.querySelectorAll('button,[role="button"]')).filter(b=>b!==x&&H.visible(b));
      const preview=inner.find(b=>/preview|play|listen|sample/i.test(H.opLabel(b)+' '+(b.getAttribute('title')||'')))||inner.find(b=>!H.tidy(b.innerText));
      out.push({name:name,node:x,preview:preview||null,active:H.isActive(x),tag:'',description:'',locked:false});
    }
  }
  return out;};
const voiceEntries=()=>{const rows=libraryRows();return rows.length?rows:menuRows();};
const voiceButton=()=>{const b=H.buttons();const i=b.findIndex(x=>/^add speaker$/i.test(H.label(x)));return i>0?b[i-1]:null;};
"""

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
  widgets:all('[role="listbox"],[role="combobox"],[role="radiogroup"],[role="slider"],[role="option"],[role="switch"],[role="tab"],[role="menuitem"],[role="dialog"]')
    .map(x=>x.getAttribute('role')+': '+(H.opLabel(x)||lab(x)).slice(0,120)+
      (x.getAttribute('aria-valuenow')!=null?' [value '+x.getAttribute('aria-valuenow')+' of '+x.getAttribute('aria-valuemin')+'-'+x.getAttribute('aria-valuemax')+']':'')+
      (x.getAttribute('aria-checked')!=null?' [checked '+x.getAttribute('aria-checked')+']':'')+flags(x)).slice(0,80),
  media:all('audio,video').map(x=>x.tagName.toLowerCase()+': source '+((x.currentSrc||x.src||'').split(':')[0]||'none')+
    ' duration '+(Number.isFinite(x.duration)?x.duration.toFixed(1):'unknown')+(x.controls?' [controls]':'')+(x.paused?' [paused]':' [playing]')),
  downloads:all('a[download],a[href*=".mp3"],a[href*=".wav"],a[href*=".ogg"],a[href^="blob:"]')
    .map(x=>(H.tidy(x.innerText)||x.getAttribute('aria-label')||'(no label)').slice(0,60)+' -> '+(x.getAttribute('href')||'').split(':')[0]+
      (x.hasAttribute('download')?' [download '+(x.getAttribute('download')||'')+']':'')).slice(0,10),
  progress:all('progress,[role="progressbar"],[aria-busy="true"]').map(x=>x.tagName.toLowerCase()+' '+(x.getAttribute('aria-valuenow')||x.value||'')).slice(0,5),
  alerts:H.all('[role="alert"],[role="status"],[aria-live]').map(x=>H.tidy(x.innerText).slice(0,160)).filter(Boolean).slice(0,5)});""")

    /** Types text into Thaana's editor the way typing would, so the page enables Generate. */
    fun setText(text: String) = FaseyhaSite.script("""
const eds=H.all('[contenteditable="true"],textarea').filter(x=>!x.closest('[role="dialog"]'));
if(!eds.length)return JSON.stringify({ok:false,reason:'Thaana AI did not show its text box.'});
const ed=eds[0];ed.focus();
if(ed.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(ed,value);
  ed.dispatchEvent(new Event('input',{bubbles:true}));}
else{document.execCommand('selectAll',false,null);
  if(!document.execCommand('insertText',false,value)){ed.textContent=value;ed.dispatchEvent(new InputEvent('input',{bubbles:true}));}}
const got=H.tidy(ed.innerText||ed.value||''),want=H.tidy(value);
return JSON.stringify({ok:got===want||got.startsWith(want.slice(0,40)),editors:eds.length});""", "value" to text)

    /** Current speaker, Generate state, audio players and messages. */
    val STATE = FaseyhaSite.script("""$POPUP
const gen=H.buttons().filter(x=>/^generate speech$/i.test(H.label(x)));
const audio=Array.from(document.querySelectorAll('audio')).map(x=>x.currentSrc||x.src).filter(Boolean);
const lines=H.body().split(/\n/).map(H.tidy).filter(x=>x&&x.length<200);
return JSON.stringify({signedOut:H.signedOut(),voice:voiceButton()?H.label(voiceButton()):'',
  generate:gen.length,generateEnabled:gen.filter(H.enabled).length,audio:audio,
  busy:H.buttons().some(x=>/generating|cancel|stop/i.test(H.label(x)))||H.all('[aria-busy="true"],[role="progressbar"],progress').length>0,
  messages:lines.filter(x=>/error|failed|insufficient|credit|limit|quota|too long|try again/i.test(x)).slice(0,3)});""")

    val GENERATE = FaseyhaSite.script("""
const m=H.buttons().filter(x=>/^generate speech$/i.test(H.label(x))&&H.enabled(x));
if(m.length!==1)return JSON.stringify({ok:false,reason:m.length?'More than one Generate button.':'Generate Speech is not enabled yet.'});
m[0].click();return JSON.stringify({ok:true});""")

    val VOICE_OPEN = FaseyhaSite.script("""$POPUP
const b=voiceButton();if(!b)return JSON.stringify({ok:false,reason:'The speaker button was not found.'});
b.click();return JSON.stringify({ok:true,voice:H.label(b)});""")

    /** Opens Thaana AI's Settings panel, where the Voice Library is. */
    val VOICE_OPEN_SETTINGS = FaseyhaSite.script("""$POPUP
if(libraryRows().length)return JSON.stringify({ok:true});
const m=H.buttons().filter(x=>/^open settings$/i.test(H.label(x)));
if(m.length!==1)return JSON.stringify({ok:false,reason:'The Settings button was not found.'});
m[0].click();return JSON.stringify({ok:true});""")

    /** Voices in the open speaker list, with whether each has a preview button. */
    val VOICE_LIST = FaseyhaSite.script("""$POPUP
return JSON.stringify({voices:voiceEntries().map(v=>({name:v.name,preview:!!v.preview,active:v.active,tag:v.tag,description:v.description,locked:v.locked}))});""")

    fun voicePreview(name: String) = FaseyhaSite.script("""$POPUP
const v=voiceEntries().find(x=>x.name===wanted);
if(!v)return JSON.stringify({ok:false,reason:'closed'});
if(!v.preview)return JSON.stringify({ok:false,reason:'Thaana AI shows no preview button for this voice.'});
v.preview.click();return JSON.stringify({ok:true});""", "wanted" to name)

    fun voiceChoose(name: String) = FaseyhaSite.script("""$POPUP
const v=voiceEntries().find(x=>x.name===wanted);
if(!v)return JSON.stringify({ok:false,reason:'closed'});
v.node.click();return JSON.stringify({ok:true});""", "wanted" to name)

    /** Remembers every audio the page plays (including previews), so the app can stop them. */
    val TRACK_MEDIA = FaseyhaSite.script("""
if(!window.__haluviTracked){window.__haluviTracked=true;window.__haluviMedia=[];
  const play=HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play=function(){if(!window.__haluviMedia.includes(this))window.__haluviMedia.push(this);return play.apply(this,arguments);};}
return JSON.stringify({ok:true});""")

    val STOP_MEDIA = FaseyhaSite.script("""
const all=[...(window.__haluviMedia||[]),...document.querySelectorAll('audio,video')];
let n=0;for(const m of all){try{if(!m.paused){m.pause();n++;}}catch(e){}}
return JSON.stringify({ok:true,stopped:n});""")

    val POPUP_LIST = FaseyhaSite.script("""$POPUP
return JSON.stringify({items:popupItems(),text:popupRoots().map(r=>H.tidy(r.innerText).slice(0,400))});""")

    val CLOSE_POPUP = FaseyhaSite.script("""$POPUP
for(const t of [document.activeElement,document.body,document])
  t&&t.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,bubbles:true}));
for(const r of panelRoots()){
  const c=Array.from(r.querySelectorAll('button,[role="button"]')).filter(H.visible)
    .find(x=>/^(close|close panel|close settings|×|✕|x)$/i.test(H.tidy(x.getAttribute('aria-label')||x.innerText||x.getAttribute('title')||'')));
  if(c)c.click();
}
return JSON.stringify({ok:true});""")

    /** Controls inside the open dialog or popup, for building native settings. */
    val POPUP_REPORT = FaseyhaSite.script("""$POPUP
const out=[];
for(const r of panelRoots()){
  out.push('Panel text: '+H.tidy(r.innerText).slice(0,800));
  for(const x of r.querySelectorAll('input,select,textarea,button,[role="slider"],[role="switch"],[role="option"],[role="menuitem"],[role="radio"],[role="combobox"]')){
    const role=x.getAttribute('role')||x.tagName.toLowerCase()+(x.type?'/'+x.type:'');
    const owner=x.parentElement&&x.parentElement.closest('[role="option"],[role="menuitem"],[role="menuitemradio"],[role="radio"],li');
    const icon=x.getAttribute('aria-label')||x.getAttribute('title')||(x.querySelector('svg')?'(icon)':'');
    out.push(role+': '+(H.opLabel(x)||H.fieldLabel(x)||icon).slice(0,80)+
      (owner?' [in "'+H.tidy((owner.innerText||'').split(/\n/)[0]).slice(0,40)+'"]':'')+
      (x.getAttribute('aria-valuenow')!=null?' [value '+x.getAttribute('aria-valuenow')+' of '+x.getAttribute('aria-valuemin')+'-'+x.getAttribute('aria-valuemax')+']':'')+
      (x.type==='range'||x.type==='number'?' [min '+x.min+' max '+x.max+' step '+x.step+' value '+x.value+']':'')+
      (x.getAttribute('aria-checked')!=null?' [checked '+x.getAttribute('aria-checked')+']':'')+
      (x.tagName==='SELECT'?' [options '+Array.from(x.options).map(o=>H.tidy(o.text)).join(', ').slice(0,300)+']':'')+
      (H.isActive(x)?' [active]':'')+(x.disabled?' [disabled]':''));
  }
}
out.push('Voice entries: '+voiceEntries().map(v=>v.name+' '+v.tag+(v.preview?' (preview: '+(v.preview.tagName.toLowerCase())+')':' (no preview)')+(v.active?' [active]':'')+(v.locked?' [locked]':'')).join('; '));
return JSON.stringify({lines:out.slice(0,140)});""")

    /** Copies the newest generated audio into the page's memory as base64, for the app to read. */
    /** Address of the newest generated audio. */
    val AUDIO_SOURCE = FaseyhaSite.script("""
const srcs=Array.from(document.querySelectorAll('audio')).map(x=>x.currentSrc||x.src).filter(Boolean);
return JSON.stringify({src:srcs[srcs.length-1]||''});""")

    val AUDIO_START = FaseyhaSite.script("""
const srcs=Array.from(document.querySelectorAll('audio')).map(x=>x.currentSrc||x.src).filter(Boolean);
const src=srcs[srcs.length-1];if(!src)return JSON.stringify({ok:false});
window.__haluviAudio={state:'loading'};
fetch(src).then(r=>r.blob()).then(b=>new Promise((res,rej)=>{const f=new FileReader();
  f.onload=()=>res({data:String(f.result).split(',')[1]||'',type:b.type||''});f.onerror=rej;f.readAsDataURL(b);}))
  .then(x=>{window.__haluviAudio={state:'ready',data:x.data,type:x.type};})
  .catch(e=>{window.__haluviAudio={state:'error',reason:String(e).slice(0,120)};});
return JSON.stringify({ok:true});""")

    val AUDIO_POLL = FaseyhaSite.script("""
const a=window.__haluviAudio||{state:'none'};
return JSON.stringify({state:a.state,length:(a.data||'').length,type:a.type||'',reason:a.reason||''});""")

    const val CHUNK = 400_000
    fun audioChunk(index: Int) = FaseyhaSite.script("""
const a=window.__haluviAudio||{};const i=Number(n);
return JSON.stringify({chunk:(a.data||'').slice(i*$CHUNK,(i+1)*$CHUNK)});""", "n" to index)
}

fun Activity.openThaanaWebsite(path: String = ThaanaSite.SPEECH_PATH) {
    startActivity(Intent(this, WebsiteActivity::class.java)
        .putExtra(WebsiteActivity.EXTRA_URL, ThaanaSite.BASE + path)
        .putExtra(WebsiteActivity.EXTRA_TITLE, "Thaana AI website"))
}

/**
 * Native Thaana AI text to speech: text box, text-file attachment, speaker choice,
 * Generate with progress, native playback and saving. The signed-in Thaana AI page
 * runs hidden behind this screen; the app types into it and presses its buttons.
 */
class ThaanaSpeechActivity : Activity() {
    private companion object {
        const val PICK_TEXT = 41
        const val SAVE_AUDIO = 42
        const val MAX_TEXT = 20_000
    }

    private lateinit var screen: EngineScreen
    private lateinit var page: FaseyhaEngine
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var voiceButton: Button
    private lateinit var generateButton: Button
    private lateinit var playButton: Button
    private lateinit var player: LinearLayout

    private var voice = ""
    private var pageReady = false
    private var signedOut = false
    private var resumedOnce = false
    private var busy = false
    private var run = 0
    private var audioFile: File? = null
    private var audioType = "audio/mpeg"
    private var media: MediaPlayer? = null
    private lateinit var speedButton: Button
    private var speed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EngineScreen(this, "Thaana AI text to speech")
        status = screen.text("Opening Thaana AI…", 17f, live = true)
        input = EditText(this).apply {
            hint = "Text to speak"; contentDescription = "Text to speak"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; maxLines = 12; gravity = Gravity.TOP or Gravity.START
        }
        screen.panel.addView(input)
        screen.button("Attach text file") { pickTextFile() }
        voiceButton = screen.button("Voice: loading") { chooseVoice() }
        generateButton = screen.button("Generate speech") { generate() }
        player = screen.section()
        playButton = screen.button("Play", player) { togglePlay() }
        screen.button("Stop", player) { stopPlayback() }
        speedButton = screen.button("Speed: 1.0x. Change", player) { chooseSpeed() }
        screen.button("Save audio", player) { saveAudio() }
        lateinit var moreToggle: Button
        lateinit var more: LinearLayout
        moreToggle = screen.button("More options") {
            more.visibility = if (more.visibility == View.GONE) View.VISIBLE else View.GONE
            moreToggle.text = if (more.visibility == View.VISIBLE) "Hide more options" else "More options"
        }
        more = screen.section()
        screen.button("Show website page", more) { screen.showWeb(page, "Thaana AI text to speech page") }
        screen.button("Copy page report", more) { copyPageReport() }
        screen.button("Copy voice list report", more) { copyPopupReport(ThaanaSite.VOICE_OPEN_SETTINGS, "voice list") }
        screen.button("Copy settings report", more) { copyPopupReport(ThaanaSite.VOICE_OPEN_SETTINGS, "settings") }
        screen.button("Reload page", more) { reloadPage() }
        screen.button("Open Thaana AI website and sign in", more) { openThaanaWebsite() }
        page = FaseyhaEngine(this, ThaanaSite.BASE) { onLoaded() }
        // Voice previews start from the app's request, not a tap on the page itself.
        page.web.settings.mediaPlaybackRequiresUserGesture = false
        screen.attach(page)
        setContentView(screen.root)
        page.open(ThaanaSite.SPEECH_PATH)
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (signedOut) { signedOut = false; reloadPage() }
    }

    private fun say(text: String) { status.text = text }

    private fun reloadPage() {
        pageReady = false
        say("Reloading Thaana AI…")
        page.open(ThaanaSite.SPEECH_PATH)
    }

    // Single-page sites finish rendering after the load event: poll until the page is usable.
    private fun onLoaded(attempt: Int = 0) {
        if (attempt == 0) page.eval(ThaanaSite.TRACK_MEDIA) {}
        page.after(if (attempt == 0) 1200 else 800) {
            page.eval(ThaanaSite.STATE) { r ->
                when {
                    r?.optBoolean("signedOut") == true -> handleSignedOut()
                    r != null && r.optInt("generate") > 0 -> {
                        pageReady = true
                        updateVoice(r.optString("voice"))
                        if (!busy) say("Ready. Type or attach text, then press Generate speech.")
                    }
                    attempt < 12 -> onLoaded(attempt + 1)
                    else -> say("The text to speech page did not finish loading. Use More options, Reload page.")
                }
            }
        }
    }

    private fun handleSignedOut() {
        signedOut = true
        pageReady = false
        say("You are signed out of Thaana AI. Sign in on the website screen, then come back.")
        AlertDialog.Builder(this).setTitle("Sign in to Thaana AI")
            .setMessage("Sign in with your email and password on the website screen, then return here.")
            .setPositiveButton("Open website") { _, _ -> openThaanaWebsite() }
            .setNegativeButton("Later", null).show()
    }

    private fun updateVoice(name: String) {
        if (name.isNotBlank()) voice = name
        voiceButton.text = if (voice.isBlank()) "Voice: not shown. Choose voice" else "Voice: $voice. Change"
        voiceButton.contentDescription = voiceButton.text
    }

    // ---------- voice ----------

    /** Opens Thaana AI's speaker list (if not already open) and runs [then] with its voices. */
    private data class Voice(val name: String, val preview: Boolean, val active: Boolean,
                             val tag: String, val description: String, val locked: Boolean)

    private fun withVoiceList(then: (List<Voice>) -> Unit) {
        fun read(attempt: Int) {
            page.eval(ThaanaSite.VOICE_LIST) { r ->
                val a = r?.optJSONArray("voices")
                val voices = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                    a!!.optJSONObject(i)?.let { Voice(it.optString("name"), it.optBoolean("preview"), it.optBoolean("active"),
                        it.optString("tag"), it.optString("description"), it.optBoolean("locked")) }
                }.filter { it.name.isNotBlank() }
                when {
                    voices.isNotEmpty() -> then(voices)
                    // First the Settings panel (Voice Library), then the speaker button's menu.
                    attempt == 0 -> page.eval(ThaanaSite.VOICE_OPEN_SETTINGS) { o ->
                        page.after(if (o?.optBoolean("ok") == true) 1200 else 0) { read(1) }
                    }
                    attempt == 3 -> page.eval(ThaanaSite.VOICE_OPEN) { page.after(900) { read(4) } }
                    attempt < 6 -> page.after(600) { read(attempt + 1) }
                    else -> {
                        page.eval(ThaanaSite.CLOSE_POPUP) {}
                        say("The voice list could not be read. Use More options, Copy voice list report, and send it.")
                    }
                }
            }
        }
        read(0)
    }

    private fun closeVoices() {
        page.eval(ThaanaSite.STOP_MEDIA) {}
        page.eval(ThaanaSite.CLOSE_POPUP) {}
    }

    private fun chooseVoice() {
        if (!pageReady || busy) { say(if (busy) "Please wait until speech is ready." else "The page is still loading."); return }
        say("Opening the voice list…")
        withVoiceList { voices ->
            val labels = voices.map { v ->
                listOf(v.name, v.tag.takeIf { it.isNotBlank() && !v.locked },
                    if (v.active || v.name.equals(voice, true)) "current voice" else null,
                    if (v.locked) "locked" else null).filterNotNull().joinToString(", ") +
                    (if (v.description.isNotBlank()) ": " + v.description else "")
            }
            AlertDialog.Builder(this).setTitle("Voices")
                .setItems(labels.toTypedArray()) { _, i ->
                    val v = voices[i]
                    if (v.locked) { say("${v.name} is locked on Thaana AI and cannot be chosen."); chooseVoice() }
                    else voiceActions(v.name, v.preview)
                }
                .setNegativeButton("Close") { _, _ -> closeVoices() }
                .setOnCancelListener { closeVoices() }
                .show()
            say("Choose a voice to preview or select.")
        }
    }

    /** Preview or choose one voice; the list stays available for previewing others. */
    private fun voiceActions(name: String, hasPreview: Boolean) {
        AlertDialog.Builder(this).setTitle(name)
            .setMessage(if (hasPreview) "Preview plays Thaana AI's sample of this voice." else "Thaana AI shows no preview for this voice.")
            .apply { if (hasPreview) setNeutralButton("Preview") { _, _ -> previewVoice(name) } }
            .setPositiveButton("Choose this voice") { _, _ -> chooseThisVoice(name) }
            .setNegativeButton("Back to voices") { _, _ -> page.eval(ThaanaSite.STOP_MEDIA) {}; chooseVoice() }
            .setOnCancelListener { closeVoices() }
            .show()
    }

    private fun previewVoice(name: String) {
        page.eval(ThaanaSite.STOP_MEDIA) {}
        fun attempt(reopened: Boolean) {
            page.eval(ThaanaSite.voicePreview(name)) { r ->
                when {
                    r?.optBoolean("ok") == true -> say("Playing the $name preview.")
                    r?.optString("reason") == "closed" && !reopened -> withVoiceList { attempt(true) }
                    else -> say(r?.optString("reason")?.takeIf { it != "closed" } ?: "The preview could not be played.")
                }
                // Keep the choice open: preview again, choose, or go back to the list.
                AlertDialog.Builder(this).setTitle(name)
                    .setNeutralButton("Preview again") { _, _ -> previewVoice(name) }
                    .setPositiveButton("Choose this voice") { _, _ -> chooseThisVoice(name) }
                    .setNegativeButton("Back to voices") { _, _ -> page.eval(ThaanaSite.STOP_MEDIA) {}; chooseVoice() }
                    .setOnCancelListener { closeVoices() }
                    .show()
            }
        }
        attempt(false)
    }

    private fun chooseThisVoice(name: String) {
        page.eval(ThaanaSite.STOP_MEDIA) {}
        fun attempt(reopened: Boolean) {
            page.eval(ThaanaSite.voiceChoose(name)) { r ->
                if (r?.optString("reason") == "closed" && !reopened) { withVoiceList { attempt(true) }; return@eval }
                if (r?.optBoolean("ok") != true) { closeVoices(); say("Thaana AI did not accept the $name voice."); return@eval }
                page.after(700) {
                    page.eval(ThaanaSite.CLOSE_POPUP) {}
                    page.eval(ThaanaSite.STATE) { s ->
                        updateVoice(s?.optString("voice").orEmpty())
                        say(if (voice.equals(name, true)) "Voice: $voice." else "Selected $name; Thaana AI shows $voice as the speaker.")
                    }
                }
            }
        }
        attempt(false)
    }

    // ---------- generate ----------

    private fun generate() {
        if (busy) return
        if (!pageReady) { say("The page is still loading. Try again in a moment."); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) { input.error = "Type or attach the text to speak"; input.requestFocus(); return }
        busy = true
        val token = ++run
        generateButton.isEnabled = false
        stopPlayback()
        say("Entering the text on Thaana AI…")
        page.eval(ThaanaSite.STATE) { before ->
            val oldAudio = before?.optJSONArray("audio")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            page.eval(ThaanaSite.setText(text)) { r ->
                if (token != run) return@eval
                if (r?.optBoolean("ok") != true) { fail(r?.optString("reason")?.ifBlank { null } ?: "The text could not be entered on Thaana AI."); return@eval }
                page.after(700) { pressGenerate(token, oldAudio, 0) }
            }
        }
    }

    private fun pressGenerate(token: Int, oldAudio: Set<String>, attempt: Int) {
        page.eval(ThaanaSite.GENERATE) { r ->
            if (token != run) return@eval
            if (r?.optBoolean("ok") == true) { say("Generating speech…"); page.after(1500) { waitForAudio(token, oldAudio, 0) } }
            else if (attempt < 6) page.after(600) { pressGenerate(token, oldAudio, attempt + 1) }
            else fail(r?.optString("reason") ?: "Generate Speech could not be pressed.")
        }
    }

    private fun waitForAudio(token: Int, oldAudio: Set<String>, attempt: Int) {
        page.eval(ThaanaSite.STATE) { s ->
            if (token != run) return@eval
            val audio = s?.optJSONArray("audio")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val messages = s?.optJSONArray("messages")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            when {
                audio.any { it !in oldAudio } -> { say("Speech generated. Preparing playback…"); fetchAudio(token) }
                messages.isNotEmpty() && attempt > 2 && s?.optBoolean("busy") != true -> fail("Thaana AI says: ${messages.first()}")
                attempt >= 80 -> fail("Speech was not ready after two minutes. Use More options, Copy page report, and send it.")
                else -> {
                    if (attempt % 5 == 4) say("Generating speech… ${(attempt + 1) * 3 / 2} seconds.")
                    page.after(1500) { waitForAudio(token, oldAudio, attempt + 1) }
                }
            }
        }
    }

    /**
     * Web addresses are downloaded by the app itself: the audio is served from another
     * server that does not let the page's script read it. Page-only (blob) audio is
     * copied through the page.
     */
    private fun fetchAudio(token: Int) {
        page.eval(ThaanaSite.AUDIO_SOURCE) { r ->
            if (token != run) return@eval
            val src = r?.optString("src").orEmpty()
            if (src.startsWith("https://")) downloadAudio(token, src) else copyThroughPage(token)
        }
    }

    private fun downloadAudio(token: Int, src: String) {
        say("Downloading the speech…")
        val agent = page.web.settings.userAgentString
        val cookies = android.webkit.CookieManager.getInstance().getCookie(src)
        Thread {
            var bytes: ByteArray? = null
            var type = ""
            var problem = ""
            try {
                val connection = (java.net.URL(src).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000
                    setRequestProperty("User-Agent", agent)
                    setRequestProperty("Referer", ThaanaSite.BASE + ThaanaSite.SPEECH_PATH)
                    if (!cookies.isNullOrBlank()) setRequestProperty("Cookie", cookies)
                }
                try {
                    if (connection.responseCode !in 200..299) problem = "the server answered ${connection.responseCode}"
                    else {
                        type = connection.contentType.orEmpty()
                        val data = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                if (out.size() > 100 * 1024 * 1024) throw java.io.IOException("larger than 100 MB")
                            }
                            out.toByteArray()
                        }
                        bytes = data
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) { problem = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                if (token != run || isFinishing) return@runOnUiThread
                val data = bytes
                if (data != null) {
                    if (type.startsWith("audio/") || type.startsWith("video/")) audioType = type.substringBefore(';')
                    else audioType = guessType(src)
                    saveBytes(data)
                } else {
                    say("Download failed ($problem). Trying through the page…")
                    copyThroughPage(token)
                }
            }
        }.start()
    }

    private fun guessType(src: String): String {
        val path = android.net.Uri.parse(src).path.orEmpty().lowercase()
        return when {
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac") -> "audio/mp4"
            path.endsWith(".webm") -> "audio/webm"
            else -> "audio/mpeg"
        }
    }

    private fun copyThroughPage(token: Int) {
        page.eval(ThaanaSite.AUDIO_START) { r ->
            if (token != run) return@eval
            if (r?.optBoolean("ok") != true) { fail("The generated audio could not be found."); return@eval }
            pollAudio(token, 0)
        }
    }

    private fun pollAudio(token: Int, attempt: Int) {
        page.after(500) {
            page.eval(ThaanaSite.AUDIO_POLL) { r ->
                if (token != run) return@eval
                when (r?.optString("state")) {
                    "ready" -> {
                        audioType = r.optString("type").ifBlank { "audio/mpeg" }
                        readChunks(token, r.optInt("length"), 0, StringBuilder())
                    }
                    "error" -> fail("The audio could not be copied: ${r.optString("reason")}.")
                    else -> if (attempt < 120) pollAudio(token, attempt + 1) else fail("Copying the audio took too long.")
                }
            }
        }
    }

    private fun readChunks(token: Int, length: Int, index: Int, data: StringBuilder) {
        if (index * ThaanaSite.CHUNK >= length) { saveToCache(data.toString()); return }
        page.eval(ThaanaSite.audioChunk(index)) { r ->
            if (token != run) return@eval
            data.append(r?.optString("chunk").orEmpty())
            readChunks(token, length, index + 1, data)
        }
    }

    private fun extension() = when {
        audioType.contains("wav") -> "wav"
        audioType.contains("ogg") -> "ogg"
        audioType.contains("webm") -> "webm"
        audioType.contains("mp4") || audioType.contains("aac") || audioType.contains("m4a") -> "m4a"
        else -> "mp3"
    }

    private fun saveToCache(base64: String) {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        saveBytes(bytes)
    }

    private fun saveBytes(bytes: ByteArray) {
        try {
            if (bytes.isEmpty()) { fail("The generated audio was empty."); return }
            cacheDir.listFiles { f -> f.name.startsWith("thaana-speech.") }?.forEach { it.delete() }
            val file = File(cacheDir, "thaana-speech." + extension())
            file.writeBytes(bytes)
            audioFile = file
            val prepared = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { playButton.text = "Play"; say("Playback finished.") }
            }
            media = prepared
            busy = false
            generateButton.isEnabled = true
            player.visibility = View.VISIBLE
            playButton.text = "Play"
            applySpeed()
            say("Speech ready, ${prepared.duration / 1000} seconds. Play or save it.")
        } catch (e: Exception) {
            fail("The audio could not be prepared for playback.")
        }
    }

    private fun fail(message: String) {
        busy = false
        run++
        generateButton.isEnabled = true
        say(message)
    }

    // ---------- playback and saving ----------

    private fun togglePlay() {
        val m = media ?: return
        if (m.isPlaying) { m.pause(); playButton.text = "Play"; say("Paused.") }
        else { m.start(); playButton.text = "Pause"; say("Playing.") }
    }

    private fun chooseSpeed() {
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }) { d, i ->
                d.dismiss(); speed = speeds[i]; applySpeed(); say("Playback speed ${speed}x.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applySpeed() {
        speedButton.text = "Speed: ${speed}x. Change"
        val m = media ?: return
        try {
            val wasPlaying = m.isPlaying
            m.playbackParams = m.playbackParams.setSpeed(speed)
            if (!wasPlaying) m.pause() // setting the speed starts playback
        } catch (_: Exception) {}
    }

    private fun stopPlayback() {
        media?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
        if (::playButton.isInitialized) playButton.text = "Play"
    }

    private fun saveAudio() {
        if (audioFile == null) return
        val name = "thaana-speech-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + "." + extension()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(audioType).putExtra(Intent.EXTRA_TITLE, name), SAVE_AUDIO)
    }

    private fun pickTextFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*"), PICK_TEXT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                PICK_TEXT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    input.setText(text.take(MAX_TEXT))
                    say(if (text.length > MAX_TEXT) "Attached the first $MAX_TEXT characters of the file." else "Text attached: ${text.length} characters.")
                }
                SAVE_AUDIO -> {
                    val file = audioFile ?: return
                    contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    say("Audio saved.")
                }
            }
        } catch (e: Exception) {
            say(if (requestCode == PICK_TEXT) "The file could not be read as text." else "The audio could not be saved.")
        }
    }

    // ---------- reports ----------

    private fun copy(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyPageReport() {
        page.eval(ThaanaSite.PAGE_REPORT) { r ->
            if (r == null) { notice("The page could not be read. Try Reload page."); return@eval }
            val text = buildString {
                append("Thaana AI page report\nApp version: ").append(appVersion()).append('\n')
                append("Path: ").append(r.optString("path")).append("; signed out: ").append(r.optBoolean("signedOut")).append('\n')
                append("App status: ").append(status.text).append('\n')
                for (key in listOf("headings", "textBoxes", "inputs", "selects", "widgets", "buttons", "media", "downloads", "progress", "alerts")) {
                    val list = r.optJSONArray(key) ?: continue
                    if (list.length() == 0) continue
                    append('\n').append(key.replaceFirstChar { it.uppercase() }).append(":\n")
                    for (i in 0 until list.length()) append("- ").append(list.optString(i)).append('\n')
                }
                append("\nTyped text and passwords are not included.")
            }
            copy("Page report", text)
        }
    }

    /** Opens the voice list or settings on the page, reports its controls, then closes it. */
    private fun copyPopupReport(openScript: String, name: String) {
        if (!pageReady) { say("The page is still loading."); return }
        page.eval(openScript) { opened ->
            if (opened?.optBoolean("ok") != true) { say("The $name could not be opened on the page."); return@eval }
            page.after(900) {
                page.eval(ThaanaSite.POPUP_REPORT) { r ->
                    page.eval(ThaanaSite.CLOSE_POPUP) {}
                    val lines = r?.optJSONArray("lines")
                    val text = buildString {
                        append("Thaana AI $name report\nApp version: ").append(appVersion()).append('\n')
                        if (lines == null || lines.length() == 0) append("No open popup was found.\n")
                        else for (i in 0 until lines.length()) append("- ").append(lines.optString(i)).append('\n')
                    }
                    copy("${name.replaceFirstChar { it.uppercase() }} report", text)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.isWebShown) screen.showNative() else super.onBackPressed()
    }

    override fun onDestroy() {
        media?.release()
        media = null
        if (::page.isInitialized) page.destroy()
        super.onDestroy()
    }
}
