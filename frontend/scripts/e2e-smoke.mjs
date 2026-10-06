/**
 * End-to-end smoke: the whole promise of the product, asserted.
 *
 * The screenshot scripts drive this same browser but only check the console, so
 * a broken flow still produces a pretty picture. This one fails.
 *
 * The business rules (the refusal when a book is already out, the renewal cap,
 * the reservation limit) are covered by the backend suite against a real
 * Postgres, so they are not repeated here. What only a browser can prove is
 * exactly what this checks: routing, authentication, the desk, and the reader's
 * own portal.
 *
 * Usage: node scripts/e2e-smoke.mjs [base-url]
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const BASE = process.argv[2] ?? process.env.BASE_URL ?? 'http://127.0.0.1:8090';
// Screenshots belong in the repo's docs, not inside frontend/.
// Its own directory: this script used to write into round-14, so every run quietly
// replaced that round's evidence with fresh screenshots. Evidence is a deliverable.
const SHOTS = path.resolve('../docs/screenshots/e2e-smoke');
const BOOK_TITLE = 'La ruta delFFFFF';
const ADMIN = { email: 'admin@local', password: 'NuevaClave2026' };
const stamp = Date.now();
const READER = {
  email: `e2e.${stamp}@demo.test`,
  fullName: 'Elena Ibáñez',
  password: 'E2eClave2026',
};

let step = 0;
const failures = [];

function ok(message) {
  step += 1;
  console.log(`  ${step}. ${message}`);
}

function check(condition, message) {
  if (!condition) failures.push(message);
  console.log(`     ${condition ? 'ok  ' : 'FALLA'} ${message}`);
}

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();

/** Signs in and clears the forced password change a fresh account gets. */
async function signIn(page, { email, password }) {
  // The real route is /entrar. /login only works through the catch-all
  // redirect, and filling the form before the SPA settles is a race.
  await page.goto(`${BASE}/entrar`);
  await page.locator('input[name="email"]').waitFor({ state: 'visible' });
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForTimeout(800);

  if (page.url().includes('/cambiar-clave')) {
    const fields = page.locator('input[type="password"]');
    await fields.nth(0).fill(password);
    await fields.nth(1).fill(password);
    await page.click('button[type="submit"]');
    await page.waitForTimeout(900);
  }

  // Do not trust the URL: the cookie is what the API will see, and calling it
  // before it lands is a race that made this script fail once in three.
  const ready = await page
    .waitForFunction(
      async () => {
        const r = await fetch('/api/auth/me', { credentials: 'same-origin' });
        return r.ok;
      },
      null,
      { timeout: 15_000 },
    )
    .then(() => true)
    .catch(() => false);
  if (!ready) throw new Error(`la sesion de ${email} nunca llego a estar lista`);
  return page.url();
}

// ── the librarian logs in and sets the scene ─────────────────────────────────
const admin = await browser.newPage();
const adminProblems = [];
admin.on('pageerror', (e) => adminProblems.push(String(e)));

console.log(`\nEscena en ${BASE}`);
await signIn(admin, ADMIN);
ok(`sesion de administrador abierta en ${new URL(admin.url()).pathname}`);
check(
  !(await admin.url().includes('/entrar')),
  'el administrador sale de la pantalla de acceso',
);

const scene = await admin.evaluate(async (opts) => {
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
    return { status: response.status, data: await response.json().catch(() => null) };
  };

  const user = await call('POST', '/users', {
    email: opts.reader.email,
    fullName: opts.reader.fullName,
    password: opts.reader.password,
    role: 'LECTOR',
  });
  const book = await call('POST', '/catalog/books', {
    title: opts.title,
    subtitle: 'Un libro de prueba',
    authors: [{ name: 'Autor Ficticio', role: 'AUTOR' }],
  });
  if (book.status !== 201) return { error: JSON.stringify(book) };
  const copies = await call('POST', '/inventory/copies/bulk', {
    bookId: book.data.id,
    quantity: 1,
  });
  return {
    userStatus: user.status,
    userBody: JSON.stringify(user.data),
    userId: user.data?.id ?? null,
    copyStatus: copies.status,
    copyId: copies.data?.created?.[0]?.id ?? null,
    code: copies.data?.created?.[0]?.code ?? null,
  };
}, { reader: READER, title: BOOK_TITLE });

check(!scene.error, `libro de prueba creado (${scene.error ?? 'ok'})`);
check(
  scene.userStatus === 201,
  `lector de prueba creado (${scene.userStatus} ${scene.userBody ?? ''})`,
);
check(scene.copyStatus === 201, `ejemplar de prueba creado (${scene.copyStatus})`);
ok(`ejemplar ${scene.code} para ${READER.fullName}`);

