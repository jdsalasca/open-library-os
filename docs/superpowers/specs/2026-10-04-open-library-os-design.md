# open-library-os — Especificación de diseño

- **Fecha:** 2026-10-04
- **Estado:** aprobado por autoconvergencia (usuario delegó autonomía completa)
- **Rama:** `develop`

---

## 1. Propósito y criterios de éxito

Aplicación self-hosted de gestión de librerías. Cualquier librería en el mundo la descarga de
GitHub, ejecuta **un comando** (`docker compose up -d`) y queda operativa, con **ownership total
de sus datos** (Postgres con volumen + export/import + backups automáticos), sin soporte externo.

Criterios de éxito (verificables):

1. `git clone && docker compose up -d` → UI accesible y `/api/actuator/health` en `UP`.
2. Reinicio del contenedor (o caída de la BD) → **cero pérdida de datos** y sesiones activas
   sobreviven (sesiones en BD).
3. Cada ronda entrega algo **usable de punta a punta** (no esqueletos), verificado con ejecución
   real y, en las rondas de UI, con screenshots.
4. Préstamo y devolución funcionan leyendo un código de barras (cámara o scanner USB).
5. El 100 % del estado vive en Postgres; ninguna dependencia de estado en RAM.
6. Base de pruebas: backend con TDD (JUnit 5 + Testcontainers), frontend con Vitest,
   E2E/screenshots con Playwright.

## 2. Enfoques considerados y decisión

| Enfoque | Decisión |
|---|---|
| **A. Monorepo: Spring Boot + Vite separados, nginx sirve SPA y proxea `/api`** | **ELEGIDO.** Separación limpia, builds rápidos, caché y gzip de nginx, proxy same-origin (cookies first-party). |
| B. Frontend compilado dentro del `jar` | Descartado: cada cambio de CSS obliga a recompilar Java; menos flexible. |
| C. SPA servida por Spring Boot directamente | Descartado: sin proxy, mezcla responsabilidades de UI y API. |
| Auth: **JWT** vs **sesión en cookie** | **Cookie + Spring Session JDBC.** Sin secretos que rotar, revocable (borrar fila = desloguear), sobrevive reinicios, y same-origin detrás de nginx. JWT solo se justificaría para clientes nativos — no es requisito. |
| Mapa 3D: **WebGL (react-three-fiber)** vs **isométrico CSS/SVG** | **WebGL.** El requisito es "vista 3D navegable"; se usa librería madura en vez de escribir un motor 3D. |

## 3. Arquitectura

```
┌──────────────┐   /api/*  ┌─────────────────┐   JDBC   ┌──────────────┐
│   frontend   │──────────▶│  backend        │─────────▶│  postgres    │
│  nginx:8080  │           │  Spring Boot    │          │  :5432       │
│  (SPA+proxy) │           │  :8080          │◀─────────│  vol pgdata  │
└──────────────┘           └─────────────────┘          └──────────────┘
                                    ▲                          ▲
                                    └──── backup (pg_dump) ────┘
                                          servicio `backup`
```

