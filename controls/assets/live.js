'use strict';
// This page controls equipment only through the packaged Android bridge.
const $=id=>document.getElementById(id);
// No ?? here: Android 10's stock WebView (Chrome 74) cannot parse it, and one syntax error blanks the page.
const either=(value,fallback)=>value===null||value===undefined?fallback:value;
const draft={minutes:20,temperature:115};
const look={hue:33,saturation:58,brightness:100,mode:'solid'};
let status={connected:false,armed:false}, previousRunning=false, view='home', returnFocus;
// During a session, temperature and time taps set a target that shows at once ("Setting to…"),
// is sent once taps settle, and is replaced by the panel's own report when confirmed.
// One unsent target per control (latest wins); native runs one command at a time.
const SETTLE_MS=800, SESSION_CONTROLS=['temperature','time'];
const target={time:null,temperature:null}, settle={time:null,temperature:null}, inFlight={time:null,temperature:null};
// 0.21 owner settings, stored natively. Never equipment.
const ACCENTS=[['White','#FFFFFF'],['Lime','#C6FF3D'],['Cyan','#3DE0FF'],['Amber','#F2B56B'],['Orange','#FF9900']];
const PRESETS=[
 {id:'warm',name:'Warm white',hue:36,saturation:22},{id:'cool',name:'Cool white',hue:0,saturation:0},{id:'amber',name:'Amber',hue:33,saturation:58},
 {id:'red',name:'Red',hue:2,saturation:72},{id:'pink',name:'Pink',hue:330,saturation:58},{id:'violet',name:'Violet',hue:262,saturation:62},
 {id:'blue',name:'Blue',hue:214,saturation:70},{id:'aqua',name:'Aqua',hue:177,saturation:72},{id:'green',name:'Green',hue:143,saturation:65},{id:'disco',name:'Disco'}];
