// Visual QA for the catalogue round: walks the real UI and captures the list,
// the multi-author form, the detail page and the empty state.
// Usage: node scripts/screenshot-catalog.mjs [baseUrl] [outDir] [label]
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const baseUrl = process.argv[2] ?? 'http://127.0.0.1:8090';
const outDir = process.argv[3] ?? '../docs/screenshots';
const label = process.argv[4] ?? 'r2';

const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 960 },
  { name: 'mobile', width: 390, height: 844 },
];

await mkdir(outDir, { recursive: true });

const browser = await chromium.launch();
const problems = [];
let newPasswordUsed = false;

async function shoot(page, name) {
  await page.waitForTimeout(300);
  const file = `${outDir}/${label}-${name}.png`;
  await page.screenshot({ path: file, fullPage: true });
  console.log('saved', file);
}

async function signIn(page) {
  await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' });
  await page.waitForSelector('input[name="email"]', { timeout: 20_000 });

  for (const candidate of ['NuevaClave2026', 'ChangeMe!2026']) {
    await page.fill('input[name="email"]', 'admin@local');
    await page.fill('input[name="password"]', candidate);
    await page.click('button[type="submit"]');

    // Success means we left the login screen, whatever the landing page is.
    const left = await page
      .waitForSelector('input[name="email"]', { state: 'detached', timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    if (!left) continue;

    newPasswordUsed = candidate === 'NuevaClave2026';
    if (await page.locator('h1:has-text("Cambia tu contrasena")').count()) {
      const fields = page.locator('input[type="password"]');
      await fields.nth(0).fill(candidate);
      await fields.nth(1).fill('NuevaClave2026');
      await fields.nth(2).fill('NuevaClave2026');
      await page.click('button[type="submit"]');
      await page.waitForSelector('input[name="email"]', { state: 'detached', timeout: 20_000 });
    }
    return;
  }
  throw new Error('could not sign in');
}

for (const theme of THEMES) {
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      colorScheme: theme,
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    page.on('console', (m) => {
      if (m.type() === 'error' && !/status of 401/.test(m.text())) {
        problems.push(`[${theme}] ${m.text()}`);
      }
    });
    page.on('pageerror', (e) => problems.push(`[${theme}] ${e.message}`));

    const tag = `${theme}-${viewport.name}`;

    await signIn(page);

    // 1. Catalogue list with filters.
    await page.goto(`${baseUrl}/catalogo`, { waitUntil: 'networkidle' });
    await page.waitForSelector('h1:has-text("Libros")', { timeout: 20_000 });
    await shoot(page, `catalog-list-${tag}`);

    // 2. Search that actually filters.
    await page.fill('input[type="search"]', 'marquez');
    await page.click('button[type="submit"]');
    await page.waitForSelector('td:has-text("Cien")', { timeout: 20_000 });
    await shoot(page, `catalog-search-${tag}`);

    // 3. Empty state for a search with no hits.
    await page.fill('input[type="search"]', 'zzzzz-no-existe');
    await page.click('button[type="submit"]');
    await page.waitForSelector('h3:has-text("No hay libros")', { timeout: 20_000 });
    await shoot(page, `catalog-empty-${tag}`);

    // 4. Detail page.
    await page.goto(`${baseUrl}/catalogo?q=Cien`, { waitUntil: 'networkidle' });
    await page.waitForSelector('td', { timeout: 20_000 });
    await page.click('tbody tr');
    await page.waitForSelector('h1:has-text("Cien")', { timeout: 20_000 });
    await shoot(page, `catalog-detail-${tag}`);

    // 5. Multi-author form with two credits filled in.
    await page.goto(`${baseUrl}/catalogo/nuevo`, { waitUntil: 'networkidle' });
    await page.waitForSelector('h1:has-text("Nuevo libro")', { timeout: 20_000 });
    await page.fill('input >> nth=0', 'El nombre de la rosa');
    await page.fill('input >> nth=1', 'Un monasterio y un asesinato');
    await page.fill('input >> nth=2', '9788426403568');
    await page.click('button:has-text("Anadir")');
    await page.fill('input[aria-label="Nombre del autor 2"]', 'Umberto Eco');
    const firstCategory = page.locator('.bookform__chip').first();
    if (await firstCategory.count()) await firstCategory.click();
    await shoot(page, `catalog-form-${tag}`);

    await context.close();
  }
}

await browser.close();

if (problems.length) {
  console.error('\nCONSOLE ERRORS:\n' + problems.join('\n'));
  process.exit(1);
}
console.log('\nno console errors (password used: ' + newPasswordUsed + ')');
