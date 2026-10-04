-- Baseline: key/value runtime configuration. Everything else references nothing yet,
-- so a fresh Postgres needs only this migration to boot the app.

CREATE TABLE app_config (
    key        TEXT PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed is idempotent so re-running Flyway on a restored volume never duplicates defaults.
INSERT INTO app_config (key, value) VALUES
    ('library.name',                    'Mi Biblioteca'),
    ('library.locale',                  'es'),
    ('loans.days_default',              '14'),
    ('loans.max_active_per_reader',     '5'),
    ('loans.max_renewals',              '2'),
    ('loans.renew_days',                '14'),
    ('reservations.hold_days',          '3'),
    ('inventory.barcode_prefix',        'OL'),
    ('isbn.providers',                  'openlibrary,googlebooks'),
    ('theme.default',                   'system')
ON CONFLICT (key) DO NOTHING;