let prefs={accent:'#FFFFFF',openOnWake:true,version:'0.25'};
// Only device telemetry selects the power state. Preferences and PUBACK cannot.
function selectedLight(){const r=status.lightReport,now=Date.now();return r&&typeof r.on==='boolean'&&now>=r.at&&now<r.freshUntil?r.on:null;}
function native(){return window.SteamNative||null;}
// 0.18 diagnostic timeline: what was tapped, what the page shows and what it sent. Never affects the UI.
function trace(type,fields){try{const n=native();if(n&&typeof n.trace==='function')n.trace(type,JSON.stringify(fields||{}));}catch(e){}}
let shownStatus=null;
// Smooth a single missed UDP poll. Native commands still require their own fresh reads.
// After a sustained outage, only Stop and the original-app escape remain available.
function recentStatus(){const age=Date.now()-Number(status.at);return Boolean(status.snapshot)&&age>=0&&age<20000;}
// 0.21: for 1.5 s after an automatic open everything but Stop is ignored (native refuses it too).
function guarded(){return Boolean(status.guarded);}
function send(action,value=0,minutes=0){if(native())native().command(action,value,minutes);}
// Coming back from Mr. Steam lands on the home screen, not the page that was open when leaving.
function original(){trace('tap',{control:'original'});goHome();if(native())native().originalApp();}
const TITLES={time:'Time',temperature:'Temperature',lights:'Lights',settings:'Settings',bluetooth:'Bluetooth audio'};
function openView(next){
 trace('view',{view:next});if(view==='home')returnFocus=document.activeElement;view=next;$('dashboard').dataset.view=next;$('home-view').hidden=true;$('detail-view').hidden=false;
 for(const name of Object.keys(TITLES))$(name+'-content').hidden=name!==next;
 $('light-power-detail').hidden=next!=='lights';$('light-choice-note').hidden=next!=='lights';$('detail-title').textContent=TITLES[next];$('detail-title').focus();render();
}
function goHome(){view='home';$('dashboard').dataset.view='home';$('home-view').hidden=false;$('detail-view').hidden=true;}
function closeView(){if(view==='bluetooth'){openView('settings');return;}goHome();if(returnFocus&&!returnFocus.disabled)returnFocus.focus();}
function percent(value,low,high){return high>low?Math.max(0,Math.min(100,(value-low)/(high-low)*100)):0;}
function setSlider(input,value,low,high,disabled){input.min=low;input.max=high;if(document.activeElement!==input||disabled)input.value=value;input.disabled=disabled;input.style.setProperty('--pct',percent(Number(input.value),low,high)+'%');}
function render(){
 const s=status.snapshot,known=Boolean(s),online=recentStatus(),live=online&&status.connected,running=known&&s.possiblyOn,uncertain=status.unsafeStart,guard=guarded();
 if(previousRunning&&!running&&online){draft.minutes=20;draft.temperature=115;}previousRunning=running;
 // Start/Stop own the panel while they run; temperature and time no longer lock on each other.
 const locked=!online||!status.armed||guard||(status.busy&&['start','stop'].includes(status.operation));
 if(!running)dropTargets();
 const remaining=known?s.remaining:0,minutes=running?either(target.time,remaining):draft.minutes,temp=running?either(target.temperature,s.targetF):draft.temperature;
 const maxMinutes=Math.min(60,known?s.maxMinutes:60),minF=Math.max(80,known?s.minF:80),maxF=Math.min(120,known?s.maxF:120);
 $('time-label').textContent=!live?'Last reported':running?'Remaining':'Time';$('time-value').textContent=known?minutes:'—';$('time-unit').textContent=known?'min':'';
 $('time-adjusted').textContent=known?minutes:'—';$('time-context').textContent=status.errorAction==='time'?status.error:target.time!==null?'Setting to '+target.time+' min…':running?'Reported by Steamlinx · minutes':'For your next session';
 $('target').textContent=known?temp:'—';$('target-unit').textContent=known?'°F':'';$('temperature-adjusted').textContent=known?temp:'—';$('temperature-context').textContent=status.errorAction==='temperature'?status.error:target.temperature!==null?'Setting to '+target.temperature+'°F…':running?'Set temperature':'For your next session';
 $('timer-open').dataset.running=String(running);$('timer-open').disabled=!known;$('temperature-open').disabled=!known;
 const timeLocked=locked||(running&&status.runtimeBlocked),timeLow=running?1:5;
 $('less-time').disabled=timeLocked||minutes-1<timeLow;
 $('more-time').disabled=timeLocked||minutes+1>maxMinutes;
 $('less-temp').disabled=locked||temp<=minF;$('more-temp').disabled=locked||temp>=maxF;
 setSlider($('time-slider'),known?minutes:draft.minutes,timeLow,maxMinutes,timeLocked||!known);$('time-min-label').textContent=timeLow+' min';$('time-max-label').textContent=maxMinutes+' min';
 setSlider($('temperature-slider'),known?temp:draft.temperature,minF,maxF,locked||!known);$('temp-min-label').textContent=minF+'°F';$('temp-max-label').textContent=maxF+'°F';
 // Small readouts on the home tiles: time against the session (or the 5–60 range), temperature against 80–120°F.
 const timePct=running?percent(remaining,0,Math.max(remaining,s.minutes||remaining)):percent(draft.minutes,5,60);
 $('time-mini-fill').style.width=timePct+'%';$('time-mini-knob').style.left=timePct+'%';$('temp-mini-knob').style.left=percent(known?temp:draft.temperature,80,120)+'%';
 $('time-foot').textContent=running?'Of '+(s.minutes||remaining)+' min':'For your next session';$('temp-foot').textContent=running?'Set temperature':'For your next session';
 // A Start in progress can be stopped: Stop cancels the remaining setting writes and the Start itself.
 // Stop is never locked or guarded; Start needs live controls.
 const starting=status.busy&&status.operation==='start',mustStop=running||uncertain||starting,stopping=status.busy&&status.operation==='stop';
 const steamDisabled=!known||stopping||(!mustStop&&(!status.armed||guard||!online||status.busy||s.fault!==0||s.paused||s.diagnostic||s.program!==0));
 const label=stopping?'Stopping…':mustStop?'Stop':'Start';
 for(const id of ['steam-button','steam-pill']){
  $(id).disabled=steamDisabled;$(id).dataset.running=String(mustStop);$(id).dataset.pending=String(status.busy&&['start','stop'].includes(status.operation));$(id).dataset.uncertain=String(!online||Boolean(uncertain));
 }
 $('steam-tile').dataset.running=String(mustStop);$('steam-label').textContent=label;$('pill-label').textContent=label;
 const icon=mustStop?'<rect x="6" y="6" width="12" height="12" rx="2.5" fill="currentColor" stroke="none"/>':'<path d="M12 3v8M6.3 6.8a8 8 0 1 0 11.4 0"/>';
 if($('power-icon').dataset.icon!==String(mustStop)){$('power-icon').innerHTML=icon;$('pill-icon').innerHTML=icon;$('power-icon').dataset.icon=String(mustStop);}
 $('pill-sub').textContent=!known?'':running?minutes+' min left':mustStop?'':draft.minutes+' min · '+draft.temperature+'°F';
 let steamText=!known?'Connecting to Steamlinx…':!online?'Connection lost\nUse Mr. Steam if needed':!live?'Reconnecting…':s.fault||s.paused||s.diagnostic?'Needs attention\nUse Mr. Steam':running?'Steam is on':'Steam is off';
 if(status.error&&['start','stop'].includes(status.errorAction))steamText=status.error;
 else if(!status.armed&&known&&!mustStop)steamText='Controls unavailable';else if(starting)steamText='Starting… tap Stop to cancel';else if(stopping)steamText='Waiting for device';
 $('steam-status').textContent=steamText;
 $('steam-plan').textContent=!known||!online?'':running?'Heating to '+temp+'°F':mustStop?'':'Starts '+draft.minutes+' min at '+draft.temperature+'°F';
 if(steamText!==shownStatus){shownStatus=steamText;trace('status',{text:steamText,known,online,live,armed:Boolean(status.armed),busy:Boolean(status.busy),ageMs:known?Date.now()-Number(status.at):null});}
 const light=selectedLight(),lightLocked=!status.armed||guard||status.cloudBusy||!status.cloudReady;
 for(const id of ['light-on','light-off','light-on-detail','light-off-detail']){
  const selected=light===id.startsWith('light-on');
  $(id).disabled=lightLocked;$(id).setAttribute('aria-pressed',String(selected));
 }
 const choiceLabel=status.lightPending?'Changing…':light===null?'Status unavailable':light?'On':'Off';
 $('light-state').textContent=choiceLabel;$('light-choice-note').textContent=choiceLabel;
 $('lights-error').textContent=status.lightError||'';$('lights-error').hidden=!$('lights-error').textContent;
 $('light-summary').textContent=lookName();$('light-level').textContent=look.brightness+'%';renderLights();renderSettings(known,online,live,light);
}
$('timer-open').onclick=()=>openView('time');$('temperature-open').onclick=()=>openView('temperature');$('lights-open').onclick=()=>openView('lights');$('settings-open').onclick=()=>openView('settings');$('bluetooth-open').onclick=()=>openView('bluetooth');$('back').onclick=closeView;
$('original-open').onclick=original;
function reported(control){const s=status.snapshot;return control==='time'?s.remaining:Math.round(s.targetF);}
function dropTargets(){for(const c of SESSION_CONTROLS){clearTimeout(settle[c]);settle[c]=null;target[c]=null;inFlight[c]=null;}}
// A finished command (native no longer busy) either failed, so drop the target and show the error,
// or was confirmed by readback, so drop it unless newer taps changed it meanwhile.
function settleInFlight(){
 if(status.busy)return;
 for(const c of SESSION_CONTROLS){
  if(inFlight[c]===null)continue;
  trace('settled',{control:c,sent:inFlight[c],ok:!(status.errorAction===c&&status.error),reported:status.snapshot?reported(c):null});
  if(status.errorAction===c&&status.error){target[c]=null;}else if(target[c]===inFlight[c])target[c]=null;
  inFlight[c]=null;
 }
}
function flushTargets(){
 const s=status.snapshot;if(!native()||!recentStatus()||!status.armed||guarded()||status.busy||!s||!s.possiblyOn)return;
 for(const c of SESSION_CONTROLS){
  if(target[c]===null||settle[c]||inFlight[c]!==null)continue;
  if(target[c]===reported(c)){trace('drop',{control:c,target:target[c],why:'already reported'});target[c]=null;continue;}
  if(c==='time'&&status.runtimeBlocked){trace('drop',{control:c,target:target[c],why:'time locked'});target[c]=null;continue;}
  inFlight[c]=target[c];trace('send',{control:c,value:target[c],why:'flush'});send(c,target[c]);
  // Pull the native state now so this command reads as busy; a refused command is retried later.
  try{status=JSON.parse(native().state());}catch(e){}
  if(!status.busy){trace('send.refused',{control:c,value:inFlight[c]});inFlight[c]=null;}
  return;
 }
}
function scheduleSettle(control){clearTimeout(settle[control]);settle[control]=setTimeout(()=>{settle[control]=null;trace('settle',{control,target:target[control]});flushTargets();render();},SETTLE_MS);}
function adjustSession(control,delta,low,high){
 const base=either(target[control],reported(control));target[control]=Math.max(low,Math.min(high,base+delta));
 trace('tap',{control,delta,target:target[control],reported:reported(control),session:true});
 scheduleSettle(control);render();
}
// A slider sets an absolute target. During a session it goes through the same settle, so a drag sends one value.
function slideSession(control,value,low,high){
 target[control]=Math.max(low,Math.min(high,value));
 trace('slide',{control,target:target[control],reported:reported(control),session:true});
 scheduleSettle(control);render();
}
function ready(control,detail){if(!recentStatus()||!status.armed||guarded()){trace('tap',Object.assign({control,ignored:guarded()?'guard':!status.armed?'locked':'stale status'},detail));return false;}return true;}
function adjustTime(delta){
 if(!ready('time',{delta}))return;const s=status.snapshot;
 if(s.possiblyOn)adjustSession('time',delta,1,Math.min(60,s.maxMinutes));
 else{draft.minutes=Math.max(5,Math.min(60,s.maxMinutes,draft.minutes+delta));trace('tap',{control:'time',delta,draft:draft.minutes});render();}
}
$('less-time').onclick=()=>adjustTime(-1);$('more-time').onclick=()=>adjustTime(1);
function adjustTemp(delta){
 if(!ready('temperature',{delta}))return;const s=status.snapshot;
 if(s.possiblyOn)adjustSession('temperature',delta,Math.max(80,s.minF),Math.min(120,s.maxF));
 else{draft.temperature=Math.max(80,s.minF,Math.min(120,s.maxF,draft.temperature+delta));trace('tap',{control:'temperature',delta,draft:draft.temperature});render();}
}
$('less-temp').onclick=()=>adjustTemp(-1);$('more-temp').onclick=()=>adjustTemp(1);
$('time-slider').oninput=event=>{
 const value=Number(event.target.value);if(!ready('time',{value})){render();return;}const s=status.snapshot;
 if(s.possiblyOn)slideSession('time',value,1,Math.min(60,s.maxMinutes));
 else{draft.minutes=Math.max(5,Math.min(60,s.maxMinutes,value));trace('slide',{control:'time',draft:draft.minutes});render();}
};
$('temperature-slider').oninput=event=>{
 const value=Number(event.target.value);if(!ready('temperature',{value})){render();return;}const s=status.snapshot;
 if(s.possiblyOn)slideSession('temperature',value,Math.max(80,s.minF),Math.min(120,s.maxF));
 else{draft.temperature=Math.max(80,s.minF,Math.min(120,s.maxF,value));trace('slide',{control:'temperature',draft:draft.temperature});render();}
};
for(const id of ['light-on','light-on-detail'])$(id).onclick=()=>applyLight(true);
for(const id of ['light-off','light-off-detail'])$(id).onclick=()=>applyLight(false);
function steamTap(){if((status.snapshot&&status.snapshot.possiblyOn)||status.unsafeStart||(status.busy&&status.operation==='start')){trace('tap',{control:'steam',action:'stop',pending:SESSION_CONTROLS.filter(c=>target[c]!==null).join(',')});dropTargets();send('stop');}else{if(guarded()){trace('tap',{control:'steam',action:'start',ignored:'guard'});return;}trace('tap',{control:'steam',action:'start',temperature:draft.temperature,minutes:draft.minutes});send('start',draft.temperature,draft.minutes);if(status.cloudReady&&!status.cloudBusy)applyLight(true);}}
$('steam-button').onclick=steamTap;$('steam-pill').onclick=steamTap;
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&view!=='home')closeView();});
function refresh(){if(native()){try{status=JSON.parse(native().state());}catch(e){status.connected=false;}}settleInFlight();flushTargets();render();}

