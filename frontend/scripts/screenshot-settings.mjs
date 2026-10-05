/**
 * Settings QA: the lending policy has to be changeable from the browser, and a
 * reader has to bounce off it.
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:8090';
const OUT = path.resolve('../docs/screenshots/round-15');
const ADMIN = { email: 'admin@local', password: 'NuevaClave2026' };

await mkdir(OUT, { recursive: true });

const probe = async () =>
  fetch(`${BASE}/api/actuator/health`)
    .then((r) => r.ok)
    .catch(() => false);
let apiUp = await probe();
for (let i = 0; i < 30 && !apiUp; i++) {
  await new Promise((r) => setTimeout(r, 2000));
  apiUp = await probe();
}
if (!apiUp) {
  console.error(`la API no responde por el proxy en ${BASE}: reinicia el frontend`);
  process.exit(1);
}

const browser = await chromium.launch();
const problems = [];
const page = await browser.newPage({ viewport: { width: 1280, height: 950 } });
page.on('pageerror', (e) => problems.push(String(e)));

async function signIn(target, { email, password }) {
  await target.goto(`${BASE}/entrar`);
  await target.locator('input[name="email"]').waitFor({ state: 'visible' });
  await target.fill('input[name="email"]', email);
  await target.fill('input[name="password"]', password);
  await target.click('button[type="submit"]');
  await target.waitForTimeout(900);
  await target
    .waitForFunction(
      async () => (await fetch('/api/auth/me', { credentials: 'same-origin' })).ok,
      null,
      { timeout: 15_000 },
    )
    .catch(() => {
      throw new Error(`la sesion de ${email} no llego a estar lista`);
    });
}

async function shot(name) {
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true });
  console.log('  ', name);
}

await signIn(page, ADMIN);
await page.goto(`${BASE}/ajustes/datos`);
await page.waitForSelector('.policy__grid', { timeout: 15_000 });
await shot('01-ajustes');

// Change one number and put it back, so the library keeps lending the same way.
const before = await page.getByLabel(/dias de prestamo/i).inputValue();
await page.getByLabel(/dias de prestamo/i).fill('21');
await page.getByRole('button', { name: /guardar reglas/i }).click();
await page.locator('[role="status"]').waitFor({ timeout: 15_000 });
await shot('02-guardado');
console.log(`   dias: ${before} -> 21`);

await page.getByLabel(/dias de prestamo/i).fill(before);
await page.getByRole('button', { name: /guardar reglas/i }).click();
await page.locator('[role="status"]').waitFor({ timeout: 15_000 });
const restored = await page.getByLabel(/dias de prestamo/i).inputValue();
console.log(`   restaurado a ${restored}`);

// A nonsense number must not be savable.
await page.getByLabel(/dias de prestamo/i).fill('0');
await page.waitForTimeout(200);
const disabled = await page.getByRole('button', { name: /guardar reglas/i }).isDisabled();
console.log('   con 0 dias el boton esta deshabilitado:', disabled);
await shot('03-rechazo');
await page.getByLabel(/dias de prestamo/i).fill(restored);

await page.emulateMedia({ colorScheme: 'dark' });
await page.reload();
await page.waitForSelector('.policy__grid', { timeout: 15_000 });
await shot('04-oscuro');

await page.setViewportSize({ width: 390, height: 844 });
await page.reload();
await page.waitForSelector('.policy__grid', { timeout: 15_000 });
await shot('05-movil');

// A reader has no business here.
const readerCtx = await browser.newContext();
const reader = await readerCtx.newPage();
await signIn(reader, { email: process.argv[2], password: 'E2eClave2026' });
await reader.goto(`${BASE}/ajustes/datos`);
await reader.waitForTimeout(1200);
await reader.screenshot({ path: path.join(OUT, '06-lector-rechazado.png') });
console.log('   ellector rebotó a', new URL(reader.url()).pathname);

await browser.close();
if (problems.length) {
  console.error('errores de JS:', problems);
  process.exit(1);
}
console.log('OK 6 capturas sin errores de JS');