- **backend/** — Maven, Java 25 (Temurin), Spring Boot 4.1.1, Spring Web, Spring Security,
  Spring Data JPA, Spring Session JDBC, Flyway, PostgreSQL driver, Validation, Actuator,
  ZXing (códigos de barras/QR).
- **frontend/** — Vite 7, React 19, TypeScript (strict), React Router, SCSS con tokens/mixins,
  tema claro/oscuro, TanStack Query (cache de servidor), `@react-three/fiber` (ronda 6).
- **deploy/** — Dockerfile multi-stage por servicio + `docker-compose.yml` en la raíz.

### 3.1 Fronteras de módulo (backend, paquetes por feature)

```
com.openlibrary
├── shared/          # errores, paginación, auditoría, config común
├── auth/            # usuarios, roles, sesión, CSRF
├── catalog/         # libros, autores, editoriales, categorías
├── inventory/       # ubicaciones, ejemplares, códigos
├── loans/           # préstamos, reservas
├── isbn/            # proveedores externos
├── mapdata/         #Feed para el mapa 3D
└── backup/          # export/import
```

Regla: cada módulo expone DTOs y casos de uso; **nunca** se exponen entidades JPA.
Prohibido el acceso directo a repositorios de otro módulo.

### 3.2 Frontend

```
frontend/src/
├── api/           # cliente fetch (credentials, CSRF), endpoints tipados por módulo
├── design/        # _tokens.scss, _mixins.scss, _themes.scss, reset
├── components/    # design system reusable (Button, Input, DataTable, Modal, …)
├── features/      # auth/, catalog/, inventory/, loans/, isbn/, map/
├── pages/         # una página por ruta
├── hooks/         # useAuth, useTheme, useApi…
└── routes.tsx
```

Regla: **SCSS siempre** — tokens en `:root`/`[data-theme='dark']`, cero CSS suelto y cero
estilos inline salvo valor dinámico (p. ej. posición 3D).

## 4. Modelo de datos (Postgres, Flyway `V*__*.sql`)

Convenciones: `id BIGINT GENERATED ALWAYS AS IDENTITY` PK, `created_at`/`updated_at TIMESTAMPTZ`,
borrado lógico solo donde tiene valor de negocio (`active`, `status`); FK con `ON DELETE RESTRICT`
salvo en tablas puente (`ON DELETE CASCADE`).

| Tabla | Campos clave | Ronda |
|---|---|---|
| `app_config` | `key PK`, `value TEXT`, `updated_at` | 0 |
| `users` | `email CITEXT UNIQUE`, `password_hash`, `full_name`, `role`, `active`, `last_login_at` | 1 |
| `audit_log` | `user_id`, `action`, `entity`, `entity_id`, `details JSONB`, `at` | 1 |
| `publishers` | `name UNIQUE`, `country` | 2 |
| `authors` | `name`, `sort_name`, `bio`, `external_ids JSONB` | 2 |
| `categories` | `name`, `slug UNIQUE`, `parent_id` (árbol) | 2 |
| `books` | `isbn13 UNIQUE`, `isbn10`, `title`, `subtitle`, `publisher_id`, `publication_year`, `language`, `pages`, `summary`, `cover_url`, `edition` | 2 |
| `book_authors` | `(book_id, author_id, role, position)` | 2 |
| `book_categories` | `(book_id, category_id)` | 2 |
| `locations` | `name`, `kind ZONE\|AISLE\|SHELF\|DESK`, `parent_id`, `sort_order`, `x,y,z`, `width,depth,height` (3D) | 3 |
| `copies` | `book_id`, `code UNIQUE`, `barcode UNIQUE`, `qr UNIQUE`, `location_id`, `status AVAILABLE\|LOANED\|MAINTENANCE\|LOST`, `acquired_at` | 3 |
| `copy_moves` | `copy_id`, `from_location_id`, `to_location_id`, `at`, `user_id` | 3 |
| `loans` | `copy_id`, `reader_id`, `loaned_at`, `due_at`, `returned_at`, `renew_count`, `status`, `created_by`, `notes` | 4 |
| `reservations` | `book_id`, `reader_id`, `status`, `created_at`, `fulfilled_loan_id` | 4 |
| `isbn_cache` | `isbn PK`, `payload JSONB`, `source`, `status`, `fetched_at` | 5 |

El historial de acciones de negocio (altas, devoluciones, cambios) se apoya en `audit_log`;
no se duplica en tablas de histórico por entidad.

## 5. Roles y permisos

Rol único en `users.role`; los permisos se derivan en código (`RolePermissions`), no en tablas
(un conjunto de 4 roles fijos no justifica RBAC genérico).

| Rol | Puede |
|---|---|
| `LECTOR` | Ver catálogo, sus préstamos, su historial, crear reservas |
| `BIBLIOTECARIO` | Todo lo de lector + préstamos (alta/devolución/renovación), inventario, cadastro rápido |
| `ADMINISTRATIVO` | Todo lo de bibliotecario + CRUD de catálogo, ubicaciones, usuarios, reportes, export/import |
| `ADMINISTRADOR` | Todo + configuración del sistema, gestión de roles, backups |

Reglas transversales:
- Contraseña ≥ 10 chars; hash **BCrypt** (coste 10).
- Seed idempotente: admin por defecto `admin@local / ChangeMe!2026`, **forzado a cambiar** en el primer login.
- CSRF habilitado (`CookieCsrfTokenRepository`, header `X-XSRF-TOKEN`) porque hay sesión por cookie.
- Fijación de sesión: rotación de `SESSIONID` en login.

## 6. Resiliencia (requisito crítico)

1. **Cero estado en RAM.** Sesiones en `SPRING_SESSION` (Postgres); caché de ISBN en tabla.
2. **Recuperación en frío.** Al arrancar: Flyway migra, seeds idempotentes, healthcheck.
3. **Reinicio sin pérdida.** Postgres en volumen nombrado `pgdata`; `restart: unless-stopped`.
4. **Backup automático.** Servicio `backup`: `pg_dump` diario a volumen `backups` con retención
   (14 diarios + 4 semanales). Un solo compose.
5. **Migración entre instancias.** `GET /api/admin/export` (JSON con versionado de esquema) e
   `POST /api/admin/import` con upsert por clave natural.
6. **Secretos.** Sin secretos embebidos: todo por variable de entorno con defaults seguros
   de desarrollo documentados.
7. **Healthchecks reales:** `pg_isready`, actuator `health`, `wget /healthz` de nginx.

## 7. Errores y observabilidad

- `@RestControllerAdvice` único → `ProblemDetail` (RFC 9457) con `code`, `fieldErrors[]`.
- El frontend tiene un único traductor de error → estados vacío/error/carga consistentes.
- `audit_log` para toda mutación de negocio con `user_id`.
- Logs con request-id por request (filtro `OncePerRequestFilter`).

## 8. Estrategia de pruebas (TDD)

| Capa | Herramienta | Qué cubre |
|---|---|---|
| Dominio | JUnit 5 puro | Reglas de préstamo, renovación, ISBN (checksums) |
| Integración | `@SpringBootTest` + Testcontainers Postgres | Repositorios, Flyway, seguridad por rol |
| Frontend | Vitest + Testing Library | Componentes del design system, reglas de UI |
| E2E | Playwright | Flujos: login → catálogo → préstamo → devolución; screenshots por ronda |

TDD en backend: test rojo → mínimo código → verde. Un test por regla de negocio, no por método.

## 9. Fuera de alcance (YAGNI)

Multitenancy, facturación, multidioma de la UI (i18n), app nativa, impresión de etiquetas en
lote, notificaciones push, OAuth/SSO, API pública de terceros. Añadir cuando se pidan.