// Settings: accent and open on wake are stored natively; the page only shows and asks.
function rgb(hex){const n=parseInt(hex.slice(1),16);return [(n>>16)&255,(n>>8)&255,n&255];}
function rgba(hex,a){const c=rgb(hex);return 'rgba('+c[0]+','+c[1]+','+c[2]+','+a+')';}
function applyAccent(hex){
 const c=rgb(hex),light=(0.2126*c[0]+0.7152*c[1]+0.0722*c[2])/255>0.8,root=document.documentElement.style;
 // A light accent would put white knobs on a white track: those turn black instead.
 root.setProperty('--accent',hex);root.setProperty('--accent-text',hex);root.setProperty('--glow',rgba(hex,.6));root.setProperty('--glow-soft',rgba(hex,.2));root.setProperty('--fill-soft',rgba(hex,.2));
 root.setProperty('--on-accent',light?'#000':'#1a1206');root.setProperty('--knob',light?'#000':'#fff');root.setProperty('--switch-knob',light?'#000':'#fff');
 root.setProperty('--knob-shadow',light?'0 0 0 3px '+hex+',0 0 14px rgba(255,255,255,.4)':'0 0 14px rgba(255,255,255,.55)');
}
function loadPrefs(){try{const n=native();if(n&&typeof n.settings==='function'){const p=JSON.parse(n.settings());if(p&&typeof p==='object')prefs=Object.assign({},prefs,p);}}catch(e){}if(!ACCENTS.some(a=>a[1]===prefs.accent))prefs.accent='#FFFFFF';applyAccent(prefs.accent);restoreLook(prefs.look);}
// 0.22: the light reports only on or off, so after a wake the page shows the last look chosen here (stored natively).
function restoreLook(l){
 if(!l||typeof l!=='object')return;const h=Number(l.hue),s=Number(l.saturation),b=Number(l.brightness);
 if(!(Number.isInteger(h)&&h>=0&&h<360&&Number.isInteger(s)&&s>=0&&s<=100&&Number.isInteger(b)&&b>=1&&b<=100))return;
 look.hue=h;look.saturation=s;look.brightness=b;look.mode=l.mode==='disco'?'disco':'solid';
}
function saveLook(){try{const n=native();if(n&&typeof n.setLook==='function')n.setLook(look.hue,look.saturation,look.brightness,look.mode==='disco');}catch(e){}}
function chooseAccent(hex){
 const n=native();let ok=true;
 try{if(n&&typeof n.setAccent==='function')ok=n.setAccent(hex)===true;}catch(e){ok=false;}
 trace('tap',{control:'accent',value:hex,ok});if(!ok)return;prefs.accent=hex;applyAccent(hex);render();
}
function toggleWake(){
 const on=!prefs.openOnWake;trace('tap',{control:'openOnWake',on});
 try{const n=native();if(n&&typeof n.setOpenOnWake==='function')n.setOpenOnWake(on);}catch(e){return;}
 prefs.openOnWake=on;render();
}
for(const [name,hex] of ACCENTS){const b=document.createElement('button');b.className='accent';b.dataset.accent=hex;b.setAttribute('aria-label',name);b.style.background=hex;b.onclick=()=>chooseAccent(hex);$('accents').appendChild(b);}
$('wake-toggle').onclick=toggleWake;
function renderSettings(known,online,live,light){
 for(const b of document.querySelectorAll('[data-accent]'))b.setAttribute('aria-pressed',String(b.dataset.accent===prefs.accent));
 $('accent-name').textContent=(ACCENTS.find(a=>a[1]===prefs.accent)||ACCENTS[0])[0];
 $('wake-toggle').setAttribute('aria-checked',String(Boolean(prefs.openOnWake)));
 $('steam-conn').dataset.ok=String(live);$('steam-conn-text').textContent=!known?'Connecting…':live?'Connected':online?'Reconnecting…':'Not connected';
 $('light-conn').dataset.ok=String(Boolean(status.cloudReady));$('light-conn-text').textContent=status.cloudReady?(light===null?'Connected · status unavailable':'Connected'):status.cloudBusy?'Connecting…':'Not connected';
 $('version-label').textContent='Mr. Bear Steam '+(prefs.version||'0.25');
}

