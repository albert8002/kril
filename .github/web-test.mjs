// Serves the built web app and drives it in headless Chrome (see web-test.sh).
import { createRequire } from 'module';
import http from 'http';
import fs from 'fs';
import path from 'path';

const require = createRequire('/tmp/pt/package.json');
const puppeteer = require('puppeteer-core');
const [root, out] = process.argv.slice(2);
const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.css': 'text/css', '.json': 'application/json', '.ttf': 'font/ttf', '.otf': 'font/otf', '.png': 'image/png',
  '.txt': 'text/plain', '.map': 'application/json',
};
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0].split('#')[0]);
  if (p.endsWith('/')) p += 'index.html';
  const f = path.join(root, p);
  fs.readFile(f, (e, d) => {
    if (e) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(f)] || 'application/octet-stream' });
    res.end(d);
  });
}).listen(8080);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lines = [];
const log = (s) => { lines.push(s); };
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME,
  headless: true,
  protocolTimeout: 240000,
  args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
    '--autoplay-policy=no-user-gesture-required', '--window-size=412,915'],
});
const page = await browser.newPage();
await page.setViewport({ width: 412, height: 915, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
let shots = 0;
let chain = Promise.resolve();
let done = false;
page.on('console', (m) => {
  const t = m.text();
  log(t);
  if (t.startsWith('SELFTEST SHOT ')) {
    const name = t.slice(14).trim().replace(/[^A-Za-z0-9_.-]/g, '_');
    const n = String(++shots).padStart(3, '0');
    chain = chain.then(() => sleep(600)).then(() => page.screenshot({ path: `${out}/shots/${n}_${name}.png` })).catch((e) => log('SHOT failed ' + e));
  }
  if (t.startsWith('SELFTEST DONE')) done = true;
});
page.on('pageerror', (e) => log('PAGEERROR ' + e.message));
page.on('requestfailed', (r) => log('REQFAIL ' + r.url().slice(0, 160) + ' ' + (r.failure()?.errorText || '')));

const t0 = Date.now();
// 1) a plain first visit
await page.goto('http://localhost:8080/', { waitUntil: 'load', timeout: 180000 });
await sleep(20000);
await page.screenshot({ path: `${out}/shots/000_first_visit.png` });
log(`START first visit after ${(Date.now() - t0) / 1000}s`);
// 2) the self-test
await page.goto('http://localhost:8080/?selftest', { waitUntil: 'load', timeout: 180000 });
const end = Date.now() + 40 * 60 * 1000;
while (!done && Date.now() < end) await sleep(1000);
await chain;
await sleep(1500);
await page.screenshot({ path: `${out}/shots/999_end.png` }).catch(() => {});
log(`END done=${done} after ${(Date.now() - t0) / 1000}s shots=${shots}`);
fs.writeFileSync(`${out}/console.txt`, lines.join('\n'));
await browser.close();
server.close();
console.log(lines.filter((l) => /SELFTEST (READY|DONE|SIGIL|EGGS)|PAGEERROR|END done/.test(l)).slice(-40).join('\n'));
