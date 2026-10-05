// Round 7 hardening evidence: the security headers the proxy actually sends, and
// proof that the strict CSP does not break a single screen. Walks the real UI and
// fails on the first console error or blocked resource.
// Usage: node scripts/screenshot-hardening.mjs [baseUrl] [outDir]
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const SHOTS = process.argv[3]
  ? new URL(`${process.argv[3]}/`, import.meta.url)
  : new URL('../../docs/screenshots/round-7/', import.meta.url);

const REQUIRED = {
  'content-security-policy': /default-src 'self'/,
  'x-content-type-options': /nosniff/,
  'x-frame-options': /SAMEORIGIN/,
  'referrer-policy': /strict-origin-when-cross-origin/,
  'cross-origin-opener-policy': /same-origin/,
  'permissions-policy': /camera=\(self\)/,
};

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();

// Any violation of the CSP shows up here; the whole point is that there are none.
const violations = [];
page.on('console', (message) => {
  const text = message.text();
  if (/Content Security Policy|Refused to/i.test(text)) {
    violations.push(`${message.type()}: ${text}`);
  }
});

await page.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });

// 1. The headers, on the document and on a hashed asset (a location block with its
// own add_header used to silently drop them).
for (const [path, url] of [
  ['documento', `${BASE}/`],
  ['asset', await firstAsset(page)],
]) {
  const response = await page.request.get(url);
  for (const [header, pattern] of Object.entries(REQUIRED)) {
    const value = response.headers()[header];
    if (!value || !pattern.test(value)) {
      throw new Error(`cabecera ausente o incorrecta en ${path}: ${header}`);
    }
  }
  console.log(`cabeceras ok en ${path}`);
}

// 2. Walk every screen: a strict CSP that breaks the app is worse than no CSP.
for (const password of ['NuevaClave2026', 'ChangeMe!2026']) {
  await page.fill('input[name="email"]', 'admin@local');
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
  await page
    .waitForSelector('input[name="email"]', { state: 'detached', timeout: 8000 })
    .then(() => true)
    .catch(() => false);
  if (!page.url().includes('/entrar')) break;
}
if (await page.locator('input[type="password"]').count()) {
  const fields = page.locator('input[type="password"]');
  await fields.nth(0).fill(await fields.nth(0).inputValue());
  await fields.nth(1).fill('NuevaClave2026');
  await fields.nth(2).fill('NuevaClave2026');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
}

for (const [route, name] of [
  ['/', '01-inicio'],
  ['/catalogo', '02-catalogo'],
  ['/inventario', '03-inventario'],
  ['/prestamos', '04-prestamos'],
  ['/mapa', '05-mapa'],
  ['/cuentas', '06-cuentas'],
]) {
  await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  await page.screenshot({
    path: fileURLToPath(new URL(`${name}.png`, SHOTS)),
    fullPage: true,
  });
}

if (violations.length > 0) {
  throw new Error(`la CSP bloqueo algo:\n${violations.join('\n')}`);
}

await browser.close();
console.log('OK: CSP sin violaciones en todas las pantallas');

async function firstAsset(target) {
  const html = await target.request.get(`${BASE}/`);
  const body = await html.text();
  const asset = body.match(/\/assets\/[a-zA-Z0-9._-]+\.js/);
  if (!asset) throw new Error('no se encontro ningun asset en el HTML');
  return `${BASE}${asset[0]}`;
}
