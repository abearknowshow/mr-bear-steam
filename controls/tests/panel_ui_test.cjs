// 0.21-0.22 page: the guard after an automatic open, Stop while locked, sliders, the header steam button,
// color dots, owner settings and the remembered light look (0.22). Fake native bridge only; no network or equipment access.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require(process.env.STEAM_JSDOM_PATH || 'jsdom');
const root = path.resolve(__dirname, '..');
let checks = 0;
function check(value, message) { assert.ok(value, message); checks++; }
const html = fs.readFileSync(path.join(root, 'assets/index.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'assets/live.js'), 'utf8');
function page(stored = { accent: '#FFFFFF', openOnWake: true, version: '0.22' }, nativeExtras = {}) {
 const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://appassets.androidplatform.net/index.html' });
 const w = dom.window, calls = [], saved = [];
 const state = { connected: true, at: Date.now(), armed: true, guarded: false, busy: false, cloudBusy: false, cloudReady: true,
  snapshot: { possiblyOn: false, remaining: 0, minutes: 0, targetF: 115, maxMinutes: 60, minF: 80, maxF: 120, fault: 0, program: 0, lightOn: false } };
 w.SteamNative = { state: () => JSON.stringify(state), command: (...a) => { calls.push(a); state.busy = true; state.operation = a[0]; },
  light: (...a) => { calls.push(['light', ...a]); return true; }, originalApp: () => calls.push(['original']),
  settings: () => JSON.stringify(stored),
  setAccent: v => { saved.push(['accent', v]); const ok = ['#FFFFFF', '#C6FF3D', '#3DE0FF', '#F2B56B', '#FF9900'].includes(v); if (ok) stored.accent = v; return ok; },
  setOpenOnWake: on => { saved.push(['openOnWake', on]); stored.openOnWake = on; },
  setLook: (hue, saturation, brightness, disco) => { saved.push(['look', hue, saturation, brightness, disco]); stored.look = { hue, saturation, brightness, mode: disco ? 'disco' : 'solid' }; return true; }, ...nativeExtras };
 w.HTMLCanvasElement.prototype.getContext = () => ({ createImageData: (x, y) => ({ data: new Uint8ClampedArray(x * y * 4) }), putImageData() {} });
 let refresh; w.setInterval = fn => { refresh = fn; return 1; };
 const timers = new Map(); let id = 0;
 w.setTimeout = fn => { timers.set(++id, fn); return id; }; w.clearTimeout = t => timers.delete(t);
 const flush = () => { const due = [...timers.values()]; timers.clear(); due.forEach(fn => fn()); };
 w.eval(script);
 const el = i => w.document.getElementById(i);
 const finish = (patch = {}) => { state.busy = false; state.operation = ''; Object.assign(state, patch); refresh(); };
 const slide = (input, value) => { input.value = String(value); input.dispatchEvent(new w.Event('input')); };
 return { w, dom, state, calls, saved, stored, el, refresh: () => refresh(), flush, finish, slide };
}

// Android 10's stock WebView is Chrome 74: ?? and ?. are syntax errors there and would blank the page.
const code = script.split('\n').map(line => line.replace(/^\s*\/\/.*$/, '')).join('\n');
check(!/\?\?/.test(code) && !/\?\.[A-Za-z_$(\[]/.test(code), 'No ?? or ?. in the page script (Chrome 74 cannot parse them)');

// No top bar or title; the home grid has its five tiles.
let p = page();
check(!p.w.document.querySelector('.topbar,.wordmark') && !/A Bear Knows Steam|Test controls/.test(p.w.document.body.textContent), 'No top bar, title or test-controls label');
for (const id of ['steam-button', 'timer-open', 'temperature-open', 'lights-open', 'settings-open']) check(p.el(id) && !p.el(id).closest('[hidden]'), id + ' is on the home screen');
check(p.calls.length === 0 && p.saved.length === 0, 'Loading the page sends no command and changes no setting');
check(p.el('steam-plan').textContent === 'Starts 20 min at 115°F', 'Home says what Start will do');

// The guard after an automatic open: Start, time, temperature and lights are ignored; Stop is not.
p.state.guarded = true; p.refresh();
check(p.el('steam-button').disabled && p.el('steam-pill').disabled, 'Start is unavailable during the guard');
for (const id of ['less-time', 'more-time', 'less-temp', 'more-temp', 'light-on', 'light-off', 'time-slider', 'temperature-slider', 'brightness', 'color-wheel'])
 check(p.el(id).disabled, id + ' is unavailable during the guard');
for (const id of ['steam-button', 'steam-pill', 'more-time', 'more-temp', 'light-on']) { p.el(id).disabled = false; p.el(id).click(); }
p.w.document.querySelector('[data-preset="blue"]').disabled = false; p.w.document.querySelector('[data-preset="blue"]').click();
p.slide(p.el('time-slider'), 40); p.flush();
check(p.calls.length === 0, 'A tap that lands during the guard sends nothing (even if a button was enabled)');
check(p.el('time-adjusted').textContent === '20' && p.el('temperature-adjusted').textContent === '115' && p.el('light-summary').textContent === 'Amber', 'A tap during the guard does not change the next session or the color either');
p.state.snapshot = { ...p.state.snapshot, possiblyOn: true, remaining: 12, minutes: 20 }; p.refresh();
check(!p.el('steam-button').disabled && !p.el('steam-pill').disabled, 'Stop is available during the guard');
p.el('steam-button').click();
check(p.calls.length === 1 && p.calls[0][0] === 'stop', 'Stop goes out during the guard');
p.finish({ guarded: false, snapshot: { ...p.state.snapshot, possiblyOn: false, remaining: 0 } });
check(!p.el('steam-button').disabled && !p.el('more-time').disabled, 'Controls return when the guard ends');
p.dom.window.close();

// Stop works while controls are locked; Start does not.
p = page(); p.state.armed = false; p.refresh();
check(p.el('steam-button').disabled, 'Start is unavailable while locked');
p.state.snapshot = { ...p.state.snapshot, possiblyOn: true, remaining: 9, minutes: 20 }; p.refresh();
check(!p.el('steam-button').disabled && p.el('steam-label').textContent === 'Stop', 'Stop is available while locked');
p.el('steam-pill').click();
check(p.calls.length === 1 && p.calls[0][0] === 'stop', 'The header steam button stops too');
p.dom.window.close();

// Sliders: idle edits the draft only; during a session a drag sends one absolute target after it settles.
p = page();
p.slide(p.el('time-slider'), 37);
check(p.el('time-adjusted').textContent === '37' && p.el('time-value').textContent === '37' && p.calls.length === 0, 'Idle time slider changes only the next-session draft');
p.slide(p.el('time-slider'), 2);
check(p.el('time-adjusted').textContent === '5', 'A new session is at least five minutes');
p.slide(p.el('temperature-slider'), 104);
check(p.el('temperature-adjusted').textContent === '104' && p.calls.length === 0 && p.el('steam-plan').textContent === 'Starts 5 min at 104°F', 'Idle temperature slider changes only the draft, and Start says so');
p.state.snapshot = { ...p.state.snapshot, possiblyOn: true, remaining: 10, minutes: 20, targetF: 110 }; p.refresh();
for (const v of [12, 15, 18, 22]) p.slide(p.el('time-slider'), v);
check(p.calls.length === 0 && /Setting to 22/.test(p.el('time-context').textContent), 'A drag shows the target and sends nothing yet');
p.flush();
check(p.calls.length === 1 && p.calls[0][0] === 'time' && p.calls[0][1] === 22, 'The settled drag sends one absolute time target');
p.slide(p.el('temperature-slider'), 130); p.flush();
p.state.snapshot = { ...p.state.snapshot, remaining: 22 }; p.finish();
check(p.calls.length === 2 && p.calls[1][0] === 'temperature' && p.calls[1][1] === 120, 'A temperature drag is clamped to 120°F and sent once');
check(p.el('pill-label').textContent === 'Stop' && /min left/.test(p.el('pill-sub').textContent), 'The header steam button reads Stop with the time left');
p.dom.window.close();

// Detail screens are full screen with the same header; Bluetooth returns to Settings.
p = page();
for (const [id, view] of [['timer-open', 'time'], ['temperature-open', 'temperature'], ['lights-open', 'lights'], ['settings-open', 'settings']]) {
 p.el(id).click();
 check(p.el('home-view').hidden && !p.el('detail-view').hidden && !p.el(view + '-content').hidden && !p.el('steam-pill').hidden, view + ' opens full screen with the steam button');
 p.el('back').click();
 check(!p.el('home-view').hidden, 'Back returns home from ' + view);
}
p.el('settings-open').click(); p.el('bluetooth-open').click();
check(!p.el('bluetooth-content').hidden && p.el('settings-content').hidden, 'Bluetooth opens from Settings');
p.el('back').click();
check(!p.el('settings-content').hidden, 'Back from Bluetooth returns to Settings');
p.el('original-open').click();
check(p.calls.length === 1 && p.calls[0][0] === 'original', 'Return to Mr. Steam is in Settings');
check(!p.el('home-view').hidden && p.el('detail-view').hidden, 'Leaving for Mr. Steam resets Bear to the home screen for the return');
p.dom.window.close();

// Color dots: Low/Medium/High are gone; a dot changes the look and sends only when the light is reported On.
p = page();
check(!p.w.document.querySelector('[data-brightness]') && !/Low|Medium|High/.test(p.el('lights-content').textContent), 'No brightness presets');
check(p.w.document.querySelectorAll('[data-preset]').length === 10 && !!p.el('color-wheel') && !!p.el('brightness'), 'Ten color dots, the custom wheel and the brightness slider');
check(!/last color|reports only/i.test(p.el('lights-content').textContent), 'No note about the last color sent');
check(p.el('light-summary').textContent === 'Amber' && p.w.document.querySelector('[data-preset="amber"]').getAttribute('aria-pressed') === 'true', 'Amber is the starting color');
p.w.document.querySelector('[data-preset="blue"]').click();
check(p.el('light-summary').textContent === 'Blue' && p.calls.length === 0, 'Choosing a color with no On report changes only the look');
p.state.lightReport = { on: true, at: Date.now(), freshUntil: Date.now() + 120000 }; p.refresh();
p.w.document.querySelector('[data-preset="green"]').click();
check(p.calls.length === 1 && p.calls[0][0] === 'light' && p.calls[0][1] === true && p.calls[0][4] === false, 'A color dot applies at once when the light is reported On');
p.el('disco-mode').click();
check(p.calls.at(-1)[4] === true && p.el('light-summary').textContent === 'Disco', 'Disco is a dot');
p.dom.window.close();

// Settings: accent and open on wake are stored natively, never sent to equipment.
p = page({ accent: '#FF9900', openOnWake: true, version: '0.22' });
check(p.w.document.documentElement.style.getPropertyValue('--accent') === '#FF9900', 'A stored accent applies at load');
check(p.w.document.documentElement.style.getPropertyValue('--knob') === '#fff', 'Orange keeps white knobs');
p.el('settings-open').click();
check(p.el('accent-name').textContent === 'Orange' && p.el('version-label').textContent === 'Mr. Bear Steam 0.22', 'Settings shows the accent and version');
check(/Open this experience on wake/.test(p.el('settings-content').textContent) && !/\bBear\b/.test(p.el('settings-content').textContent.replace('Mr. Bear Steam', '')), 'Settings says "this experience", not Bear');
const order = [...p.el('settings-content').querySelectorAll('.settings-list strong')].map(n => n.textContent);
check(order.join('|') === 'Accent color|Open this experience on wake|Bluetooth audio|Return to Mr. Steam', 'Settings rows are in the owner\'s order');
p.w.document.querySelector('[data-accent="#FFFFFF"]').click();
check(p.stored.accent === '#FFFFFF' && p.w.document.documentElement.style.getPropertyValue('--knob') === '#000' && p.w.document.documentElement.style.getPropertyValue('--on-accent') === '#000', 'White accent: knobs and Stop text turn black');
check(p.w.document.querySelectorAll('[data-accent]').length === 5, 'Five accents offered');
p.el('wake-toggle').click();
check(p.stored.openOnWake === false && p.el('wake-toggle').getAttribute('aria-checked') === 'false', 'Open on wake turns off');
p.el('wake-toggle').click();
check(p.stored.openOnWake === true && p.el('wake-toggle').getAttribute('aria-checked') === 'true', 'and back on');
check(p.calls.length === 0, 'Settings changes never send equipment commands');
p.dom.window.close();
// A refused accent is not shown as chosen; a bad stored value falls back to white.
p = page({ accent: '#123456' }, { setAccent: () => false });
check(p.w.document.documentElement.style.getPropertyValue('--accent') === '#FFFFFF', 'An unknown stored accent falls back to white');
p.w.document.querySelector('[data-accent="#3DE0FF"]').click();
check(p.w.document.documentElement.style.getPropertyValue('--accent') === '#FFFFFF', 'A refused accent change does not apply');
p.dom.window.close();
// Older native layers without settings still work.
p = page(undefined, { settings: undefined, setAccent: undefined, setOpenOnWake: undefined });
check(p.el('version-label').textContent === 'Mr. Bear Steam 0.22' && p.calls.length === 0, 'No settings bridge: defaults, no errors');
p.dom.window.close();
// 0.22: the light reports only on or off, so the page remembers the last look chosen here and shows it after a wake.
const looks = p => p.saved.filter(s => s[0] === 'look');
p = page();
p.el('lights-open').click();
p.w.document.querySelector('[data-preset="blue"]').click();
check(looks(p).length === 1 && p.stored.look.hue === 214 && p.stored.look.saturation === 70 && p.stored.look.mode === 'solid' && p.calls.length === 0, 'Choosing Blue with the light off remembers it and sends nothing');
p.slide(p.el('brightness'), 40); p.el('brightness').dispatchEvent(new p.w.Event('change'));
check(p.stored.look.brightness === 40 && p.stored.look.hue === 214 && p.calls.length === 0, 'A brightness change is remembered with the color');
const kept = p.stored; p.dom.window.close();
// Sleep and wake: a new page reads the same stored settings.
p = page(kept);
check(p.el('light-summary').textContent === 'Blue' && p.w.document.querySelector('[data-preset="blue"]').getAttribute('aria-pressed') === 'true' && p.w.document.querySelector('[data-preset="amber"]').getAttribute('aria-pressed') === 'false', 'After a wake the page shows Blue, not Amber');
check(p.el('brightness-value').textContent === '40%' && p.el('light-level').textContent === '40%', 'and the remembered brightness');
check(p.calls.length === 0 && looks(p).length === 0, 'Restoring the look sends nothing and stores nothing');
p.el('light-on').click();
const blue = p.calls.at(-1);
// What a fresh page sends for Blue at 40%, chosen directly.
const fresh = page(); fresh.w.document.querySelector('[data-preset="blue"]').click(); fresh.slide(fresh.el('brightness'), 40); fresh.el('light-on').click();
const expected = fresh.calls.at(-1); fresh.dom.window.close();
check(blue[0] === 'light' && blue[1] === true && /^#[0-9a-f]{6}$/i.test(blue[2]) && blue[2] === expected[2] && blue[3] === 40 && blue[4] === false, 'On after a wake sends the remembered Blue at 40%');
p.state.lightReport = { on: true, at: Date.now(), freshUntil: Date.now() + 120000 }; p.refresh();
p.w.document.querySelector('[data-preset="disco"]').click();
check(p.stored.look.mode === 'disco' && p.calls.at(-1)[4] === true, 'Disco with the light on is sent and remembered');
const disco = p.stored; p.dom.window.close();
p = page(disco);
check(p.el('light-summary').textContent === 'Disco' && p.calls.length === 0, 'Disco is shown again after a wake');
p.dom.window.close();
// The custom wheel is remembered too.
p = page(); p.el('lights-open').click(); p.el('color-wheel').focus();
p.el('color-wheel').dispatchEvent(new p.w.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
check(p.stored.look.hue === 38 && p.stored.look.saturation === 58 && looks(p).length === 1, 'A wheel change is remembered');
p.dom.window.close();
// During the guard nothing changes, so nothing is remembered.
p = page(); p.state.guarded = true; p.refresh();
p.w.document.querySelector('[data-preset="blue"]').click();
check(looks(p).length === 0 && p.stored.look === undefined, 'A color tap during the guard is not remembered');
p.dom.window.close();
// Bad or missing stored looks fall back to Amber; an older bridge without setLook still works.
for (const bad of [{ hue: 400, saturation: 70, brightness: 40 }, { hue: 214, saturation: 70, brightness: 0 }, { hue: '214px', saturation: 70, brightness: 40 }, { hue: 2.5, saturation: 70, brightness: 40 }, 'blue']) {
 p = page({ accent: '#FFFFFF', openOnWake: true, version: '0.22', look: bad });
 check(p.el('light-summary').textContent === 'Amber' && p.el('brightness-value').textContent === '100%', 'A bad stored look falls back to Amber: ' + JSON.stringify(bad));
 p.dom.window.close();
}
p = page(undefined, { setLook: undefined }); p.el('lights-open').click();
p.w.document.querySelector('[data-preset="green"]').click();
check(p.el('light-summary').textContent === 'Green' && p.calls.length === 0, 'No setLook bridge: the choice still shows, no errors');
p.dom.window.close();
console.log(`Panel 0.22 UI checks: ${checks} passed`);
