// Round 9 evidence: the data ownership screen. Downloads a real export, imports it
// back, and screenshots both states plus the refusal for a file that is not an
// export.
// Usage: node scripts/screenshot-transfer.mjs [baseUrl] [outDir]
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const SHOTS = process.argv[3]
  ? new URL(`${process.argv[3]}/`, import.meta.url)
  : new URL('../../docs/screenshots/round-9/', import.meta.url);

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  acceptDownloads: true,
});
const page = await context.newPage();
const saved = [];

async function shoot(name, fullPage = true) {
  await page.screenshot({ path: fileURLToPath(new URL(name, SHOTS)), fullPage });
  saved.push(name);
}

await page.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });
for (const password of ['NuevaClave2026', 'ChangeMe!2026']) {
  await page.fill('input[name="email"]', 'admin@local');
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
  const left = await page
    .waitForSelector('input[name="email"]', { state: 'detached', timeout: 8000 })
    .then(() => true)
    .catch(() => false);
  if (left && !page.url().includes('/entrar')) break;
}
if (await page.getByRole('heading', { name: /contrasena/i }).count()) {
  const fields = page.locator('input[type="password"]');
  await fields.nth(0).fill(await fields.nth(0).inputValue());
  await fields.nth(1).fill('NuevaClave2026');
  await fields.nth(2).fill('NuevaClave2026');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
}
await page.waitForSelector('nav a', { timeout: 20000 });

// The data screen is administrator-only.
await page.goto(`${BASE}/ajustes/datos`, { waitUntil: 'networkidle' });
await page.locator('.transfer').waitFor({ timeout: 15000 });
await shoot('01-tus-datos.png');

// 1. Download a real export and keep the file.
const [download] = await Promise.all([
  page.waitForEvent('download', { timeout: 30000 }),
  page.getByRole('button', { name: 'Descargar la biblioteca' }).click(),
]);
const exported = join(tmpdir(), 'openlibrary-qa.json');
await download.saveAs(exported);
await page.locator('.transfer__counts').waitFor({ timeout: 15000 });
await shoot('02-exportado.png');
console.log(`exportado a ${exported}`);

// 2. Import it back: everything already exists, so nothing may be duplicated.
await page.locator('input[type="file"]').setInputFiles(exported);
await page.locator('.transfer__report, .transfer__error').first().waitFor({ timeout: 30000 });
if (await page.locator('.transfer__error').count()) {
  const text = await page.locator('.transfer__error').innerText();
  throw new Error('la importacion fallo: ' + text);
}
await shoot('03-importado.png');

const report = await page.locator('.transfer__table tbody tr').evaluateAll((rows) =>
  rows.map((row) => row.innerText.replace(/\s+/g, ' ').trim()),
);
console.log('informe de importacion:');
for (const row of report) console.log(`  ${row}`);

// 3. A file that is not an export has to be refused with a readable message.
const rubbish = join(tmpdir(), 'no-es-un-export.json');
await writeFile(rubbish, '{"hola":"que tal"}');
await page.locator('input[type="file"]').setInputFiles(rubbish);
await page.locator('.transfer__error').waitFor({ timeout: 20000 });
await shoot('04-importacion-rechazada.png');

// 4. Dark theme.
await page.evaluate(() => localStorage.setItem('theme', 'dark'));
await page.emulateMedia({ colorScheme: 'dark' });
await page.goto(`${BASE}/ajustes/datos`, { waitUntil: 'networkidle' });
await page.locator('.transfer').waitFor({ timeout: 15000 });
await shoot('05-tus-datos-oscuro.png');

// 5. Phone.
const phoneCtx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  storageState: await context.storageState(),
});
const phone = await phoneCtx.newPage();
await phone.goto(`${BASE}/ajustes/datos`, { waitUntil: 'networkidle' });
await phone.locator('.transfer').waitFor({ timeout: 15000 });
await phone.screenshot({
  path: fileURLToPath(new URL('06-tus-datos-movil.png', SHOTS)),
  fullPage: true,
});
saved.push('06-tus-datos-movil.png');
const overflow = await phone.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
if (overflow > 2) throw new Error(`la pantalla de datos desborda en movil: ${overflow}px`);

await browser.close();
console.log('');
console.log(saved.join('\n'));
console.log(`OK ${saved.length} screenshots`);