// ── the desk: find the reader by name, lend the copy ─────────────────────────
console.log('\nMostrador');
await admin.goto(`${BASE}/prestamos`);
await admin.getByLabel('Lector').fill('ibañ');
await admin.waitForTimeout(1200);

// Earlier runs left accounts with this same name behind, and the list is
// ordered by name, so "the first result" would be whichever one the database
// felt like. Pick the one this run created.
const hit = admin.locator('.reader-picker__hit', { hasText: READER.email }).first();
check(await hit.count() > 0, 'escribir "ibañ" encuentra a Elena Ibáñez sin teclear la ñ');
check(
  (await hit.innerText()).includes('Ibáñez'),
  'el resultado muestra el nombre completo con su acento',
);
await hit.click();
check(await admin.getByRole('button', { name: /Cambiar de lector/i }).count() > 0, 'queda elegido');

await admin.getByPlaceholder(/OL-/).fill(scene.code);
await admin.click('button:has-text("Buscar ejemplar")');
await admin.waitForTimeout(1200);
const lend = admin.locator('button:has-text("Prestar")').first();
check(await lend.count() > 0, 'el ejemplar aparece listo para prestar');
await lend.click();
await admin.waitForTimeout(1400);

const afterLend = await admin.locator('body').innerText();
check(
  afterLend.includes(scene.code),
  `el ejemplar ${scene.code} aparece en los prestamos activos del mostrador`,
);
await admin.screenshot({ path: path.join(SHOTS, '01-prestado.png') });

// ── the reader sees it in their own portal ───────────────────────────────────
console.log('\nEl rincon del lector');
// A separate context, or this page shares the administrator's cookies and the
// whole reader half of the test quietly tests the wrong person.
const readerContext = await browser.newContext();
const reader = await readerContext.newPage();
await signIn(reader, READER);
ok(`sesion de lector abierta en ${new URL(reader.url()).pathname}`);


await reader.goto(`${BASE}/mi-biblioteca`);
// Wait for the loan to show up, not for a guessed number of milliseconds.
const shown = await reader
  .getByText(BOOK_TITLE)
  .waitFor({ timeout: 15_000 })
  .then(() => true)
  .catch(() => false);
const mine = await reader.locator('body').innerText();
check(shown && mine.includes(BOOK_TITLE), `el lector ve el libro que se le prestó (${BOOK_TITLE})`);
check(
  await reader.locator('.mine__loans li').count() > 0,
  'el préstamo aparece en su lista, no solo en el texto',
);
await reader.screenshot({ path: path.join(SHOTS, '02-mi-biblioteca.png') });

// The reader renews from home, without walking to the desk.
const renew = reader.locator('button:has-text("Renovar")').first();
check(await renew.count() > 0, 'el lector ve el botón de renovar en su rincon');
const dueBefore = await reader.locator('.mine__date').first().innerText();
await renew.click();
const status = reader.locator('[role="status"]');
const renewed = await status
  .waitFor({ timeout: 15_000 })
  .then(() => true)
  .catch(() => false);
check(renewed, 'el lector renueva su propio préstamo');
// The confirmation itself carries the new date: no waiting for a refetch to
// land, which is what made this flaky the first time round.
const said = renewed ? await status.innerText() : '';
check(said.includes('Renovado'), `la biblioteca lo confirma en pantalla ("${said.trim()}")`);
check(!said.includes(dueBefore), `la fecha se mueve (${dueBefore} -> ${said.trim()})`);
await reader.screenshot({ path: path.join(SHOTS, '02b-renovado.png') });

// ── and a reader cannot reach the desk ───────────────────────────────────────
console.log('\nPermisos en la practica');
// The guard has to bounce a reader off the desk, not just hide the menu link.
const bounced = await reader
  .goto(`${BASE}/prestamos`)
  .then(() =>
    reader
      .waitForURL((u) => !u.pathname.includes('/prestamos'), { timeout: 10_000 })
      .then(() => true)
      .catch(() => false),
  )
  .catch(() => false);
const readerAtDesk = reader.url();
check(
  bounced,
  `un LECTOR no puede llegar al mostrador (acabo en ${new URL(readerAtDesk).pathname})`,
);
await reader.screenshot({ path: path.join(SHOTS, '03-lector-sin-acceso.png') });

check(adminProblems.length === 0, `sin excepciones de JS (${adminProblems.join(' | ')})`);

await browser.close();

console.log('');
if (failures.length) {
  console.error(`FALLOS (${failures.length}):`);
  failures.forEach((f) => console.error(`  - ${f}`));
  process.exit(1);
}
console.log(`OK ${step} pasos, ${failures.length} fallos`);
