// Visual QA: captures each round's screens in both themes and at mobile width.
// Usage: node scripts/screenshot.mjs [baseUrl] [outDir] [label]
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const baseUrl = process.argv[2] ?? 'http://127.0.0.1:8090';
const outDir = process.argv[3] ?? '../docs/screenshots';
const label = process.argv[4] ?? 'round';

const ROUTES = [['home', '/']];
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 960 },
  { name: 'mobile', width: 390, height: 844 },
];

await mkdir(outDir, { recursive: true });

const browser = await chromium.launch();
const errors = [];

for (const [route, path] of ROUTES) {
  for (const theme of ['light', 'dark']) {
    for (const viewport of VIEWPORTS) {
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height },
        colorScheme: theme,
        deviceScaleFactor: 2,
      });
      const page = await context.newPage();
      page.on('console', (m) => m.type() === 'error' && errors.push(`[${route}/${theme}] ${m.text()}`));
      page.on('pageerror', (e) => errors.push(`[${route}/${theme}] ${e.message}`));

      await page.goto(baseUrl + path, { waitUntil: 'networkidle' });
      await page.waitForTimeout(350);

      const file = `${outDir}/${label}-${route}-${theme}-${viewport.name}.png`;
      await page.screenshot({ path: file, fullPage: true });
      console.log('saved', file);
      await context.close();
    }
  }
}

await browser.close();

if (errors.length) {
  console.error('\nconsole errors:\n' + errors.join('\n'));
  process.exit(1);
}
console.log('\nno console errors');