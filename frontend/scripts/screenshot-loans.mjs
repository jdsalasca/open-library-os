// Round 4 evidence: the desk screen. Walks a real loan end to end through the UI:
// type a reader, type a copy code, lend, renew, return, and check the queue.
// Usage: node scripts/screenshot-loans.mjs [baseUrl] [outDir]
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const SHOTS = process.argv[3]
  ? new URL(`${process.argv[3]}/`, import.meta.url)
  : new URL('../../docs/screenshots/round-4/', import.meta.url);

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
// 1024x768 is the tablet the desk actually sits at.
const context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
const page = await context.newPage();
const saved = [];

async function shot(name, fullPage = true) {
  const file = fileURLToPath(new URL(name, SHOTS));
  await page.screenshot({ path: file, fullPage });
  saved.push(name);
}

// Sign in, clearing the forced password change if the seed still has it.
await page.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });
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

await page.goto(`${BASE}/prestamos`, { waitUntil: 'networkidle' });
await page.waitForSelector('.loans__desk', { timeout: 15000 });
await shot('01-mostrador-vacio.png');

// Find a reader id and an available copy through the API the page already uses,
// so the screenshot shows real data instead of a hand-written id.
const ids = await page.evaluate(async () => {
  const csrf = document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length);
  const me = await fetch('/api/auth/me', { headers: { 'X-XSRF-TOKEN': csrf ?? '' } }).then((r) =>
    r.json(),
  );
  const users = await fetch('/api/users?size=50', {
    headers: { 'X-XSRF-TOKEN': csrf ?? '' },
  }).then((r) => r.json());
  const copies = await fetch('/api/inventory/copies?status=DISPONIBLE&size=1', {
    headers: { 'X-XSRF-TOKEN': csrf ?? '' },
  }).then((r) => r.json());
  return {
    me: me.id,
    // /api/users answers with a plain array, not a page.
    reader: Array.isArray(users) ? users.find((u) => u.role === 'LECTOR')?.id : undefined,
    copy: copies.content?.[0]?.code,
  };
});
if (!ids.reader || !ids.copy) throw new Error(`no hay datos para el prestamo: ${JSON.stringify(ids)}`);

// 2. Lend: reader id, then the copy code as a scanner would type it.
await page.getByLabel(/Lector/).fill(String(ids.reader ?? ''));
await page.getByLabel(/Codigo del ejemplar/).fill(ids.copy ?? '');
await page.getByRole('button', { name: 'Buscar ejemplar' }).click();
await page.locator('.loans__found li').first().waitFor({ timeout: 10000 });
await shot('02-mostrador-ejemplar-encontrado.png');

await page.locator('.loans__found li').first().getByRole('button', { name: 'Prestar' }).click();
await page.locator('.loans__notice--ok').waitFor({ timeout: 10000 });
await shot('03-mostrador-prestado.png');

// 4. Renew from the desk list.
await page.getByRole('button', { name: 'Renovar' }).first().click();
await page.waitForTimeout(900);
await shot('04-mostrador-renovado.png');

// 5. Give it back.
await page.getByRole('button', { name: 'Devolver' }).first().click();
await page.waitForTimeout(900);
await shot('05-mostrador-devuelto.png');

// 6. A refusal the reader can understand: ask for a reader that does not exist.
await page.getByLabel(/Lector/).fill('999999');
await page.getByLabel(/Codigo del ejemplar/).fill(ids.copy);
await page.getByRole('button', { name: 'Buscar ejemplar' }).click();
await page.locator('.loans__found li').first().waitFor({ timeout: 10000 });
await page.locator('.loans__found li').first().getByRole('button', { name: 'Prestar' }).click();
await page.locator('.loans__notice--ko').waitFor({ timeout: 10000 });
await shot('06-mostrador-error-claro.png');

// 7. Dark theme.
await page.evaluate(() => localStorage.setItem('theme', 'dark'));
await page.emulateMedia({ colorScheme: 'dark' });
await page.goto(`${BASE}/prestamos`, { waitUntil: 'networkidle' });
await page.waitForSelector('.loans__desk');
await shot('07-mostrador-oscuro.png');
await page.evaluate(() => localStorage.setItem('theme', 'light'));

// 8. Phone: the desk collapses to one column without overflowing.
const mobile = await browser.newContext({
  viewport: { width: 390, height: 844 },
  storageState: await context.storageState(),
});
const phone = await mobile.newPage();
await phone.goto(`${BASE}/prestamos`, { waitUntil: 'networkidle' });
await phone.waitForSelector('.loans__desk');
// Everything was given back on the desktop pass, so lend from the phone first:
// an empty table would prove nothing about whether the desk works at 390px.
await phone.getByLabel(/Lector/).fill(String(ids.reader));
await phone.getByLabel(/Codigo del ejemplar/).fill(ids.copy);
await phone.getByRole('button', { name: 'Buscar ejemplar' }).click();
await phone.locator('.loans__found li').first().waitFor({ timeout: 15000 });
await phone.locator('.loans__found li').first().getByRole('button', { name: 'Prestar' }).click();
await phone.locator('.loans__notice--ok').waitFor({ timeout: 15000 });
await phone.getByRole('columnheader', { name: 'Acciones' }).waitFor({ timeout: 15000 });
const canReturn = await phone.getByRole('button', { name: 'Devolver' }).count();
if (canReturn === 0) throw new Error('en movil no se puede devolver un prestamo');
// Reachable without a sideways scroll, not merely present in the DOM.
const returnBox = await phone.getByRole('button', { name: 'Devolver' }).first().boundingBox();
if (!returnBox || returnBox.x + returnBox.width > 391) {
  throw new Error(`el boton Devolver queda fuera de pantalla: ${JSON.stringify(returnBox)}`);
}
const file = fileURLToPath(new URL('08-mostrador-movil.png', SHOTS));
await phone.screenshot({ path: file, fullPage: true });
saved.push('08-mostrador-movil.png');
const overflow = await phone.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
if (overflow > 2) throw new Error(`la pantalla del mostrador desborda en movil: ${overflow}px`);
await mobile.close();

await browser.close();
console.log(saved.join('\n'));
console.log(`OK ${saved.length} screenshots`);
