package mv.haluvi.serviceconnector

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Faseyha website addresses and every script the app runs inside the provider page.
 *
 * Each script receives the shared helper library as `H` plus named, JSON-quoted
 * arguments. User input is never concatenated into JavaScript source.
 */
object FaseyhaSite {
    const val HOST = "faseyha.net"
    const val BASE = "https://faseyha.net"

    fun trusted(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    fun trustedUrl(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        return uri.scheme == "https" && trusted(uri.host)
    }

    /** Path of a trusted Faseyha URL, or null for any other address. */
    fun pathOf(url: String?): String? = if (trustedUrl(url)) Uri.parse(url).path?.ifEmpty { "/" } ?: "/" else null

    fun isReceiptUrl(url: String?) = pathOf(url)?.startsWith("/invoice/") == true

    /** Decodes the JSON string returned by evaluateJavascript. */
    fun decode(raw: String?): JSONObject? = try {
        JSONObject(JSONArray("[$raw]").getString(0))
    } catch (_: Exception) { null }

    fun script(body: String, vararg args: Pair<String, Any?>): String {
        val names = args.joinToString("") { "," + it.first }
        val values = args.joinToString("") { "," + JSONObject.quote(it.second?.toString() ?: "") }
        return "(function(H$names){try{$body}catch(e){return JSON.stringify({ok:false,stage:'error'," +
            "reason:'Page script error: '+String(e&&e.message||e).slice(0,160)});}})($HELPERS$values);"
    }

    private val HELPERS = """(function(){
const tidy=x=>String(x==null?'':x).replace(/\s+/g,' ').trim();
const low=x=>tidy(x).toLowerCase();
const trusted=()=>location.protocol==='https:'&&(location.hostname==='faseyha.net'||location.hostname.endsWith('.faseyha.net'));
const visible=x=>!!x&&x.getClientRects().length>0&&getComputedStyle(x).visibility!=='hidden';
const enabled=x=>visible(x)&&!x.disabled&&x.getAttribute('aria-disabled')!=='true'&&getComputedStyle(x).pointerEvents!=='none';
const label=x=>tidy(x.innerText||x.value||x.getAttribute('aria-label')||x.textContent);
const labels=x=>[x.innerText,x.value,x.getAttribute('aria-label')].filter(Boolean).map(tidy);
const fieldLabel=x=>low([(x.labels&&x.labels.length?x.labels[0].innerText:''),x.getAttribute('aria-label'),x.getAttribute('placeholder'),x.name,x.id].filter(Boolean).join(' '));
const all=sel=>Array.from(document.querySelectorAll(sel)).filter(visible);
const buttons=()=>all('button,[role="button"],input[type="submit"]');
const inputs=()=>all('input').filter(x=>!x.disabled&&!['hidden','password','email','submit','button','checkbox','radio','search'].includes((x.type||'').toLowerCase()));
const body=()=>(document.body&&document.body.innerText)||'';
const summary=()=>{const t=body(),i=t.toLowerCase().lastIndexOf('order summary');return i<0?'':tidy(t.slice(i,i+900));};
const isActive=x=>{
  if(['aria-selected','aria-checked','aria-pressed','aria-current'].some(k=>{const v=x.getAttribute(k);return v==='true'||v==='page';}))return true;
  if(['active','checked','on'].includes(x.getAttribute('data-state')))return true;
  const input=x.matches('input')?x:x.querySelector('input[type="radio"],input[type="checkbox"]');
  if(input&&input.checked)return true;
  const cls=(' '+(typeof x.className==='string'?x.className:'')+' ').toLowerCase();
  return /(?:^|[\s_-])(selected|active|checked|current)(?:[\s_-]|$)/.test(cls);
};
const setValue=(x,v)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(x,v);
  x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));x.dispatchEvent(new FocusEvent('blur'));};
const OPS={dhiraagu:['dhiraagu'],ooredoo:['ooredoo','supernet','raastas'],medianet:['medianet']};
const OPRE=/^(dhiraagu|ooredoo|supernet|medianet)(?:\s*\/\s*[a-z][a-z ]{1,20}|\s+(?:raastas|reload|postpaid|prepaid|bills?))?$/;
const opOf=t=>{const m=low(t).match(OPRE);return m?(m[1]==='supernet'?'ooredoo':m[1]):'';};
const opLabel=x=>{const img=x.querySelector&&x.querySelector('img[alt]');return label(x)||tidy(img?img.alt:'');};
const opControls=()=>all('button,[role="tab"],[role="radio"],[role="button"],label').filter(x=>opOf(opLabel(x)));
const operatorState=()=>{
  const ctl=opControls();
  const act=[...new Set(ctl.filter(isActive).map(x=>opOf(opLabel(x))))];
  const heads=[...new Set(all('h1,h2,h3,h4,h5,[role="heading"]').map(x=>opOf(x.innerText||x.textContent)).filter(Boolean))];
  const b=low(body());
  const cards=Object.keys(OPS).filter(k=>OPS[k].some(a=>b.includes('pay any '+a)));
  return {active:act.length===1?act[0]:'',activeCount:act.length,headings:heads,cards:cards,controls:ctl.length};
};
const operatorMatches=(op,sum)=>{
  const w=low(op),s=operatorState();
  if(s.active)return s.active===w;
  if(sum&&(OPS[w]||[w]).some(a=>low(sum).includes(a)))return true;
  return (s.headings.length===1&&s.headings[0]===w)||(s.cards.length===1&&s.cards[0]===w);
};
const ACTION={'Recharge':/^recharge(?:\s+now)?(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/i,
  'Pay':/^pay(?:\s+(?:now|bill))?(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/i,
  'Activate package':/^activate(?:\s+package)?(?:\s+(?:mvr|rf|ރ)?\s*\d[\d,.]*)?$/i};
const actionButtons=a=>buttons().filter(x=>labels(x).some(l=>ACTION[a].test(l)));
const amounts=t=>{const out=[];let m;
  const a=/(?:^|[^A-Za-z0-9])(?:MVR|MRf|Rf|ރ\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?![0-9A-Za-z])/gi;
  while((m=a.exec(t)))out.push(Number(m[1].replace(/,/g,'')));
  const b=/(?:^|[^A-Za-z0-9.,])([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:MVR|MRf)(?![A-Za-z])/gi;
  while((m=b.exec(t)))out.push(Number(m[1].replace(/,/g,'')));
  return out.filter(Number.isFinite);};
const TOTALS=['total\\s+to\\s+pay','total\\s+amount','amount\\s+to\\s+pay','amount\\s+paid','total','amount'];
const labelledTotal=t=>{const f=tidy(t);for(const l of TOTALS){const m=f.match(new RegExp('(?:^|[^A-Za-z])'+l+'\\s*:?\\s*(?:MVR|MRf|Rf|ރ\\.?)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9A-Za-z])','i'));if(m)return Number(m[1].replace(/,/g,''));}return NaN;};
const amountShown=(t,want)=>{const total=labelledTotal(t);return Number.isFinite(total)?total===want:amounts(t).some(v=>v===want);};
const alnum=s=>String(s||'').toUpperCase().replace(/[^A-Z0-9]/g,'');
const hasRecipient=(t,r)=>{const w=alnum(r);if(!w)return false;const up=String(t||'').toUpperCase();
  const re=new RegExp('(^|[^A-Z0-9])(?:\\+?960)?'+w+'([^A-Z0-9]|$)');
  return re.test(up)||re.test(up.replace(/\b(\d{3})[\s-](\d{4})\b/g,'$1$2'));};
const mentions=(t,op)=>{const l=low(t);return (OPS[low(op)]||[low(op)]).some(a=>l.includes(a));};
const snapshot=()=>{const lines=body().split(/\n/).map(x=>x.trim()).filter(Boolean);window.__haluviBeforeSubmit=lines;
  try{sessionStorage.setItem('haluvi_before_submit',JSON.stringify(lines));}catch(e){}};
const previous=()=>{let old=window.__haluviBeforeSubmit||[];
  if(!old.length){try{old=JSON.parse(sessionStorage.getItem('haluvi_before_submit')||'[]');}catch(e){}}
  return new Set(old.map(tidy));};
const signedOut=()=>/\/(login|signin|sign-in|register|auth)(\/|$)/i.test(location.pathname)||!!document.querySelector('input[type="password"]');
return {tidy,low,trusted,visible,enabled,label,labels,fieldLabel,all,buttons,inputs,body,summary,isActive,setValue,OPS,opOf,opLabel,
  opControls,operatorState,operatorMatches,actionButtons,amounts,labelledTotal,amountShown,alnum,hasRecipient,mentions,snapshot,previous,signedOut};
})()"""

    val SIGNED_OUT = script("""return JSON.stringify({signedOut:H.signedOut(),path:location.pathname});""")

    fun applyOperator(operator: String) = script("""
if(!H.trusted())return JSON.stringify({ok:false,reason:'This page is not on Faseyha.'});
const w=H.low(op);
if(H.operatorState().active===w)return JSON.stringify({ok:true,clicked:false,active:w});
const safe=x=>!/(pay|recharge now|activate|submit|purchase|checkout|confirm)/.test(H.low(H.opLabel(x)));
let pick=H.opControls().filter(x=>!x.disabled&&safe(x)&&H.opOf(H.opLabel(x))===w);
if(pick.length>1){const exact=pick.filter(x=>H.low(H.opLabel(x))===w);if(exact.length===1)pick=exact;}
if(pick.length!==1)return JSON.stringify({ok:false,reason:pick.length===0?'No '+op+' option is visible on Faseyha yet.':
  'Faseyha shows '+pick.length+' '+op+' options, so the app cannot choose one safely.'});
pick[0].click();
return JSON.stringify({ok:true,clicked:true,active:H.operatorState().active});""", "op" to operator)

    val CATEGORIES = script("""
const names=H.all('button,[role="tab"],[role="button"]').filter(x=>!x.disabled)
  .map(x=>H.tidy(x.innerText||x.getAttribute('aria-label')||''))
  .filter(x=>/^(all|special offer|mini data plans|weekly|monthly|data|call only|salhi|[0-9]+ ?gb)$/i.test(x))
  .filter((x,i,a)=>a.indexOf(x)===i);
return JSON.stringify({categories:names});""")

    fun applyCategory(name: String) = script("""
const match=H.all('button,[role="tab"],[role="button"]').filter(x=>!x.disabled)
  .filter(x=>H.low(x.innerText||x.getAttribute('aria-label'))===H.low(wanted));
if(match.length!==1)return JSON.stringify({ok:false,reason:'Faseyha shows '+match.length+' "'+wanted+'" categories.'});
if(!H.isActive(match[0]))match[0].click();
return JSON.stringify({ok:true});""", "wanted" to name)

    private const val PACKAGE_NODES = "'button,[role=\"option\"],[role=\"radio\"],[role=\"button\"],label,article,[class*=\"package\"],[class*=\"Package\"]'"

    val PACKAGES = script("""
const raw=H.all($PACKAGE_NODES).map(x=>H.tidy(x.innerText||x.getAttribute('aria-label')||''))
  .filter(s=>s.length>=12&&s.length<=260&&/\b(gb|mb|data|minutes|days|package)\b/i.test(s))
  .filter(s=>!/(activate package|purchase now|confirm payment|pay now|checkout)/i.test(s));
const items=raw.filter((v,i,a)=>a.indexOf(v)===i).slice(0,160).map(s=>{
  const first=s.match(/^(.*?)(?=\s+(?:special offer|(?:\d+(?:\.\d+)?\s*(?:gb|mb|days))))/i);
  const title=(first?first[1]:s.split(/SPECIAL OFFER| \d+\s*(?:GB|MB)\s*\/day/i)[0]).trim().slice(0,85)||s.slice(0,85);
  const prices=[...s.matchAll(/(?:MVR|Rf|ރ)?\s*([\d,]+(?:\.\d{1,2})?)\s*incl\.?\s*GST/gi)];
  return {title:title,text:s,price:prices.length?prices[prices.length-1][1]:''};
});
return JSON.stringify({items:items});""")

    fun selectPackage(text: String) = script("""
const match=H.all($PACKAGE_NODES).filter(x=>H.tidy(x.innerText||x.getAttribute('aria-label')||'')===wanted)
  .filter(x=>!/(activate package|pay now|checkout|confirm payment|purchase now)/i.test(H.tidy(x.innerText)));
if(match.length!==1)return JSON.stringify({ok:false,reason:match.length===0?'The package is no longer shown on Faseyha.':'Faseyha shows this package more than once.'});
const node=match[0];
if(node.matches('form,[type="submit"],a[href]')||node.querySelector('button[type="submit"],a[href*="checkout"]'))
  return JSON.stringify({ok:false,reason:'The package card contains a payment control, so it was not pressed.'});
node.click();
return JSON.stringify({ok:true});""", "wanted" to text)

    fun packageSummary(number: String) = script("""
const s=H.summary();
return JSON.stringify({summary:s,hasPackage:/\bpackage\b/i.test(s),hasTotal:/total\s+to\s+pay|\btotal\b/i.test(s),
  recipient:!!number&&H.hasRecipient(s,number)});""", "number" to number)

    fun fillPackageRecipient(number: String) = script("""
const choices=H.inputs().filter(x=>!x.readOnly&&/(mobile|phone|number|7xxxxxxx|7x{4,})/.test(H.fieldLabel(x)));
if(choices.length!==1)return JSON.stringify({ok:false,count:choices.length,reason:'Found '+choices.length+' possible recipient fields on Faseyha.'});
H.setValue(choices[0],value);
const ok=H.alnum(choices[0].value)===H.alnum(value);
return JSON.stringify({ok:ok,count:1,reason:ok?'':'Faseyha did not keep the recipient number.'});""", "value" to number)

    /**
     * Fills a Recharge or Bill Pay form. Bill forms that first show only an account
     * field are handled by pressing one clearly non-payment lookup button.
     */
    fun fill(number: String, amount: String, kind: Kind, lookedUp: Boolean) = script("""
if(!H.trusted())return JSON.stringify({stage:'error',reason:'This page is not on Faseyha.'});
const fields=H.inputs(),editable=fields.filter(x=>!x.readOnly);
const numRe=/(mobile|phone|number|account|subscriber|service|7xxxxxxx)/,amtRe=/(amount|price|pay|mvr)/;
let num=editable.filter(x=>numRe.test(H.fieldLabel(x))),amt=editable.filter(x=>amtRe.test(H.fieldLabel(x)));
if((num.length!==1||amt.length!==1||num[0]===amt[0])&&editable.length===2){
  const numeric=editable.filter(x=>/amount|mvr|price/.test(H.fieldLabel(x)));
  if(numeric.length===1){amt=numeric;num=editable.filter(x=>x!==amt[0]);}else{num=[editable[0]];amt=[editable[1]];}
}
const r={n:num.length,a:amt.length,fields:editable.length};
const out=(stage,extra)=>JSON.stringify(Object.assign(r,{stage:stage},extra||{}));
if(num.length===1&&amt.length===1&&num[0]!==amt[0]){
  H.setValue(num[0],number);H.setValue(amt[0],amount);
  const ok=H.alnum(num[0].value)===H.alnum(number)&&Number(String(amt[0].value).replace(/,/g,''))===Number(amount);
  return out(ok?'filled':'mismatch',{reason:ok?'':'Faseyha changed the entered number or amount.'});
}
if(kind==='BILL'&&num.length===1&&amt.length===0){
  if(H.alnum(num[0].value)!==H.alnum(number))H.setValue(num[0],number);
  const fixed=fields.filter(x=>x.readOnly&&amtRe.test(H.fieldLabel(x))&&H.tidy(x.value));
  if(fixed.length===1){
    const shown=Number(String(fixed[0].value).replace(/[^0-9.]/g,''));
    if(shown===Number(amount))return out('filled');
    return out('provider-amount',{providerAmount:String(shown),reason:'Faseyha shows a fixed amount for this account.'});
  }
  if(lookedUp!=='true'){
    const look=H.buttons().filter(H.enabled).filter(x=>/^(check|check bill|search|find|find bill|view|view bill|get bill|fetch bill|look ?up|verify)$/i.test(H.label(x)));
    if(look.length===1){look[0].click();return out('lookup');}
  }
}
return out('wait',{reason:num.length+amt.length===0?'The Faseyha form is not visible yet.':
  'Found '+num.length+' number fields and '+amt.length+' amount fields on Faseyha; exactly one of each is needed.'});""",
        "number" to number, "amount" to amount, "kind" to kind.name, "lookedUp" to lookedUp)

    fun ready(number: String, amount: String, operator: String, action: String) = script("""
const sum=H.summary(),cands=H.actionButtons(action),on=cands.filter(H.enabled),ins=H.inputs();
return JSON.stringify({summary:sum,found:cands.length,enabled:on.length,
  numberMatch:ins.some(x=>H.alnum(x.value)===H.alnum(number)),
  amountMatch:ins.some(x=>x.value!==''&&Number(String(x.value).replace(/,/g,''))===Number(amount)),
  invalid:ins.filter(x=>x.required&&!x.checkValidity()).map(x=>H.tidy(x.getAttribute('aria-label')||x.placeholder||x.name||x.id).slice(0,60)),
  amountSeen:ins.filter(x=>/(amount|price|pay|mvr)/.test(H.fieldLabel(x))||x.type==='number').map(x=>H.tidy(x.value)).filter(Boolean).slice(0,3),
  numberEmpty:!ins.some(x=>H.tidy(x.value)!==''),
  operatorMatch:H.operatorMatches(op,action==='Pay'?'':sum),operator:H.operatorState()});""",
        "number" to number, "amount" to amount, "op" to operator, "action" to action)

    /** Presses the provider's payment button once, after re-checking every detail. */
    fun submit(path: String, number: String, amount: String, operator: String, action: String, reviewed: String) = script("""
if(!H.trusted()||location.pathname!==path)return JSON.stringify({ok:false,reason:'The Faseyha page changed'});
const text=H.body(),sum=H.summary();
if(action==='Pay'&&/(?:bill\s*)?payment\s*(?:successful|submitted)/i.test(text)&&/\b(submitted|being processed|notify you once)\b/i.test(text))
  return JSON.stringify({ok:false,reason:'Faseyha already shows a submitted bill payment. Check Wallet before paying again'});
if(reviewed&&reviewed!==sum)return JSON.stringify({ok:false,reason:'The Faseyha order summary changed after your review'});
if(action!=='Pay'&&!H.hasRecipient(sum||text,number))return JSON.stringify({ok:false,reason:'Faseyha does not show the recipient number'});
if(!H.operatorMatches(op,action==='Pay'?'':sum))return JSON.stringify({ok:false,reason:'The operator selected on Faseyha does not match'});
if(action==='Activate package'){
  if(!sum||!/\bpackage\b/i.test(sum)||!/\btotal\b/i.test(sum))return JSON.stringify({ok:false,reason:'The package summary is missing'});
}else{
  const ins=H.inputs();
  if(!ins.some(x=>H.alnum(x.value)===H.alnum(number))||!ins.some(x=>x.value!==''&&Number(String(x.value).replace(/,/g,''))===Number(amount)))
    return JSON.stringify({ok:false,reason:'The Faseyha form values changed'});
  if(action==='Recharge'&&(!sum||!/\btotal\b/i.test(sum)))return JSON.stringify({ok:false,reason:'The recharge summary is missing'});
}
const m=H.actionButtons(action).filter(H.enabled);
if(m.length!==1)return JSON.stringify({ok:false,reason:m.length===0?'The Faseyha '+action+' button is not enabled':'Faseyha shows more than one '+action+' button'});
H.snapshot();m[0].click();
return JSON.stringify({ok:true});""",
        "path" to path, "number" to number, "amount" to amount, "op" to operator, "action" to action, "reviewed" to reviewed)

    /** Reads the provider's response after the payment button was pressed. */
    fun result(number: String, amount: String, operator: String, action: String) = script("""
const want=Number(amount);
if(action==='Pay'){
  const head=/^(?:bill\s*)?payment\s*(?:successful|submitted|received)!?$/i;
  const heads=H.all('h1,h2,h3,h4,h5,[role="heading"],div,p,section,span').filter(x=>head.test(H.tidy(x.innerText||''))).slice(0,12);
  for(const node of heads){
    for(let p=node,d=0;p&&d<8;p=p.parentElement,d++){
      const s=H.tidy(p.innerText||'');if(s.length>1200)break;
      const state=/\bsubmitted\b/i.test(s)&&/being processed|notify you once|once it.s confirmed|processing/i.test(s)?'submitted':
        /\b(successful|completed|paid)\b/i.test(s)?'successful':'';
      if(state&&H.mentions(s,op)&&H.hasRecipient(s,number)&&H.amounts(s).some(v=>v===want))
        return JSON.stringify({classification:'Provider accepted payment',state:state,message:s.slice(0,300)});
    }
  }
}
const positive=H.buttons().filter(x=>/^(confirm\s*(?:&|and)\s*(?:recharge|pay|activate)|confirm\s+(?:recharge|payment|pay|activation|purchase)|confirm|yes,?\s*(?:confirm|pay|proceed)|proceed to pay)$/i.test(H.label(x)));
if(positive.length===1&&H.enabled(positive[0])){
  for(let p=positive[0].parentElement,d=0;p&&d<9;d++,p=p.parentElement){
    const s=H.tidy(p.innerText||'');if(s.length>1200)break;
    const sib=H.buttons().filter(x=>p.contains(x));
    if(sib.some(x=>/^(cancel|back|close|no)$/i.test(H.label(x)))&&/confirm|are you sure|review/i.test(s))
      return JSON.stringify({classification:'Awaiting confirmation',message:s,actionLabel:H.label(positive[0])});
  }
}
const old=H.previous();
const notices=H.all('[role="alert"],[role="status"],[aria-live],.toast,.notification,.alert,[role="dialog"]')
  .map(x=>H.tidy(x.innerText||x.textContent)).filter(x=>x&&x.length<500&&!old.has(x));
const fresh=H.body().split(/\n/).map(H.tidy).filter(x=>x&&x.length<350&&!old.has(x));
for(const line of [...new Set([...notices,...fresh])]){
  if(/insufficient (?:wallet )?(?:balance|funds)|not enough (?:balance|funds)/i.test(line))return JSON.stringify({classification:'Insufficient balance',message:line});
  if(/\b(failed|declined|unsuccessful|rejected|payment error|transaction error)\b/i.test(line))return JSON.stringify({classification:'Failed',message:line});
  if(action==='Pay'&&/(?:bill\s*)?payment\s*successful/i.test(line))continue;
  if(/\b(successful|successfully|payment completed|transaction completed|activated successfully|recharge complete)\b/i.test(line))
    return JSON.stringify({classification:'Provider reported success',message:line});
  if(/\b(processing|pending|submitted|being processed)\b/i.test(line))return JSON.stringify({classification:'Processing',message:line});
}
return JSON.stringify({classification:'Unknown'});""",
        "number" to number, "amount" to amount, "op" to operator, "action" to action)

    /** Presses the provider's own confirmation once, only if it shows the authorised details. */
    fun confirmSecond(path: String, number: String, amount: String, operator: String, expectedLabel: String) = script("""
if(!H.trusted()||location.pathname!==path)return JSON.stringify({ok:false,reason:'The Faseyha page changed.'});
const pos=H.buttons().filter(b=>H.label(b)===expectedLabel&&H.enabled(b));
if(pos.length===1){
  for(let p=pos[0].parentElement,d=0;p&&d<9;d++,p=p.parentElement){
    const s=H.tidy(p.innerText||'');if(s.length>1200)break;
    const sib=H.buttons().filter(x=>p.contains(x));
    if(sib.some(b=>/^(cancel|back|close|no)$/i.test(H.label(b)))&&H.hasRecipient(s,number)&&H.mentions(s,op)&&
       H.amountShown(s,Number(amount))&&/confirm|are you sure|review/i.test(s)){
      H.snapshot();pos[0].click();return JSON.stringify({ok:true});
    }
  }
}
return JSON.stringify({ok:false,reason:'The Faseyha confirmation is missing, changed or ambiguous.'});""",
        "path" to path, "number" to number, "amount" to amount, "op" to operator, "expectedLabel" to expectedLabel)

    fun walletTab(category: String) = script("""
const m=H.all('button,[role="tab"],[role="button"]').filter(x=>!x.disabled&&H.low(x.innerText||x.getAttribute('aria-label'))===H.low(wanted));
if(m.length!==1)return JSON.stringify({ok:false});
if(!H.isActive(m[0]))m[0].click();
return JSON.stringify({ok:true});""", "wanted" to category)

    /** Reads the balance and every transaction card that has a receipt link. */
    val WALLET_SCAN = script("""
const page=H.body();
const balance=(page.match(/available\s+balance[\s\S]{0,90}?(\d[\d,.]*)(?:\s|$)/i)||[])[1]||'';
const refs=/\b(?:FR|BLA)[A-Z0-9]{8,40}\b/i,statuses=/\b(successful|completed|processing|pending|failed|submitted)\b/i;
const dates=/\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}(?:,\s*\d{4})?(?:,\s*|\s+)\d{1,2}:\d{2}\s*(?:AM|PM)\b/i;
const currency=/^(?:MVR|MRf|Rf|ރ\.?)[ ]*([+-]?[ ]*[\d,]+(?:\.\d{1,2})?)$/i;
const rows=[],seen=new Set();
for(const a of Array.from(document.querySelectorAll('a[href]')).filter(x=>/receipt|tax invoice/i.test(H.tidy(x.innerText||x.getAttribute('aria-label')))||/\/invoice\//i.test(x.href))){
  let item=null;
  for(let p=a,d=0;p&&d<10;d++,p=p.parentElement){const t=H.tidy(p.innerText);
    if(t.length>=20&&t.length<=900&&refs.test(t)&&statuses.test(t)&&dates.test(t)){item=p;break;}}
  if(!item)continue;
  const t=H.tidy(item.innerText),reference=(t.match(refs)||[])[0]||'';
  if(!reference||seen.has(reference))continue;
  seen.add(reference);
  const at=t.indexOf(reference);
  const leaves=Array.from(item.querySelectorAll('*')).filter(x=>x.getClientRects().length&&x.children.length===0)
    .map(x=>H.tidy(x.textContent)).filter(x=>x.length&&x.length<45);
  const values=leaves.map(x=>x.match(currency)).filter(Boolean).map(x=>x[1].replace(/[^0-9.,]/g,'')).filter(Boolean);
  let amount=values.length===1?values[0]:'';
  if(!amount){const m=t.replace(reference,' ').match(/(?:^|[^A-Za-z0-9])(?:MVR|MRf|Rf|ރ\.?)[ ]*([+-]?[ ]*[\d,]+(?:\.\d{1,2})?)(?![A-Za-z0-9])/i);
    if(m)amount=m[1].replace(/[^0-9.,]/g,'');}
  let url='';try{const u=new URL(a.href);if(u.protocol==='https:'&&(u.hostname==='faseyha.net'||u.hostname.endsWith('.faseyha.net'))&&u.pathname.startsWith('/invoice/'))url=u.href;}catch(e){}
  rows.push({reference:reference,service:H.tidy(at>=0?t.slice(0,at):'').slice(0,100)||'Unknown service',
    status:(t.match(statuses)||[])[1]||'Not shown',date:(t.match(dates)||[])[0]||'',amount:amount,url:url});
}
const more=H.buttons().some(x=>/^load more$/i.test(H.label(x)));
return JSON.stringify({balance:balance,rows:rows.slice(0,500),more:more,signedOut:H.signedOut()});""")

    val LOAD_MORE = script("""
const m=H.buttons().filter(x=>!x.disabled&&/^load more$/i.test(H.label(x)));
if(m.length!==1)return JSON.stringify({ok:false});
m[0].click();return JSON.stringify({ok:true});""")

    val RECEIPT_READ = script("""
const t=H.body();
const reference=(t.match(/receipt\s*(?:no\.?|number)\s*:?\s*([A-Z0-9]{8,45})/i)||[])[1]||'';
const total=(t.match(/(?:total amount|amount paid)\s*(?:MVR|Rf|ރ|ރ\.)?\s*([\d,]+(?:\.\d{1,2})?)/i)||[])[1]||'';
const service=(t.match(/description\s+([\s\S]{0,120}?)(?:total amount|amount paid)/i)||[])[1]||'';
const billTo=(t.match(/bill to\s+([A-Z0-9-]{5,32})/i)||[])[1]||'';
const cands=(service.match(/\b[A-Z0-9-]{7,30}\b/gi)||[]).filter(x=>/\d/.test(x));
return JSON.stringify({reference:reference,account:cands.find(x=>/^BB/i.test(x))||cands[cands.length-1]||billTo,
  amount:total,description:H.tidy(service).slice(0,120),status:/\bpaid in full\b/i.test(t)?'Paid in full':'Not identified'});""")

    /** Labels and structure only; input values are reported as filled or empty. */
    val PAGE_REPORT = script("""
const st=H.operatorState();
return JSON.stringify({path:location.pathname,signedOut:H.signedOut(),
  headings:H.all('h1,h2,h3,h4,[role="heading"]').map(x=>H.tidy(x.innerText).slice(0,80)).filter(Boolean).slice(0,15),
  buttons:H.buttons().map(x=>H.opLabel(x).slice(0,60)+(H.enabled(x)?'':' [disabled]')+(H.isActive(x)?' [active]':'')).filter(Boolean).slice(0,60),
  choices:H.all('[role="tab"],[role="radio"],label').map(x=>H.opLabel(x).slice(0,60)+(H.isActive(x)?' [active]':'')).filter(Boolean).slice(0,30),
  inputs:H.inputs().map(x=>(x.type||'text')+': '+H.fieldLabel(x).slice(0,80)+(x.readOnly?' [read-only]':'')+(x.value?' [filled]':' [empty]')),
  operator:'active='+(st.active||'none')+' (count '+st.activeCount+'), headings='+st.headings.join('/')+', cards='+st.cards.join('/')+', controls='+st.controls,
  summary:H.summary().slice(0,300),
  dialogs:H.all('[role="dialog"],[role="alert"],[aria-modal="true"]').map(x=>H.tidy(x.innerText).slice(0,300)).slice(0,3)});""")
}
