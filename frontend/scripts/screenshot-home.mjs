/**
 * Home QA: the panel that has to answer "who is late" before anyone walks in.
 *
 * This script only looks; it never fakes state. To see the urgent list with
 * people in it, age a loan first:
 *
 *   docker compose exec -T db psql -U openlibrary -d openlibrary \
 *     -c "update loans set due_at = now() - interval '9 days' \
 *         where returned_at is null and id in (select id from loans limit 3)"
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:8090';
const OUT = path.resolve('../docs/screenshots/round-13');
const ADMIN = { email: 'admin@local', password: 'NuevaClave2026' };

await mkdir(OUT, { recursive: true });

// After a rebuild the backend gets a new IP and nginx keeps the old one for a
// while, which shows up as a 502 on the first requests. Wait for a real answer
// instead of guessing how long a container takes.
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
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
page.on('pageerror', (e) => problems.push(String(e)));

async function signIn(target, { email, password }) {
  await target.goto(`${BASE}/entrar`);
  await target.locator('input[name="email"]').waitFor({ state: 'visible' });
  await target.fill('input[name="email"]', email);
  await target.fill('input[name="password"]', password);
  await target.click('button[type="submit"]');
  // Let the POST land before polling: navigating away mid-login aborts it.
  await target.waitForTimeout(900);
  await target
    .waitForFunction(
      async () => (await fetch('/api/auth/me', { credentials: 'same-origin' })).ok,
      null,
      { timeout: 15_000 },
    )
    .catch(() => {
      throw new Error(`la sesion no llego a estar lista (url ${target.url()})`);
    });
  console.log('   sesion en', new URL(target.url()).pathname);
}

async function shot(name) {
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log('  ', name);
}

await signIn(page, ADMIN);

await page.goto(`${BASE}/`);
await page.waitForTimeout(1200);
console.log('   tras entrar en la raiz:', new URL(page.url()).pathname);
await shot('01-inicio-panel');

const text = await page.locator('body').innerText();
console.log('   vencidos visibles:', /vencid/i.test(text));

// Wait for the panel itself, not for a number of milliseconds: after a reload
// the app needs a round trip to know who you are.
await page.emulateMedia({ colorScheme: 'dark' });
await page.reload();
await page.waitForSelector('.home__stats, .home__below', { timeout: 15_000 });
await shot('02-inicio-oscuro');

await page.setViewportSize({ width: 390, height: 844 });
await page.reload();
await page.waitForSelector('.home__stats', { timeout: 15_000 });
await shot('03-inicio-movil');

// The reader half: no other people's debts. A throwaway account with a known
// password, so the script does not depend on what other passes left behind.
const readerAccount = {
  email: `inicio.${Date.now()}@demo.test`,
  password: 'InicioClave2026',
};
await page.evaluate(async (account) => {
  const csrf =
    document.cookie
      .split('; ')
      .find((c) => c.startsWith('XSRF-TOKEN='))
      ?.slice('XSRF-TOKEN='.length) ?? '';
  await fetch('/api/users', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': csrf, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: account.email,
      fullName: 'Lector En Casa',
      password: account.password,
      role: 'LECTOR',
    }),
  });
}, readerAccount);

const reader = await (await browser.newContext()).newPage();
await signIn(reader, readerAccount);
await reader.goto(`${BASE}/`);
await reader.waitForTimeout(1200);
await reader.screenshot({ path: path.join(OUT, '04-inicio-lector.png') });
const readerText = await reader.locator('body').innerText();
console.log('   el lector ve "A quien hay que llamar":', readerText.includes('A quien hay que llamar'));

await browser.close();
if (problems.length) {
  console.error('errores de JS:', problems);
  process.exit(1);
}
console.log('OK 4 capturas sin errores de JS');