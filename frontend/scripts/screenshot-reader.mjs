// Round 8 evidence: the reader's corner of the library. Signs in as a reader (not
// the administrator), gives them loans, history and a queue, and screenshots every
// state in light, dark and on a phone.
// Usage: node scripts/screenshot-reader.mjs [baseUrl] [outDir]
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const SHOTS = process.argv[3]
  ? new URL(`${process.argv[3]}/`, import.meta.url)
  : new URL('../../docs/screenshots/round-8/', import.meta.url);

// A fresh card holder every run, created through the public API with a password
// this script knows: no shared state, no poking at the database.
const READER = {
  email: `lector-qa-${Date.now().toString(36)}@local`,
  password: 'LectorClave2026',
};

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
const saved = [];
const crashes = [];
page.on('pageerror', (error) => crashes.push(error.message));

async function shoot(target, name, fullPage = true) {
  await target.screenshot({
    path: fileURLToPath(new URL(name, SHOTS)),
    fullPage,
  });
  saved.push(name);
}

/** Signs in, clears the forced password change if any, and waits for the shell. */
async function signIn(target, credentials) {
  await target.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });

  let entered = false;
  for (const password of [credentials.password, 'ChangeMe!2026']) {
    await target.fill('input[name="email"]', credentials.email);
    await target.fill('input[name="password"]', password);
    await target.click('button[type="submit"]');
    const left = await target
      .waitForSelector('input[name="email"]', { state: 'detached', timeout: 8000 })
      .then(() => true)
      .catch(() => false);
    if (left && !target.url().includes('/entrar')) {
      entered = true;
      break;
    }
  }
  if (!entered) throw new Error(`no se pudo entrar como ${credentials.email}`);

  // A freshly seeded account is sent to the change-password screen first.
  if (await target.getByRole('heading', { name: /contrasena/i }).count()) {
    const fields = target.locator('input[type="password"]');
    await fields.nth(0).fill(await fields.nth(0).inputValue());
    await fields.nth(1).fill('NuevaClave2026');
    await fields.nth(2).fill('NuevaClave2026');
    await target.click('button[type="submit"]');
    await target.waitForTimeout(1500);
  }

  // Wait for the shell to be on screen: navigating away in the middle of the first
  // render leaves the tree empty and every later screenshot comes out blank.
  await target.waitForSelector('nav a', { timeout: 20000 });
}

// ── the administrator sets the scene, through the API ────────────────────────
const admin = await browser.newContext();
const setup = await admin.newPage();
await signIn(setup, { email: 'admin@local', password: 'NuevaClave2026' });

const scene = await setup.evaluate(async (readerEmail) => {
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

  const users = await call('GET', '/users');
  if (!users.body?.length) throw new Error('no se pudo leer la lista de cuentas');
  const created = await call('POST', '/users', {
    email: readerEmail,
    fullName: 'Ana Lectora',
    password: 'LectorClave2026',
    role: 'LECTOR',
  });
  if (created.status !== 201) {
    throw new Error(`no se pudo crear el lector: ${JSON.stringify(created)}`);
  }
  const reader = { id: created.body.id };

  // A book made for this run, so the scene does not depend on whatever the
  // catalogue happens to look like after a dozen screenshot passes.
  const wanted = await call('POST', '/catalog/books', {
    title: 'La biblioteca de medievo',
    subtitle: 'Un recorrido por los scriptorios',
    isbn13: '9788491058120',
    authors: [{ name: 'Dorothy L. Sayers', role: 'AUTOR' }],
    categories: ['Ensayo'],
  });
  if (wanted.status !== 201) {
    throw new Error(`no se pudo crear el libro: ${JSON.stringify(wanted)}`);
  }

  const available = await call('POST', '/catalog/books', {
    title: 'El nombre de la rosa',
    isbn13: '9788426403568',
    authors: [{ name: 'Umberto Eco', role: 'AUTOR' }],
  });
  if (available.status !== 201 && available.status !== 409) {
    throw new Error(`no se pudo crear el segundo libro: ${JSON.stringify(available)}`);
  }

  const stock = await call('POST', '/inventory/copies/bulk', {
    bookId: wanted.body.id,
    quantity: 1,
  });
  const copyId = stock.body.created[0].id;
  const onShelf = await call('POST', '/inventory/copies/bulk', {
    bookId: available.body.id ?? available.body?.id,
    quantity: 1,
  });
  if (!onShelf.body?.created?.length) {
    throw new Error(`no se pudo poblar el segundo libro: ${JSON.stringify(onShelf)}`);
  }

  // A throwaway reader to hold the copy. Reusing a shared account would hit its
  // loan limit after a couple of runs and the scene would silently change.
  const holder = await call('POST', '/users', {
    email: `titular-qa-${Date.now().toString(36)}@local`,
    fullName: 'Titular Temporal',
    password: 'LectorClave2026',
    role: 'LECTOR',
  });
  if (holder.status !== 201) {
    throw new Error(`no se pudo crear el titular: ${JSON.stringify(holder)}`);
  }

  // Somebody else keeps the only copy, and the reader takes a second one out.
  await call('POST', '/loans', { copyId, readerId: holder.body.id });
  const open = await call('GET', '/inventory/copies?status=DISPONIBLE&size=2');
  const second = open.body.content[0];
  if (second) {
    await call('POST', '/loans', { copyId: second.id, readerId: reader.id });
  }
  const loans = await call('GET', `/loans?readerId=${reader.id}&size=5`);
  const firstLoan = loans.body.content[0];
  if (firstLoan) {
    await call('POST', `/loans/${firstLoan.id}/renew`);
  }

  // One returned book, so the history section is not empty either. It must be a
  // different title: returning a copy of the wanted book would put it back on the
  // shelf and make the reservation below impossible.
  const spare = await call('GET', '/inventory/copies?status=DISPONIBLE&size=6');
  const toReturn = spare.body.content?.find((c) => c.bookId !== wanted.body.id);
  if (toReturn) {
    await call('POST', '/loans', { copyId: toReturn.id, readerId: reader.id });
    const mine = await call('GET', `/loans?readerId=${reader.id}&size=1`);
    await call('POST', `/loans/${mine.body.content[0].id}/return`);
  }

  // The reader waits for the book whose only copy is out. The queue entry belongs
  // to whoever asks for it, so it is created later with the reader's own session.

  return {
    readerId: reader.id,
    wantedBookId: wanted.body.id,
    availableBookId: available.body.id,
  };
}, READER.email);
await admin.close();

