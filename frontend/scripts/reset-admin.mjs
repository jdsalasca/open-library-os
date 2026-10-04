// Puts the seeded admin account back to its documented state so QA runs start
// from a known point: password "ChangeMe!2026" with the forced change pending.
//
// The hash is BCrypt(cost 10) of that exact password. Only the local QA account
// is touched; a real deployment rotates it on first login.
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HASH = '$2a$10$fPZEo30aDN5HBd.uP.S.ZOSywf0cUl2UxIyNVTJDgO9KPFusAIdQ.';

// Repo root, so the script works from any directory.
const composeDir = fileURLToPath(new URL('../../', import.meta.url));

execFileSync(
  'docker',
  [
    'compose',
    'exec',
    '-T',
    'db',
    'psql',
    '-U',
    process.env.DB_USER ?? 'openlibrary',
    '-d',
    process.env.DB_NAME ?? 'openlibrary',
    '-c',
    `update users set password_hash = '${HASH}', must_change_password = true, active = true`
      + ` where email = '${process.env.DEMO_EMAIL ?? 'admin@local'}';`,
  ],
  { stdio: 'inherit', cwd: composeDir },
);

console.log('admin reset to the seeded password');
