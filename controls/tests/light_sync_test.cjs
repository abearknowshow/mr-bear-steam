// Real reported state wins over saved intent, including external changes and reentry.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require(process.env.STEAM_JSDOM_PATH || 'jsdom');
const assets=process.argv[2]||path.resolve(__dirname,'../assets');
const dom=new JSDOM(fs.readFileSync(path.join(assets,'index.html'),'utf8'),{runScripts:'outside-only',url:'https://appassets.androidplatform.net/index.html'});
const w=dom.window,calls=[],state={armed:true,cloudReady:true,cloudBusy:false,lightSelection:true,lightChoiceSource:'saved',connected:false};
let refresh,checks=0;
const el=id=>w.document.getElementById(id);
function check(v,m){assert.ok(v,m);checks++;}
function selection(on){
 for(const suffix of ['','-detail']){
  check(el('light-on'+suffix).getAttribute('aria-pressed')===String(on===true),'On reflects telemetry');
  check(el('light-off'+suffix).getAttribute('aria-pressed')===String(on===false),'Off reflects telemetry');
 }
}
function report(on){state.lightReport={on,at:Date.now(),freshUntil:Date.now()+120000};refresh();}
w.SteamNative={state:()=>JSON.stringify(state),light:(on,...args)=>{calls.push([on,...args]);state.lightSelection=on;state.lightPending=state.cloudBusy=true;return true;},command:()=>calls.push('steam'),originalApp:()=>{}};
w.HTMLCanvasElement.prototype.getContext=()=>({createImageData:(x,y)=>({data:new Uint8ClampedArray(x*y*4)}),putImageData(){}});
w.setInterval=fn=>{refresh=fn;return 1;};w.eval(fs.readFileSync(path.join(assets,'live.js'),'utf8'));
selection(null);check(calls.length===0,'No commands on launch');
report(false);selection(false);check(el('light-state').textContent==='Off','Actual Off is plain Off');
el('light-on').click();selection(false);check(calls.length===1,'Explicit On sent once');
state.cloudBusy=state.lightPending=false;state.lightDelivered=true;refresh();selection(false);
check(el('light-state').textContent==='Off','Broker receipt does not invent On');
report(true);selection(true);check(el('light-state').textContent==='On','Device report confirms On');
el('lights-open').click();selection(true);el('back').click();
report(false);selection(false);check(calls.length===1,'External Off updates both views without a command');
el('disco-mode').click();check(calls.length===1,'Editing a look while actually Off does not turn it on');
report(true);w.document.querySelector('[data-preset="amber"]').click();check(calls.length===2,'Color edits apply when device is actually On');
state.cloudBusy=state.lightPending=false;
state.lightReport.freshUntil=Date.now()-1;refresh();selection(null);
state.lightReport={on:true,at:Date.now()+10000,freshUntil:Date.now()+120000};refresh();selection(null);
state.lightReport=null;state.armed=false;refresh();selection(null);
check(calls.length===2,'Reentry, expiry and locking never send commands');
check(!/Last used|Selected here/.test(el('light-state').textContent),'No history workaround');
dom.window.close();console.log(`Actual light synchronization UI checks: ${checks} passed`);