// ── the reader's own screens ──────────────────────────────────────────────────
await signIn(page, READER);
if (page.url().includes('/entrar')) {
  throw new Error(`el lector no pudo entrar: ${page.url()}`);
}

await page.goto(`${BASE}/mi-biblioteca`, { waitUntil: 'networkidle' });
await page
  .locator('.mine__heading')
  .first()
  .waitFor({ timeout: 15000 })
  .catch(async () => {
    const mine = await page
      .evaluate(async () => {
        const csrf =
          document.cookie
            .split('; ')
            .find((c) => c.startsWith('XSRF-TOKEN='))
            ?.slice('XSRF-TOKEN='.length) ?? '';
        const response = await fetch('/api/loans/mine', { headers: { 'X-XSRF-TOKEN': csrf } });
        return `${response.status} ${await response.text()}`;
      })
      .catch((e) => `no se pudo consultar: ${e}`);
    throw new Error(
      `la pantalla del lector no cargo: ${page.url()}\n` +
        `body: ${JSON.stringify((await page.locator('body').innerText()).slice(0, 300))}\n` +
        `h2: ${JSON.stringify(
          await page
            .locator('h2')
            .evaluateAll((nodes) => nodes.map((n) => `${n.className}|${n.textContent}`)),
        )}\n` +
        `main: ${JSON.stringify(
          (await page.locator('main').innerHTML().catch(() => 'sin main')).slice(0, 300),
        )}\n` +
        `crashes: ${crashes.join(' | ') || '(ninguno)'}\n` +
        `/api/loans/mine: ${mine.slice(0, 400)}`,
    );
  });
await page.waitForTimeout(400);
await shoot(page, '01-mi-biblioteca.png');

// The desk must not leak into the reader's navigation.
const staffLinks = await page.locator('nav a[href="/prestamos"]').count();
if (staffLinks > 0) throw new Error('el lector ve el enlace del mostrador');

// The book page offers the reserve button, and says plainly why it will not
// queue a book that is sitting on the shelf.
if (scene.wantedBookId) {
  await page.goto(`${BASE}/catalogo/${scene.wantedBookId}`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: 'Reservar' }).waitFor({ timeout: 10000 });
  await shoot(page, '02-ficha-reserva-ya-hecha.png', false);
}

// A book that IS on the shelf must send the reader to the desk, not to a queue.
if (scene.availableBookId) {
  await page.goto(`${BASE}/catalogo/${scene.availableBookId}`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: 'Reservar' }).click();
  await page.locator('.book__reserve-error').waitFor({ timeout: 10000 });
  await shoot(page, '03-ficha-reserva-rechazada.png', false);
}

// The queue entry has to belong to the reader, so it is made with their session.
const reserved = await page.evaluate(async (bookId) => {
  const csrf =
    document.cookie
      .split('; ')
      .find((c) => c.startsWith('XSRF-TOKEN='))
      ?.slice('XSRF-TOKEN='.length) ?? '';
  const response = await fetch('/api/loans/reservations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    body: JSON.stringify({ bookId }),
  });
  return `${response.status} ${await response.text()}`;
}, scene.wantedBookId);
if (!reserved.startsWith('201')) {
  throw new Error(`el lector no pudo reservar: ${reserved.slice(0, 200)}`);
}

await page.goto(`${BASE}/mi-biblioteca`, { waitUntil: 'networkidle' });
await page.locator('.mine__heading', { hasText: 'Esperando' }).waitFor({ timeout: 15000 });
await page.waitForTimeout(400);
await shoot(page, '04-mi-biblioteca-con-reserva.png');

// Leaving the queue works and the badge goes away.
await page.getByRole('button', { name: 'Salir de la cola' }).first().click();
await page.waitForTimeout(900);
const stillQueued = await page.getByRole('button', { name: 'Salir de la cola' }).count();
if (stillQueued > 0) throw new Error('el lector no puede salir de la cola');

// Dark theme.
await page.evaluate(() => localStorage.setItem('theme', 'dark'));
await page.emulateMedia({ colorScheme: 'dark' });
await page.goto(`${BASE}/mi-biblioteca`, { waitUntil: 'networkidle' });
await page.waitForTimeout(600);
await shoot(page, '05-mi-biblioteca-oscuro.png');
await page.evaluate(() => localStorage.setItem('theme', 'light'));

// Phone: this screen exists for phones.
const phoneCtx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  storageState: await context.storageState(),
});
const phone = await phoneCtx.newPage();
await phone.goto(`${BASE}/mi-biblioteca`, { waitUntil: 'networkidle' });
await phone.locator('.mine__heading').first().waitFor({ timeout: 15000 });
await shoot(phone, '06-mi-biblioteca-movil.png');
const overflow = await phone.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
if (overflow > 2) throw new Error(`la pantalla del lector desborda en movil: ${overflow}px`);

await browser.close();
console.log(saved.join('\n'));
console.log(`OK ${saved.length} screenshots`);
