// Exercise the shipped UI with a fake native bridge. No network or equipment access.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require(process.env.STEAM_JSDOM_PATH || 'jsdom');
const root = path.resolve(__dirname, '..');
let checks = 0;
function check(value, message) { assert.ok(value, message); checks++; }
const html = fs.readFileSync(path.join(root, 'assets/index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://appassets.androidplatform.net/index.html' });
const w = dom.window, calls = [];
let state = { connected: true, at: Date.now(), armed: true, busy: false, cloudBusy: false, cloudReady: false,
 snapshot: { possiblyOn: false, remaining: 20, targetF: 115, maxMinutes: 60, minF: 80, maxF: 120, fault: 0, program: 0, lightOn: false } };
w.SteamNative = { state: () => JSON.stringify(state), command: (...args) => calls.push(args), light: (...args) => calls.push(['light', ...args]), originalApp: () => calls.push(['original']) };
w.HTMLCanvasElement.prototype.getContext = () => ({ createImageData: (x,y) => ({ data: new Uint8ClampedArray(x*y*4) }), putImageData() {} });
let refresh; w.setInterval = fn => {refresh=fn;return 1;};
// Manual timers so the test decides when batched time taps are sent.
const timers=new Map(); let timerId=0;
w.setTimeout=fn=>{timers.set(++timerId,fn);return timerId;}; w.clearTimeout=id=>timers.delete(id);
const flushTimers=()=>{const due=[...timers.values()];timers.clear();due.forEach(fn=>fn());};
w.eval(fs.readFileSync(path.join(root, 'assets/live.js'), 'utf8'));
const el = id => w.document.getElementById(id);
check(el('light-state').textContent==='Status unavailable', 'A Steamlinx light bit must not be presented as Chroma lamp state');
state.cloudReady=true;
state.lightReport={on:true,at:Date.now(),freshUntil:Date.now()+120000};refresh();
check(el('light-state').textContent==='On', 'A hardware report selects actual light state');
el('light-off').click();
check(calls.at(-1)[0]==='light'&&calls.at(-1)[1]===false, 'Explicit Off must send OFF even if the LAN bit says off');
state.connected=false;state.at=Date.now()-60000;refresh();
check(!el('light-on').disabled&&!el('light-off').disabled, 'Cloud light controls must not depend on the Steamlinx connection');
state.lightReport=null;refresh();
check(el('light-state').textContent==='Status unavailable', 'Missing cloud state must not be mislabeled as physical Off');
el('light-on').click();
check(calls.at(-1)[1]===true, 'Explicit On must send ON with unknown reported state');
state.lightReport={on:false,at:Date.now()-130000,freshUntil:Date.now()-10000};refresh();
check(el('light-state').textContent==='Status unavailable', 'Expired cloud reports must not be presented as current lamp state');
state.cloudBusy=true;refresh();
check(el('light-on').disabled&&el('light-off').disabled, 'Prevent duplicate light sends while one is pending');
state.connected=true;state.at=Date.now();state.cloudBusy=false;state.cloudReady=false;refresh();
el('lights-open').click();
check(!w.document.getElementById('light-setup'), 'Lights must not have a separate sign-in control');
check(!/Connect lights|Account connected|Sign in once/.test(el('lights-content').textContent), 'Light controls must not contain account setup or success banners');
check(el('lights-error').hidden, 'No error area shown during normal operation');
check(!el('steam-button').disabled, 'An unavailable account must not block local Start');
const beforeReconnect=calls.length;
state.connected=false;state.at=Date.now()-8000;refresh();
check(!el('steam-button').disabled, 'A single missed poll must not disable Start while recent verified state is available');
check(el('steam-status').textContent==='Reconnecting…', 'A missed poll describes reconnection instead of asserting current equipment state');
check(el('time-label').textContent==='Last reported', 'Cached time is identified as last reported during reconnection');
state.connected=true;state.at=Date.now();refresh();
check(!el('steam-button').disabled, 'Recovered polling must keep Start available without a lock/unlock cycle');
check(calls.length===beforeReconnect, 'Status updates must never issue commands');
state.connected=false;state.at=Date.now()-21000;refresh();
check(el('steam-button').disabled, 'Sustained lost status disables Start after the freshness window');
state.at=Date.now();
state.snapshot.possiblyOn = true; state.connected = false; state.cloudBusy = true; refresh();
check(!el('steam-button').disabled, 'Stop stays available during account work and a lost status connection');
el('steam-button').click(); check(calls.at(-1)[0] === 'stop', 'Stop goes directly to the native command');
el('original-open').click(); check(calls.at(-1)[0] === 'original', 'Original-app return remains independent of account state');
state.connected = true; state.cloudBusy = false; state.cloudReady = true; state.lightError = 'Light change unconfirmed. Use Mr. Steam.'; refresh();
check(!el('lights-error').hidden && el('lights-error').textContent === state.lightError, 'A real light error appears beside light controls');
state.lightError = ''; refresh();
check(el('lights-error').hidden, 'Resolved errors do not leave a persistent banner');
// Session temperature/time: the target shows at once, is sent once taps settle,
// and only the panel's readback replaces it. Idle taps edit the next-session draft.
check(el('more-time').getAttribute('aria-label')==='Add one minute'&&el('less-time').getAttribute('aria-label')==='Remove one minute', 'Time buttons step by one minute');
state.snapshot={...state.snapshot,possiblyOn:false,remaining:0}; state.connected=true; state.at=Date.now(); state.busy=false; state.unsafeStart=false; state.error=''; state.errorAction=''; refresh();
let sent=calls.length; el('more-time').click(); el('more-time').click();
check(el('time-adjusted').textContent==='22'&&calls.length===sent, 'Idle taps change only the next-session draft');
for(let i=0;i<40;i++)el('less-time').click();
check(el('time-adjusted').textContent==='5'&&el('less-time').disabled, 'A new session cannot be shorter than five minutes');
// From here the fake native layer reports busy while a steam command runs, like DashboardService.
w.SteamNative.command=(...a)=>{calls.push(a);state.busy=true;state.operation=a[0];};
const finish=(patch={})=>{state.busy=false;state.operation='';Object.assign(state,patch);refresh();};
state.snapshot={...state.snapshot,possiblyOn:true,remaining:10,targetF:110}; refresh();
sent=calls.length; el('more-time').click(); el('more-time').click(); el('more-time').click();
check(el('time-adjusted').textContent==='13'&&/Setting to 13/.test(el('time-context').textContent)&&calls.length===sent, 'Taps show the target at once and send nothing yet');
check(!el('more-time').disabled&&!el('more-temp').disabled, 'Buttons stay live while taps settle');
flushTimers();
check(calls.length===sent+1&&calls.at(-1)[0]==='time'&&calls.at(-1)[1]===13, 'Settled taps send one absolute target');
el('more-temp').click(); el('more-temp').click();
check(el('temperature-adjusted').textContent==='112'&&!el('more-temp').disabled, 'Temperature stays live while a time change is in flight');
flushTimers(); check(calls.length===sent+1, 'A second command waits for the one in flight');
state.snapshot={...state.snapshot,remaining:13}; finish();
check(calls.length===sent+2&&calls.at(-1)[0]==='temperature'&&calls.at(-1)[1]===112, 'The waiting temperature goes out when the panel is free');
check(el('time-adjusted').textContent==='13'&&!/Setting/.test(el('time-context').textContent), 'Confirmed time shows the panel report');
el('more-temp').click(); flushTimers();
state.snapshot={...state.snapshot,targetF:112}; finish();
check(calls.at(-1)[0]==='temperature'&&calls.at(-1)[1]===113, 'A newer tap made during flight is sent next (latest wins)');
state.snapshot={...state.snapshot,targetF:110}; finish({error:'Change unconfirmed. Check Mr. Steam.',errorAction:'temperature'});
check(el('temperature-adjusted').textContent==='110'&&el('temperature-context').textContent==='Change unconfirmed. Check Mr. Steam.', 'A failed change snaps back to the panel report and says so');
finish({error:'',errorAction:''});
state.snapshot={...state.snapshot,remaining:2}; refresh();
el('less-time').click(); el('less-time').click(); el('less-time').click();
check(el('time-adjusted').textContent==='1'&&el('less-time').disabled, 'A running session cannot be set below one minute');
sent=calls.length; el('steam-button').click();
check(calls.at(-1)[0]==='stop'&&calls.length===sent+1, 'Stop goes straight out');
flushTimers(); finish({}); check(calls.at(-1)[0]==='stop', 'Unsent taps are discarded by Stop, never sent after it');
el('more-time').click(); state.snapshot={...state.snapshot,possiblyOn:false,remaining:0}; refresh();
sent=calls.length; flushTimers(); refresh();
check(calls.length===sent, 'Taps pending when the session ends are discarded');
state.snapshot={...state.snapshot,possiblyOn:true,remaining:10}; state.runtimeBlocked=true; refresh();
check(el('more-time').disabled&&el('less-time').disabled, 'An unconfirmed time change locks the time controls');
// 0.20: a Start still sending its settings can be stopped from the screen (emulator, 2026-10-21: it could not).
state.runtimeBlocked=false; state.snapshot={...state.snapshot,possiblyOn:false,remaining:0}; finish({}); refresh();
sent=calls.length; el('steam-button').click();
check(calls.slice(sent).filter(c=>c[0]==='start').length===1, 'Start goes out once (Start also turns the light on through its own path)');
state.busy=true; state.operation='start'; refresh(); sent=calls.length;
check(!el('steam-button').disabled&&el('steam-label').textContent==='Stop'&&/tap Stop to cancel/.test(el('steam-status').textContent), 'A Start in progress shows an enabled Stop');
el('steam-button').click();
check(calls.length===sent+1&&calls.at(-1)[0]==='stop', 'Tapping STOP during a Start sends Stop, never a second Start');
dom.window.close();

