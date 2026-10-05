// Round 6 evidence: the 3D-looking floor plan. Walks the real map from a phone
// and a desktop: overview, occupancy mode, drilling into a shelf, the twin list,
// dark theme and reduced motion.
// Usage: node scripts/screenshot-map.mjs [baseUrl] [outDir]
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const SHOTS = process.argv[3]
  ? new URL(`${process.argv[3]}/`, import.meta.url)
  : new URL('../../docs/screenshots/round-6/', import.meta.url);

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const saved = [];

async function signIn(page) {
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
}

async function shoot(page, name, fullPage = true) {
  const file = fileURLToPath(new URL(name, SHOTS));
  await page.screenshot({ path: file, fullPage });
  saved.push(name);
}

// ── desktop ───────────────────────────────────────────────────────────────────
const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await desktop.newPage();
await signIn(page);

await page.goto(`${BASE}/mapa`, { waitUntil: 'networkidle' });
await page.waitForSelector('.lmap__plan');
await page.waitForTimeout(400);
await shoot(page, '01-mapa-general.png');

// The plan must be a 3D-looking scene, not a flat list of divs.
const transform = await page.locator('.lmap__plan').evaluate((el) => getComputedStyle(el).transform);
if (transform === 'none') throw new Error('el plano no esta proyectado en 3D');

// The whole plan has to be visible without scrolling on a laptop: a bigger scale
// used to push it past the stage and hide half the library.
const stage = await page.locator('.lmap__stage').evaluate((el) => ({
  width: el.clientWidth,
  height: el.clientHeight,
  scrollWidth: el.scrollWidth,
  scrollHeight: el.scrollHeight,
}));
if (stage.scrollWidth > stage.width + 2 || stage.scrollHeight > stage.height + 2) {
  throw new Error(
    `el plano no cabe en el escenario: ${stage.scrollWidth}x${stage.scrollHeight} en ${stage.width}x${stage.height}`,
  );
}

// A room floor must never cover the shelves standing on it.
const covered = await page.locator('.lmap__node[data-kind="ESTANTE"]').evaluateAll((shelves) =>
  shelves.filter((shelf) => {
    const box = shelf.getBoundingClientRect();
    const top = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
    return !(top === shelf || shelf.contains(top));
  }).length,
);
if (covered > 0) throw new Error(`${covered} estantes tapados por su propia sala`);

// Occupancy colours.
await page.getByRole('button', { name: 'Ver ocupacion' }).click();
await page.waitForTimeout(300);
await shoot(page, '02-mapa-ocupacion.png');

// Drill into a shelf: selection, highlight, books and copies in the twin list.
await page.locator('.lmap__node[data-kind="ESTANTE"]').first().click();
await page.waitForTimeout(400);
await shoot(page, '03-mapa-estante-seleccionado.png');

// Back up the chain: room, then aisles.
await page.getByRole('button', { name: /Volver a/ }).click();
await page.waitForTimeout(300);
await page.getByRole('button', { name: /P-1/ }).first().click();
await page.waitForTimeout(400);
await shoot(page, '04-mapa-pasillo.png');

// Dark theme.
await page.evaluate(() => localStorage.setItem('theme', 'dark'));
await page.emulateMedia({ colorScheme: 'dark' });
await page.goto(`${BASE}/mapa`, { waitUntil: 'networkidle' });
await page.waitForSelector('.lmap__plan');
await shoot(page, '05-mapa-oscuro.png');
await page.evaluate(() => localStorage.setItem('theme', 'light'));

// ── phone: the deliverable ────────────────────────────────────────────────────
const phoneCtx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  storageState: await desktop.storageState(),
});
const phone = await phoneCtx.newPage();
await phone.goto(`${BASE}/mapa`, { waitUntil: 'networkidle' });
await phone.waitForSelector('.lmap__plan');
await phone.waitForTimeout(400);
await shoot(phone, '06-mapa-movil.png');

const overflow = await phone.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
if (overflow > 2) throw new Error(`el mapa desborda en movil: ${overflow}px`);

// The touch targets have to be usable with a thumb.
const small = await phone.locator('.lmap__node').evaluateAll((nodes) =>
  nodes.filter((node) => {
    const box = node.getBoundingClientRect();
    return box.width < 28 || box.height < 20;
  }).length,
);
if (small > 0) throw new Error(`${small} nodos con area tactil demasiado pequena`);

// Finding a book on a phone is the whole point: tap a shelf, read the copies.
await phone.locator('.lmap__node[data-kind="ESTANTE"]').first().click();
await phone.getByRole('heading', { name: 'Ejemplares' }).waitFor({ timeout: 10000 });
await shoot(phone, '07-mapa-movil-ejemplares.png');

// Reduced motion: the tilt must stay, the transition must go.
const reduced = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  reducedMotion: 'reduce',
  storageState: await desktop.storageState(),
});
const calm = await reduced.newPage();
await calm.goto(`${BASE}/mapa`, { waitUntil: 'networkidle' });
await calm.waitForSelector('.lmap__plan');
const motion = await calm.locator('.lmap__plan').evaluate(
  (el) => getComputedStyle(el).transitionDuration,
);
// Browsers report a zeroed transition as "0s" or "1e-05s" depending on the engine.
const slowest = Math.max(
  ...motion.split(',').map((value) => parseFloat(value) || 0),
);
if (slowest > 0.001) throw new Error(`prefers-reduced-motion ignorado: ${motion}`);
await shoot(calm, '08-mapa-sin-animacion.png');

await browser.close();
console.log(saved.join('\n'));
console.log(`OK ${saved.length} screenshots`);
