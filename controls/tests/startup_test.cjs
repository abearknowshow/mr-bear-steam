// Startup/re-entry regression: render the shipped dashboard with a recording native bridge.
// This checks JavaScript behavior only; it cannot prove Android/vendor lifecycle behavior.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require(process.env.STEAM_JSDOM_PATH || 'jsdom');
const assets = process.argv[2] || path.resolve(__dirname, '../assets');
const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
const script = fs.readFileSync(path.join(assets, 'live.js'), 'utf8');
let checks = 0;
for (const lightOn of [false, true]) {
  const calls = [];
  let state = { armed: false, connected: false, cloudReady: false, cloudBusy: false };
  let refresh;
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://appassets.androidplatform.net/index.html' });
  const w = dom.window;
  w.SteamNative = {
    state: () => JSON.stringify(state),
    command: (...args) => calls.push(['command', ...args]),
    light: (...args) => calls.push(['light', ...args]),
    originalApp: () => calls.push(['original']),
  };
  w.HTMLCanvasElement.prototype.getContext = () => ({
    createImageData: (x, y) => ({ data: new Uint8ClampedArray(x * y * 4) }),
    putImageData() {},
  });
  w.setInterval = fn => { refresh = fn; return 1; };
  w.eval(script);
  assert.equal(calls.length, 0, 'Opening the dashboard must not send a control command'); checks++;
  state = { ...state, connected: true, at: Date.now(), busy: false,
    snapshot: { possiblyOn: false, remaining: 0, targetF: 115, maxMinutes: 60,
      minF: 80, maxF: 120, fault: 0, program: 0, lightOn } };
  for (const accountState of [
    { cloudBusy: true, cloudReady: false },
    { cloudBusy: false, cloudReady: true },
    { cloudBusy: false, cloudReady: false },
  ]) {
    Object.assign(state, accountState); refresh();
    assert.equal(calls.length, 0, 'Account restoration/status changes must not send light commands'); checks++;
    for (const id of ['steam-button', 'light-on', 'light-off']) {
      assert.equal(w.document.getElementById(id).disabled, true, 'Equipment controls must stay disabled while locked'); checks++;
      w.document.getElementById(id).click();
    }
    assert.equal(calls.length, 0, 'Clicks on locked controls must not reach the native command bridge'); checks++;
  }
  for (const id of ['timer-open', 'temperature-open', 'lights-open', 'bluetooth-open']) {
    w.document.getElementById(id).click();
    w.document.getElementById('back').click();
    assert.equal(calls.length, 0, 'Opening/closing control details must not change equipment'); checks++;
  }
  state.armed = true; refresh();
  state.snapshot.possiblyOn = true; refresh();
  state.snapshot.possiblyOn = false; refresh();
  state.armed = false; refresh();
  assert.equal(calls.length, 0, 'Enabling, reported state changes and locking must not send commands'); checks++;
  dom.window.close();
}
console.log(`Dashboard startup/re-entry checks: ${checks} passed; no equipment access`);