function hsvRgb(hue,saturation){
  const h=hue/60,c=saturation,x=c*(1-Math.abs(h%2-1)),m=1-c;
  const channels=h<1?[c,x,0]:h<2?[x,c,0]:h<3?[0,c,x]:h<4?[0,x,c]:h<5?[x,0,c]:[c,0,x];
  return channels.map(v=>Math.round((v+m)*255));
}
function paintWheel(){
  const canvas=$('wheel-canvas'),context=canvas.getContext('2d'),size=canvas.width,center=size/2,radius=size/2;
  const pixels=context.createImageData(size,size);
  for(let y=0;y<size;y++)for(let x=0;x<size;x++){
    const dx=x-center,dy=y-center,distance=Math.hypot(dx,dy),offset=(y*size+x)*4;
    if(distance>radius)continue;
    const hue=(Math.atan2(dy,dx)*180/Math.PI+450)%360,rgb=hsvRgb(hue,distance/radius);
    pixels.data[offset]=rgb[0];pixels.data[offset+1]=rgb[1];pixels.data[offset+2]=rgb[2];pixels.data[offset+3]=255;
  }
  context.putImageData(pixels,0,0);
}
function lightLocked(){return !status.armed||guarded()||status.cloudBusy;}
// A wheel touch picks a custom solid color (it also leaves Disco).
function updateWheel(event){
  if(lightLocked())return;
  const rect=$('color-wheel').getBoundingClientRect(),x=event.clientX-rect.left-rect.width/2,y=event.clientY-rect.top-rect.height/2;
  const radius=rect.width/2,distance=Math.hypot(x,y);look.mode='solid';
  if(distance>2)look.hue=Math.round((Math.atan2(y,x)*180/Math.PI+450)%360)%360;
  look.saturation=clamp(Math.round(distance/radius*100),0,100);render();
}
const wheel=$('color-wheel');let wheelPointer=null;
wheel.onpointerdown=event=>{
  if(lightLocked()||event.button!==0||!event.isPrimary)return;
  wheelPointer=event.pointerId;wheel.setPointerCapture(wheelPointer);updateWheel(event);
};
wheel.onpointermove=event=>{if(event.pointerId!==wheelPointer)return;updateWheel(event);};
wheel.onpointerup=event=>{if(event.pointerId!==wheelPointer)return;updateWheel(event);wheelPointer=null;commitLook();};
wheel.onpointercancel=()=>{wheelPointer=null;};wheel.onlostpointercapture=()=>{wheelPointer=null;};
wheel.onkeydown=event=>{
  if(lightLocked())return;
  if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(event.key))return;event.preventDefault();look.mode='solid';
  if(event.key==='ArrowLeft')look.hue=(look.hue+355)%360;if(event.key==='ArrowRight')look.hue=(look.hue+5)%360;
  if(event.key==='ArrowUp')look.saturation=clamp(look.saturation+5,0,100);if(event.key==='ArrowDown')look.saturation=clamp(look.saturation-5,0,100);
  if(event.key==='Home')look.saturation=0;if(event.key==='End')look.saturation=100;render();commitLook();
};

