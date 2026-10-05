/**
 * Desk QA: the reader box used to ask for a database id. Now it searches by name
 * and shows what the reader already holds, so the librarian can warn them.
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:8090';
const OUT = path.resolve('../docs/screenshots/round-10');
const ADMIN = { email: 'admin@local', password: 'NuevaClave2026' };
// One account every run, created through the public API. Reused if it is already
// there: a fresh one per run would pile up identical people in the results list,
// which is a screenshot artefact, not a product behaviour.
const READER = {
  email: 'garcia@demo.test',
  fullName: 'Bruno García Ordóñez',
};

await mkdir(OUT, { recursive: true });

const browser = await chromium.launch();
const problems = [];

async function signIn(page, { email, password }) {
  for (const candidate of [password, 'ChangeMe!2026']) {
    await page.goto(`${BASE}/login`);
    await page.fill('input[name="email"]', email);
    await page.fill('input[name="password"]', candidate);
    await page.click('button[type="submit"]');
    await page.waitForTimeout(700);
    if (!page.url().includes('/cambiar-clave')) return;
  }
  throw new Error(`no se pudo iniciar sesion como ${email}`);
}

/** The administrator sets the scene, through the API. */
{
  const page = await browser.newPage();
  await signIn(page, ADMIN);
  const scene = await page.evaluate(async (reader) => {
    const csrf =
      document.cookie
        .split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.slice('XSRF-TOKEN='.length) ?? '';
    const call = async (method, path, body) => {
      const response = await fetch(`/api${path}`, {
        method,
        headers: {
          'X-XSRF-TOKEN': csrf,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
      });
      return { status: response.status, body: await response.json().catch(() => null) };
    };
    const created = await call('POST', '/users', {
      email: reader.email,
      fullName: reader.fullName,
      password: 'GarciaClave2026',
      role: 'LECTOR',
    });
    // 409 means it is already there, which is exactly what we want on a rerun.
    if (created.status !== 201 && created.status !== 409) {
      throw new Error(`no se pudo crear el lector: ${JSON.stringify(created)}`);
    }
    return { id: created.body?.id ?? null };
  }, READER);
  console.log('   lector creado:', READER.fullName, `#${scene.id}`);
  await page.close();
}

const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
page.on('console', (m) => {
  // The browser logs a console error for the identity probe's 401. That one is
  // expected; every other console error fails the run.
  if (m.type() !== 'error') return;
  if (m.text().includes('401') && expectedProbeHappened) return;
  problems.push(m.text());
});
let expectedProbeHappened = false;
page.on('pageerror', (e) => problems.push(String(e)));
// The login page asks who we are before there is a session, so a 401 on this
// probe is the answer, not a fault. Anything else is a real error.
const EXPECTED_401 = '/api/auth/me';
page.on('response', (r) => {
  if (r.status() >= 400) {
    const expected = r.status() === 401 && r.url().includes(EXPECTED_401);
    console.log(`    ${expected ? 'ok ' : ''}${r.status()} ${r.url()}`);
    if (expected) expectedProbeHappened = true;
    if (!expected) problems.push(`${r.status()} ${r.url()}`);
  }
});

async function shot(name) {
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log('  ', name);
}

// The desk is for staff, so sign in as the librarian to shoot it. The reader
// above exists only to be found by the search.
await signIn(page, { email: ADMIN.email, password: ADMIN.password });
await page.goto(`${BASE}/prestamos`);
await page.getByLabel('Lector').fill('gar');
await page.waitForTimeout(900);
await shot('01-buscando-lector');

const hit = page.locator('.reader-picker__hit').first();
if (!(await hit.count())) throw new Error('la busqueda de lector no devolvio nada');
console.log('   lector encontrado:', (await hit.innerText()).replace(/\s+/g, ' '));
await hit.click();
await shot('02-lector-elegido');

await page.click('button:has-text("Cambiar de lector")');
await shot('03-cambiar-de-lector');

await page.emulateMedia({ colorScheme: 'dark' });
await page.getByLabel('Lector').fill('gar');
await page.waitForTimeout(900);
await shot('04-mostrador-oscuro');

await page.setViewportSize({ width: 390, height: 844 });
await page.waitForTimeout(400);
await shot('05-mostrador-movil');

await browser.close();
if (problems.length) {
  console.error('errores de consola:', problems);
  process.exit(1);
}
console.log('OK 5 capturas sin errores de consola');
