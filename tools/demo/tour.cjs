// Films and photographs the real page (controls/assets) at the panel's size, 1024x600, with simulated equipment.
// Nothing here reaches a panel, bridge or light. Usage, from the repository root:
//   npm i --no-save playwright && npx playwright install chromium
//   node tools/demo/tour.cjs build/demo            # screenshots (2x) and tour-raw.webm
//   node tools/demo/tour.cjs build/demo shots      # or: video
const { chromium } = require(process.env.PW || 'playwright');
const path = require('path'), fs = require('fs'), os = require('os');
const HERE = __dirname, ASSETS = path.join(HERE, '..', '..', 'controls', 'assets');
const OUT = path.resolve(process.argv[2] || 'build/demo'), MODE = process.argv[3] || 'all';
const sleep = ms => new Promise(r => setTimeout(r, ms));

// The page with the simulated bridge (fake.js) and tap rings (touch.js) loaded before live.js.
function buildPage() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bear-demo-'));
  const html = fs.readFileSync(path.join(ASSETS, 'index.html'), 'utf8');
  if (html.split('<script src="live.js">').length !== 2) throw new Error('index.html must load live.js once');
  fs.writeFileSync(path.join(dir, 'index.html'), html.replace('<script src="live.js">', '<script src="fake.js"></script><script src="touch.js"></script><script src="live.js">'));
  for (const f of ['fake.js', 'touch.js']) fs.copyFileSync(path.join(HERE, f), path.join(dir, f));
  fs.copyFileSync(path.join(ASSETS, 'live.js'), path.join(dir, 'live.js'));
  return dir;
}
const PAGE = buildPage();
const url = q => 'file://' + path.join(PAGE, 'index.html') + (q ? '?' + q : '');

async function center(page, sel) { const b = await page.locator(sel).first().boundingBox(); if (!b) throw new Error('no box ' + sel); return [b.x + b.width / 2, b.y + b.height / 2]; }
async function tap(page, sel, after = 900) {
  const [x, y] = await center(page, sel);
  await page.mouse.move(x, y, { steps: 12 }); await sleep(120);
  await page.mouse.down(); await sleep(90); await page.mouse.up(); await sleep(after);
}
// Drag a range input from its current value to `value`, the way a finger would.
async function drag(page, sel, value, after = 1200) {
  const el = page.locator(sel).first(); const b = await el.boundingBox();
  const { min, max, cur } = await el.evaluate(e => ({ min: +e.min, max: +e.max, cur: +e.value }));
  const at = v => b.x + 14 + (b.width - 28) * (v - min) / (max - min), y = b.y + b.height / 2;
  await page.mouse.move(at(cur), y, { steps: 10 }); await sleep(150); await page.mouse.down();
  await page.mouse.move(at(value), y, { steps: 28 }); await sleep(120); await page.mouse.up(); await sleep(after);
}
async function open(browser, q, video) {
  const ctx = await browser.newContext({ viewport: { width: 1024, height: 600 }, deviceScaleFactor: 2,
    ...(video ? { recordVideo: { dir: OUT, size: { width: 1024, height: 600 } } } : {}) });
  const page = await ctx.newPage(); await page.goto(url(q)); await sleep(1200); return { ctx, page };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  if (MODE === 'all' || MODE === 'shots') {
    const shot = async (q, name, steps) => {
      const { ctx, page } = await open(browser, q); if (steps) await steps(page);
      await sleep(700); await page.screenshot({ path: path.join(OUT, name + '.png') }); await ctx.close();
    };
    await shot('', '01-home-ready');
    await shot('running=1', '02-home-steam-on');
    await shot('running=1', '03-time', p => tap(p, '#timer-open'));
    await shot('running=1', '04-temperature', p => tap(p, '#temperature-open'));
    await shot('running=1', '05-lights', p => tap(p, '#lights-open'));
    await shot('running=1', '06-lights-blue', async p => { await tap(p, '#lights-open'); await tap(p, '.swatch[data-preset=blue]'); });
    await shot('running=1', '07-settings', p => tap(p, '#settings-open'));
    for (const [n, hex] of [['08-accent-orange', '#FF9900'], ['09-accent-lime', '#C6FF3D'], ['10-accent-cyan', '#3DE0FF'], ['11-accent-amber', '#F2B56B']])
      await shot('running=1&accent=' + encodeURIComponent(hex), n);
    await shot('running=1&light=0', '12-lights-off');
  }
  if (MODE === 'all' || MODE === 'video') {
    const { ctx, page } = await open(browser, '', true);
    await page.evaluate(() => { window.SHOW_TAPS = true; });
    await sleep(2200);
    await tap(page, '#steam-button', 2600);                        // Start
    await tap(page, '#timer-open', 1000); await drag(page, '#time-slider', 30, 1800); await tap(page, '#back', 1100);
    await tap(page, '#temperature-open', 1000); await drag(page, '#temperature-slider', 110, 1800); await tap(page, '#back', 1100);
    await tap(page, '#lights-open', 1100);
    for (const p of ['blue', 'violet', 'aqua']) await tap(page, `.swatch[data-preset=${p}]`, 1100);
    await drag(page, '#brightness', 70, 1200); await tap(page, '#back', 1300);
    await tap(page, '#settings-open', 1200);
    for (const i of [4, 1, 2, 4]) await tap(page, `#accents button >> nth=${i}`, 1100);
    await tap(page, '#back', 1600);
    await tap(page, '#light-off', 1300); await tap(page, '#light-on', 1500);
    await tap(page, '#steam-button', 2600);                        // Stop
    await sleep(800);
    const video = page.video(); await ctx.close(); fs.renameSync(await video.path(), path.join(OUT, 'tour-raw.webm'));
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