const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
function colorName(){if(look.saturation<8)return 'White';if(look.saturation<35)return 'Soft color';const h=look.hue;return h<15||h>=345?'Red':h<44?'Orange':h<76?'Yellow':h<155?'Green':h<195?'Aqua':h<260?'Blue':h<295?'Violet':'Pink';}
function hexColor(){return '#'+hsvRgb(look.hue,look.saturation/100).map(v=>v.toString(16).padStart(2,'0')).join('');}
function presetHex(p){return '#'+hsvRgb(p.hue,p.saturation/100).map(v=>v.toString(16).padStart(2,'0')).join('');}
// The dot whose color the current look matches, if any. The ring marks the color last chosen here.
function selectedPreset(){if(look.mode==='disco')return 'disco';const p=PRESETS.find(p=>p.id!=='disco'&&(p.saturation===0?look.saturation===0:Math.abs(p.hue-look.hue)<=1&&Math.abs(p.saturation-look.saturation)<=1));return p?p.id:null;}
function lookName(){const id=selectedPreset();return id?PRESETS.find(p=>p.id===id).name:colorName();}
function applyLight(on){trace('tap',{control:'light',on,color:hexColor(),brightness:look.brightness,mode:look.mode});if(native()&&status.armed&&!guarded()&&status.cloudReady&&!status.cloudBusy){if(native().light(on,hexColor(),look.brightness,look.mode==='disco')===true)refresh();}}
// Every finished choice is remembered, light on or off; it is sent only while the light reports On.
function commitLook(){saveLook();if(selectedLight()===true)applyLight(true);}
function choosePreset(p){
 if(lightLocked()){trace('tap',{control:'color',preset:p.id,ignored:guarded()?'guard':'locked'});return;}
 if(p.id==='disco')look.mode='disco';else{look.mode='solid';look.hue=p.hue;look.saturation=p.saturation;}
 trace('tap',{control:'color',preset:p.id});render();commitLook();
}
for(const p of PRESETS){
 const b=document.createElement('button');b.className='swatch';b.dataset.preset=p.id;if(p.id==='disco')b.id='disco-mode';
 const dot=document.createElement('i');if(p.id==='disco')dot.className='disco';else dot.style.background=presetHex(p);
 const name=document.createElement('span');name.textContent=p.name;b.appendChild(dot);b.appendChild(name);b.onclick=()=>choosePreset(p);$('swatches').appendChild(b);
}
function renderLights(){
 const locked=lightLocked(),preset=selectedPreset();
 document.documentElement.style.setProperty('--color',hexColor());$('color-dot').classList.toggle('disco',look.mode==='disco');
 for(const b of document.querySelectorAll('[data-preset]')){b.disabled=locked;b.setAttribute('aria-pressed',String(b.dataset.preset===preset));}
 $('color-wheel').dataset.disco=String(look.mode==='disco');$('wheel-label').textContent=look.mode==='disco'?'Disco is on · tap the wheel for a color':'Tap or drag';
 const radians=(look.hue-90)*Math.PI/180,radius=look.saturation*.5;$('wheel-thumb').style.left=(50+Math.cos(radians)*radius)+'%';$('wheel-thumb').style.top=(50+Math.sin(radians)*radius)+'%';
 $('color-wheel').disabled=locked;$('brightness').disabled=locked;
 if(document.activeElement!==$('brightness'))$('brightness').value=look.brightness;$('brightness').style.setProperty('--pct',look.brightness+'%');$('brightness-value').textContent=look.brightness+'%';
}
$('brightness').oninput=event=>{look.brightness=Number(event.target.value);render();};$('brightness').onchange=commitLook;
loadPrefs();paintWheel();setInterval(refresh,350);refresh();
