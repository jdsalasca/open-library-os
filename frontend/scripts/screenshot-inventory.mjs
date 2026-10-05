// Round 3 evidence: inventory page (list, filters, bulk add, labels, locations)
// plus the reader view that must not see the section.
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:8090';
const OUT = new URL('../../docs/evidence/round-3/', import.meta.url);
const SHOTS = new URL('../../docs/screenshots/round-3/', import.meta.url);

async function login(page, password) {
  await page.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });
  await page.fill('input[name="email"]', 'admin@local');
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL((url) => !url.pathname.includes('/entrar'), { timeout: 15000 });
}

async function clearGate(page) {
  const heading = page.getByRole('heading', { name: /cambia (tu )?contrasena|nueva contrasena/i });
  if (await heading.count()) {
    const fields = page.locator('input[type="password"]');
    const total = await fields.count();
    await fields.nth(0).fill(await fields.nth(0).inputValue());
    await fields.nth(1).fill('NuevaClave2026');
    await fields.nth(2).fill('NuevaClave2026');
    await page.click('button[type="submit"]');
    await page.waitForURL((url) => !/contrasena|password/i.test(url.pathname), { timeout: 15000 });
    return total;
  }
  return 0;
}

await mkdir(OUT, { recursive: true });
await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
const results = [];

async function shot(name, target, target_page = page) {
  const file = fileURLToPath(new URL(name, SHOTS));
  await target_page.screenshot({ path: file, fullPage: Boolean(target) });
  results.push(name);
}

// The seeded account may still be waiting for the forced password change.
for (const candidate of ['NuevaClave2026', 'ChangeMe!2026']) {
  try {
    await login(page, candidate);
    break;
  } catch {
    /* try the documented seed password */
  }
}
await clearGate(page);

// 1. Inventory list with seeded copies.
await page.goto(`${BASE}/inventario`, { waitUntil: 'networkidle' });
await page.waitForSelector('table tbody tr');
await shot('01-inventario-listado.png');

// 2. Filter by state: only withdrawn copies.
await page.selectOption('select#\\:r2\\:, select >> nth=1', { label: 'Mantenimiento' }).catch(async () => {
  await page.getByLabel('Estado').selectOption({ label: 'Mantenimiento' });
});
await page.getByRole('button', { name: 'Buscar' }).click();
await page.waitForTimeout(700);
await shot('02-inventario-filtrado-vacio.png');

// 3. Bulk add dialog.
await page.getByLabel('Estado').selectOption({ label: 'Todos' });
await page.getByRole('button', { name: 'Buscar' }).click();
await page.getByRole('button', { name: 'Anadir ejemplares' }).click();
await page.getByRole('dialog').waitFor().catch(() => {});
await shot('03-inventario-alta-masiva.png', true);

// 4. Generate one copy and read back its code.
await page.getByLabel('Cuantos').fill('2');
await page.getByRole('button', { name: /Generar 2 ejemplares/ }).click();
await page.getByText('Codigos generados').waitFor({ timeout: 10000 });
await shot('04-inventario-codigos-generados.png', true);

// 5. Location tree with rolled-up counts.
await page.goto(`${BASE}/inventario`, { waitUntil: 'networkidle' });
await page.getByRole('heading', { name: 'Ubicaciones' }).scrollIntoViewIfNeeded();
await page.waitForTimeout(400);
await shot('05-inventario-ubicaciones.png', true);

// 6. Printable label.
const label = await context.newPage();
const href = await page.locator('a.inv__label').first().getAttribute('href');
await label.goto(`${BASE}${href}`, { waitUntil: 'networkidle' });
await shot('06-etiqueta-imprimible.png', true, label);
await label.close();

// 7. Reader must not get the section.
const readerCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const reader = await readerCtx.newPage();
await reader.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });
await reader.fill('input[name="email"]', 'lector@local');
await reader.fill('input[name="password"]', 'LectorClave2026');
await reader.click('button[type="submit"]');
await reader.waitForTimeout(1500);
if (await reader.locator('input[type="password"]').count()) {
  const fields = reader.locator('input[type="password"]');
  await fields.nth(0).fill('ChangeMe!2026');
  await fields.nth(1).fill('LectorClave2026');
  await fields.nth(2).fill('LectorClave2026');
  await reader.click('button[type="submit"]');
  await reader.waitForTimeout(1500);
}
await reader.goto(`${BASE}/inventario`, { waitUntil: 'networkidle' });
await reader.waitForTimeout(800);
await shot('07-lector-sin-inventario.png', false, reader);
const navHasInventory = await reader
  .locator('nav a[href="/inventario"]')
  .count();
if (navHasInventory > 0) throw new Error('lector sees the inventory nav entry');

// 8. Dark theme on the staff view.
await page.evaluate(() => localStorage.setItem('theme', 'dark'));
await page.emulateMedia({ colorScheme: 'dark' });
await page.goto(`${BASE}/inventario`, { waitUntil: 'networkidle' });
await page.waitForSelector('table tbody tr');
await shot('08-inventario-oscuro.png');
await page.evaluate(() => localStorage.setItem('theme', 'light'));

// 9. Mobile: the action column has to give way, not overflow.
const mobile = await browser.newContext({
  viewport: { width: 390, height: 844 },
  storageState: await context.storageState(),
});
const phone = await mobile.newPage();
await phone.goto(`${BASE}/inventario`, { waitUntil: 'networkidle' });
await phone.waitForSelector('table tbody tr');
await shot('09-inventario-movil.png', true, phone);
const overflow = await phone.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
if (overflow > 2) throw new Error(`la tabla desborda en movil: ${overflow}px`);
await mobile.close();

await browser.close();
console.log(results.join('\n'));
console.log(`OK ${results.length} screenshots`);
