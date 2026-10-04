// Visual QA for the authenticated screens: walks the real login flow in a real
// browser and captures each screen in both themes and at mobile width.
//
// Idempotent on purpose: the first run has to change the seeded password, and
// later runs must still get in. So we remember whichever password worked.
// Usage: node scripts/screenshot-auth.mjs [baseUrl] [outDir] [label]
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const baseUrl = process.argv[2] ?? 'http://127.0.0.1:8090';
const outDir = process.argv[3] ?? '../docs/screenshots';
const label = process.argv[4] ?? 'r1';

const EMAIL = process.env.DEMO_EMAIL ?? 'admin@local';
const SEED_PASSWORD = process.env.DEMO_PASSWORD ?? 'ChangeMe!2026';
const NEW_PASSWORD = 'NuevaClave2026';

const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 960 },
  { name: 'mobile', width: 390, height: 844 },
];

/** Password known to work, discovered on the first successful login. */
let workingPassword = SEED_PASSWORD;

/** The forced password change only shows once, on the very first login. */
let gateCaptured = false;

async function shoot(page, name, options = {}) {
  await page.waitForTimeout(300);
  const file = `${outDir}/${label}-${name}.png`;
  await page.screenshot({ path: file, fullPage: options.fullPage ?? true });
  console.log('saved', file);
}

/** Signs in and clears the forced password change if it is still pending. */
async function signIn(page, tag) {
  await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' });
  await page.waitForSelector('input[name="email"]', { timeout: 20_000 });

  for (const candidate of [workingPassword, NEW_PASSWORD, SEED_PASSWORD]) {
    await page.waitForSelector('input[name="email"]', { timeout: 20_000 });
    await page.fill('input[name="email"]', EMAIL);
    await page.fill('input[name="password"]', candidate);
    await page.click('button[type="submit"]');

    const changeWall = await page
      .waitForSelector('h1:has-text("Cambia tu contrasena")', { timeout: 8_000 })
      .then(() => true)
      .catch(() => false);
    const home = await page
      .waitForSelector('h1:has-text("Tu biblioteca")', { timeout: 8_000 })
      .then(() => true)
      .catch(() => false);

    if (home) {
      workingPassword = candidate;
      return;
    }
    if (changeWall) {
      workingPassword = NEW_PASSWORD;
      if (!gateCaptured) {
        gateCaptured = true;
        await shoot(page, `force-password-${tag}`);
      }
      const fields = page.locator('input[type="password"]');
      await fields.nth(0).fill(candidate);
      await fields.nth(1).fill(NEW_PASSWORD);
      await fields.nth(2).fill(NEW_PASSWORD);
      await page.click('button[type="submit"]');
      await page.waitForSelector('h1:has-text("Tu biblioteca")', { timeout: 20_000 });
      return;
    }
  }

  throw new Error('could not sign in with any known password');
}
await mkdir(outDir, { recursive: true });

const browser = await chromium.launch();
const problems = [];

for (const theme of THEMES) {
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      colorScheme: theme,
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    page.on('console', (m) => {
      // 401s are part of what we exercise (anonymous session, wrong password).
      if (m.type() === 'error' && !/status of 401/.test(m.text())) {
        problems.push(`[${tag}] ${m.text()}`);
      }
    });
    page.on('pageerror', (e) => problems.push(`[${tag}] ${e.message}`));

    const tag = `${theme}-${viewport.name}`;

    // 1. Login screen, before any credentials.
    await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' });
    await page.waitForSelector('input[name="email"]', { timeout: 20_000 });
    await shoot(page, `login-${tag}`);

    // 2. Wrong credentials: the error state must be legible, not a blank screen.
    await page.fill('input[name="email"]', EMAIL);
    await page.fill('input[name="password"]', 'definitely-wrong');
    await page.click('button[type="submit"]');
    await page.waitForSelector('[role="alert"]', { timeout: 20_000 });
    await shoot(page, `login-error-${tag}`);

    await signIn(page, tag);
    await shoot(page, `home-${tag}`);

    // 4. User management.
    await page.goto(`${baseUrl}/cuentas`, { waitUntil: 'networkidle' });
    await page.waitForSelector('h1:has-text("Cuentas")', { timeout: 20_000 });
    await shoot(page, `users-${tag}`);

    // 5. Navigation with the drawer open on mobile. Viewport-only: a full-page
    //    capture would detach the fixed drawer and fake a layout bug.
    if (viewport.name === 'mobile') {
      await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' });
      await page.click('button[aria-label="Abrir navegacion"]');
      await shoot(page, `nav-open-${tag}`, { fullPage: false });
    }

    await context.close();
  }
}

await browser.close();

if (problems.length) {
  console.error('\nCONSOLE ERRORS:\n' + problems.join('\n'));
  process.exit(1);
}
console.log('\nno console errors');