async function setupTest(finalState) {
 const page = new JSDOM(fs.readFileSync(path.join(root,'setup.html'),'utf8'), { runScripts:'outside-only', url:'http://127.0.0.1:8776/' });
 const win = page.window, get = id => win.document.getElementById(id); let poll;
 win.setInterval = fn => {poll=fn;return 1;}; win.clearInterval = () => {};
 win.fetch = async (url, options) => {
  if (url === '/credentials') {
   const body=JSON.parse(options.body);check(body.password==='TEST-ONLY', 'Credential request uses the entered value');
   return {ok:true,json:async()=>({state:'waiting'})};
  }
  return {ok:true,json:async()=>({state:finalState})};
 };
 win.eval(fs.readFileSync(path.join(root,'setup.js'),'utf8'));
 check(!/Connect.*lights|test the lights/i.test(win.document.body.textContent), 'Mac sign-in belongs to the entire app');
 get('code').value='123456';get('email').value='fake@example.invalid';get('password').value='TEST-ONLY';
 get('signin').dispatchEvent(new win.Event('submit',{cancelable:true}));
 await new Promise(resolve=>setImmediate(resolve));
 check(get('password').value==='', 'Password cleared after submission');
 await poll();
 if(finalState==='connected') check(get('signin').hidden, 'Successful sign-in removes the form instead of inviting another login');
 else check(!get('signin').hidden&&!get('submit').disabled, 'Sign-in failure allows a retry');
 check(!/lights/i.test(get('status').textContent), 'Account result is not framed as a separate lights login');
 page.window.close();
}
(async()=>{await setupTest('connected');await setupTest('failed');console.log(`UI account/controls checks: ${checks} passed`);})().catch(error=>{console.error(error);process.exitCode=1;});
