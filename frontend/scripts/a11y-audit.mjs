// Accessibility audit of every screen, for both roles. Fails on any serious or
// critical violation, because those are the ones that stop somebody using the
// library; the rest are reported so they can be judged.
// Usage: node scripts/a11y-audit.mjs [baseUrl] [outFile]
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import AxeBuilder from '@axe-core/playwright';
import { chromium } from 'playwright';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8090';
const OUT = process.argv[3]
  ? process.argv[3]
  : fileURLToPath(new URL('../../docs/evidence/round-10/axe-report.json', import.meta.url));

const STAFF = { email: 'admin@local', password: 'NuevaClave2026' };
const READER = { email: process.argv[4] ?? 'lector@local', password: 'LectorClave2026' };

const SCREENS = [
  ['/', 'inicio'],
  ['/catalogo', 'catalogo'],
  ['/inventario', 'inventario'],
  ['/prestamos', 'prestamos'],
  ['/mapa', 'mapa'],
  ['/cuentas', 'cuentas'],
  ['/mi-biblioteca', 'mi-biblioteca'],
  ['/mi-contrasena', 'mi-contrasena'],
  ['/ajustes/datos', 'tus-datos'],
];

const SERIOUS = new Set(['critical', 'serious']);

/** Both themes: a palette can pass in the light and fail in the dark. */
const THEMES = ['light', 'dark'];

async function signIn(page, credentials) {
  await page.goto(`${BASE}/entrar`, { waitUntil: 'networkidle' });
  for (const password of [credentials.password, 'ChangeMe!2026']) {
    await page.fill('input[name="email"]', credentials.email);
    await page.fill('input[name="password"]', password);
    await page.click('button[type="submit"]');
    const left = await page
      .waitForSelector('input[name="email"]', { state: 'detached', timeout: 8000 })
      .then(() => true)
      .catch(() => false);
    if (left && !page.url().includes('/entrar')) {
      await page.waitForSelector('nav a', { timeout: 20000 });
      return;
    }
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
}

const browser = await chromium.launch();
const report = [];
const blocking = [];

for (const [who, credentials] of [
  ['staff', STAFF],
  ['lector', READER],
]) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  try {
    await signIn(page, credentials);
  } catch (error) {
    report.push({ who, screen: 'login', error: String(error).slice(0, 200) });
    blocking.push(`${who}: no se pudo iniciar sesion (${String(error).slice(0, 80)})`);
    await context.close();
    continue;
  }

  for (const [route, name] of SCREENS) {
    for (const theme of THEMES) {
      await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle' });
      await page.evaluate((chosen) => localStorage.setItem('theme', chosen), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.reload({ waitUntil: 'networkidle' });
      await page.waitForTimeout(500);

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      for (const violation of results.violations) {
        const entry = {
          who,
          theme,
          screen: name,
          id: violation.id,
          impact: violation.impact,
          help: violation.help,
          nodes: violation.nodes.slice(0, 3).map((node) => ({
            target: node.target.join(' '),
            summary: (node.failureSummary ?? '').split('\n').slice(0, 2).join(' '),
          })),
        };
        report.push(entry);
        if (violation.impact && SERIOUS.has(violation.impact)) {
          blocking.push(
            `${who}/${theme}/${name}: ${violation.id} (${violation.impact}) - ${violation.help}`,
          );
        }
      }
      console.log(`${who}/${theme}/${name}: ${results.violations.length} violaciones`);
    }
  }
  await context.close();
}

await browser.close();
await writeFile(OUT, JSON.stringify(report, null, 2));

console.log('');
console.log(`informe escrito en ${OUT}`);
if (blocking.length > 0) {
  console.log('');
  console.log('BLOQUEANTES:');
  for (const line of blocking) console.log(`  ${line}`);
  process.exitCode = 1;
} else {
  console.log('sin violaciones criticas ni serias');
}
