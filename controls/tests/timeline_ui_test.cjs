// 0.18: the page reports taps, settle, send and what the status line shows, and tracing never changes the UI.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require(process.env.STEAM_JSDOM_PATH || 'jsdom');
const root = path.resolve(__dirname, '..');
let checks = 0;
function check(value, message) { assert.ok(value, message); checks++; }
function page(nativeExtras) {
 const dom = new JSDOM(fs.readFileSync(path.join(root, 'assets/index.html'), 'utf8'), { runScripts: 'outside-only', url: 'https://appassets.androidplatform.net/index.html' });
 const w = dom.window, calls = [], traces = [];
 const state = { connected: true, at: Date.now(), armed: true, busy: false, cloudBusy: false, cloudReady: true,
  snapshot: { possiblyOn: true, remaining: 10, targetF: 110, maxMinutes: 60, minF: 80, maxF: 120, fault: 0, program: 0, lightOn: false } };
 w.SteamNative = { state: () => JSON.stringify(state), command: (...a) => { calls.push(a); state.busy = true; state.operation = a[0]; },
  light: (...a) => { calls.push(['light', ...a]); return true; }, originalApp: () => calls.push(['original']),
  trace: (type, fields) => traces.push({ type, ...JSON.parse(fields) }), ...nativeExtras };
 w.HTMLCanvasElement.prototype.getContext = () => ({ createImageData: (x, y) => ({ data: new Uint8ClampedArray(x * y * 4) }), putImageData() {} });
 let refresh; w.setInterval = fn => { refresh = fn; return 1; };
 const timers = new Map(); let id = 0;
 w.setTimeout = fn => { timers.set(++id, fn); return id; }; w.clearTimeout = t => timers.delete(t);
 const flush = () => { const due = [...timers.values()]; timers.clear(); due.forEach(fn => fn()); };
 w.eval(fs.readFileSync(path.join(root, 'assets/live.js'), 'utf8'));
 return { w, state, calls, traces, el: i => w.document.getElementById(i), refresh: () => refresh(), flush };
}
const p = page();
check(p.traces.some(t => t.type === 'status' && t.text === 'Steam is on'), 'The first status line shown is reported');
const before = p.traces.length; p.refresh(); p.refresh();
check(p.traces.length === before, 'An unchanged status line is not reported again');
p.el('more-temp').click(); p.el('more-temp').click(); p.el('more-temp').click();
const taps = p.traces.filter(t => t.type === 'tap' && t.control === 'temperature');
check(taps.length === 3 && taps.map(t => t.target).join() === '111,112,113' && taps.every(t => t.delta === 1 && t.session === true && t.reported === 110),
 'Each tap reports its control, delta, the target shown and the panel value');
check(!p.traces.some(t => t.type === 'send'), 'Nothing is reported sent before taps settle');
p.flush();
const settleAt = p.traces.findIndex(t => t.type === 'settle'), sendAt = p.traces.findIndex(t => t.type === 'send');
check(settleAt >= 0 && p.traces[settleAt].target === 113 && sendAt > settleAt && p.traces[sendAt].value === 113 && p.traces[sendAt].why === 'flush',
 'Settle then one send of the final target');
check(p.calls.length === 1 && p.calls[0][0] === 'temperature' && p.calls[0][1] === 113, 'Tracing does not change what is sent');
p.state.busy = false; p.state.operation = ''; p.state.snapshot = { ...p.state.snapshot, targetF: 113 }; p.refresh();
const settled = p.traces.find(t => t.type === 'settled');
check(settled && settled.control === 'temperature' && settled.sent === 113 && settled.ok === true && settled.reported === 113, 'The readback outcome is reported');
p.state.connected = false; p.refresh();
const lost = p.traces.filter(t => t.type === 'status').at(-1);
check(lost.text === 'Reconnecting…' && lost.online === true && lost.live === false && typeof lost.ageMs === 'number', 'Reconnecting… is reported with why');
p.state.at = Date.now() - 25000; p.refresh();
check(p.traces.filter(t => t.type === 'status').at(-1).text === 'Connection lost\nUse Mr. Steam if needed', 'Connection lost is reported');
// The button is normally disabled by then; this is the tap that lands between a render and the status aging out.
p.el('more-time').disabled = false; p.el('more-time').click();
check(p.traces.at(-1).type === 'tap' && p.traces.at(-1).ignored === 'stale status' && p.calls.length === 1, 'An ignored tap says why and sends nothing');
p.state.at = Date.now(); p.state.connected = true; p.refresh();
p.el('steam-button').click();
check(p.traces.some(t => t.type === 'tap' && t.control === 'steam' && t.action === 'stop') && p.calls.at(-1)[0] === 'stop', 'Stop tap reported, Stop sent');
p.el('light-on').click();
check(p.traces.some(t => t.type === 'tap' && t.control === 'light' && t.on === true && /^#[0-9a-f]{6}$/.test(t.color)), 'Light tap reports the colour');
p.el('lights-open').click();
check(p.traces.some(t => t.type === 'view' && t.view === 'lights'), 'Detail views are reported');
// A failing trace hook must not break the page; an absent one must be skipped.
const broken = page({ trace: () => { throw new Error('bridge gone'); } });
broken.el('more-temp').click(); broken.flush();
check(broken.calls.length === 1 && broken.calls[0][1] === 111, 'A throwing trace hook leaves controls working');
const legacy = page({ trace: undefined });
legacy.el('more-temp').click(); legacy.flush();
check(legacy.calls.length === 1 && legacy.traces.length === 0, 'No trace hook: controls work, nothing traced');
check(!JSON.stringify(p.traces).match(/\d{1,3}(\.\d{1,3}){3}|@/), 'No addresses in page events');
console.log(`Timeline UI checks: ${checks} passed`);
