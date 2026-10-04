# open-library-os — Plan de trabajo por rondas

- **Rama:** `develop` (todo el trabajo)
- **Spec:** [`docs/superpowers/specs/2026-10-04-open-library-os-design.md`](superpowers/specs/2026-10-04-open-library-os-design.md)
- **Leyenda de estado:** `planned` → `in progress` → `done`

Cada ronda termina con: código en `develop`, **evidencia real** (tests/build/healthCheck +
screenshot en las rondas de UI) y commit atómico. Ninguna ronda cierra solo con documentación.

---

## Principios de trabajo

1. **TDD** en backend y frontend: test rojo → mínimo código → verde.
2. **Un solo comando**: `docker compose up -d` levanta todo.
3. **Cero estado en RAM**: Postgres es la única fuente de verdad.
4. **SCSS únicamente**, con tokens y mixins; temas claro/oscuro desde la primera ronda.
5. **Verificación visual** con Playwright en cada ronda con UI.
6. **Deuda técnica**: cada ronda incluye refactor/métrica de calidad, no solo features.

---

## Ronda 0 — Cimientos (repo, backend, frontend, Docker) — `done`

**Estado:** verificado el 2026-10-04. `mvn test` 3/3, `vitest run` 13/13, `tsc -b --noEmit` limpio,
`oxlint` 0 avisos, 4 contenedores `healthy`, `down`/`up` sin pérdida de datos.

**Añadido tras el cierre:** `scripts/verify-restore.sh` demuestra que un backup se
restaura de verdad (dump → restore → comparación de filas en las 6 tablas) y lo
limpia aunque falle. Sin desbordamiento horizontal a 320/390/768/1440 px.
Evidencia en [`docs/evidence/round-0/`](evidence/round-0/) y capturas en
[`docs/screenshots/`](screenshots/).

**Objetivo:** un comando levanta un sistema vivo y verificable.

- Monorepo `backend/` + `frontend/` + `deploy/`, git en `develop`.
- Backend Spring Boot 4.1.1 / Java 25, Maven, PostgreSQL + Flyway, Actuator.
- Migración `V1` con `app_config`; seed idempotente.
- Frontend Vite + React + TS strict + SCSS con tokens, tema claro/oscuro, layout base,
  componentes núcleo (Button, Input, Card, Badge, EmptyState, ErrorState, Skeleton).
- `docker-compose.yml`: `db` + `backend` + `frontend` + `backup`, healthchecks y
  `restart: unless-stopped`, volúmenes `pgdata` y `backups`.
- README con instrucciones y credenciales por defecto.

**Entregable:** `docker compose up -d` → SPA en `:8080`, `/api/actuator/health` = `UP`,
migración aplicada. **Verificado** con `curl` + screenshot.

**Criterio de cierre:** tras `docker compose down` + `up -d` los datos siguen intactos y el
health vuelve a `UP`. (`down -v` sí borra, a proposito: es lavia de datos manual.)

---

## Ronda 1 — Auth y roles

**Objetivo:** cuatro roles con permisos reales, sesión persistente en BD.

- Migración `V2`: `users`, `audit_log`; `SPRING_SESSION` (Spring Session JDBC).
- Spring Security: login/logout, CSRF por cookie, rotación de sesión, `BCrypt`.
- `RolePermissions`: LECTOR / ADMINISTRATIVO / BIBLIOTECARIO / ADMINISTRADOR.
- Gestión de usuarios (CRUD) para ADMINISTRATIVO+; filtro de cambio de contraseña forzado.
- Frontend: `AuthContext`, `ProtectedRoute`, `RequireRole`, página de login, layout con
  navegación por rol, selector de tema persistido.
- Tests: matriz de permisos 4×N por endpoint, login/rotación, CSRF, sesión sobrevive reinicio.

**Entregable:** iniciar sesión como cada rol y ver exactamente su UI.

---

## Ronda 2 — Catálogo

**Objetivo:** CRUD completo de libros con autores múltiples, editoriales, categorías, ISBN.

- Migración `V3`: `publishers`, `authors`, `categories`, `books`, `book_authors`, `book_categories`.
- Búsqueda: texto libre (título/autor/ISBN), filtros por categoría, editorial, año, idioma,
  disponibilidad; paginación y orden; índice GIN/trigram para búsqueda tolerante.
- Frontend: `DataTable` con orden/paginación, formulario de libro (autores múltiples con chips),
  páginas de listado y detalle, estados vacío/carga/error.
- Tests: reglas de unicidad ISBN, asociación múltiple de autores, permisos de escritura,
  búsqueda y filtros.

**Entregable:** crear libro con 3 autores, encontrarlo por cada filtro.

---

## Ronda 3 — Ejemplares e inventario

**Objetivo:** N copias por libro con ubicación física y códigos imprimibles.

- Migración `V4`: `locations` (zona/pasillo/estante + xyz), `copies`, `copy_moves`.
- Generación de códigos: EAN-13 interno derivado, Code 128 para barcode, QR con ZXing.
- Endpoint de imagen de etiqueta (PNG) por ejemplar → imprimible desde el navegador.
- Traslado de ejemplar entre ubicaciones con registro en `copy_moves`.
- Frontend: árbol de ubicaciones, alta masiva de copias, etiquetas imprimibles, listado de inventario
  con filtros por estado/ubicación.
- Tests: unicidad de códigos, asignación de ubicación, historial de movimientos.

**Entregable:** cada ejemplar localizable físicamente por su código.

---

## Ronda 4 — Préstamos, reservas y lector de código de barras

**Objetivo:** ciclo de préstamo completo escaneando un código.

- Migración `V5`: `loans`, `reservations`.
- Reglas: no prestar ejemplar no disponible, no superar el límite del lector, no prestar si hay
  reservas pendientes de otro lector, renovación con tope, vencimiento configurable en `app_config`.
- Reservas: cola por orden de creación, aviso "disponible", canje al prestar.
- Lector: `BarcodeDetector` nativo cuando existe + fallback con `@zxing/browser`; soporte de
  scanner USB (teclado wedge) con auto-cierre y focus; pantalla de loans optimizada para tablet.
- Pantallas: préstamo, devolución, historial, mis préstamos, reservas.
- Tests: todas las reglas de préstamo como tests de dominio; integración de la máquina de estados.

**Entregable:** préstamo → vencimiento → renovación → devolución con escáner.

---

## Ronda 5 — Rellenado automático por ISBN

**Objetivo:** escribir 13 dígitos y obtener la ficha completa.

- Migración `V6`: `isbn_cache`.
- Proveedores: Open Library (sin key), Google Books (opcional), ambos normalizados a un modelo
  común `ExternalBook`. Caché en BD; timeout y circuit-breaker; si todo falla → formulario manual.
- Frontend: en el formulario de libro, botón "Buscar por ISBN" con preview antes de aplicar,
  indicador de origen de cada campo.
- Tests: checksum ISBN-10/13, parseo de respuestas con fixtures, fallback manual, caché.

**Entregable:** alta de un libro real con 3 llamadas a la API y 0 campos escritos a mano.

---

## Ronda 6 — Mapa 3D del sitio (mobile-first)

**Objetivo:** ver dónde está cada ejemplar, en 3D, desde el teléfono.

- Endpoint `GET /api/map` con árbol de ubicaciones + xyz + ocupación por estado.
- Escena WebGL con `@react-three/fiber`: zonas, pasillos, estantes; selección encadenada
  zona → estante → libro → ejemplar; resaltado de estante objetivo con ruta.
- Mobile-first: controles táctiles grandes, lista gemela accesible (2D/ARIA) y sin WebGL
  (fallback DOM), `prefers-reduced-motion` respetado.
- Tests: endpoint (contrato), componentes de escena con mock de WebGL, fallback sin WebGL.

**Entregable:** localizar un ejemplar concreto en el mapa desde un móvil.

---

## Ronda 7 — Resiliencia, backups y cierre

**Objetivo:** el sistema aguanta caidas y se puede migrar de instancia.

- Servicio `backup`: `pg_dump` diario, retención 14 diarios + 4 semanales, verificación de
  integridad al restaurar.
- `GET /api/admin/export` (JSON versionado) y `POST /api/admin/import` (upsert).
- Script `scripts/verify-restore.sh`: restaura un backup en limpio y compara conteos.
- **Hardening:** CSP, límites de subida, rate limit de login, `X-Content-Type-Options`.
- Pulido final: Lighthouse/a11y, texts real, README completo, `LICENSE`, `CONTRIBUTING`.
- Test E2E de resiliencia: reinicio de contenedores con sesión activa y datos.

**Entregable:** `down`/`up` y "migrar a otra máquina" demostrados con output pegado.

---

## Métricas de calidad (revisadas cada ronda)

- Backend: tests verdes, 0 warnings de compilación, `ruff`-style cleanliness no aplica (Java);
  `mvn -q verify` como puerta.
- Frontend: `tsc --noEmit` limpio, ESLint limpio, sin `any` implícito.
- UX: 0 problemas de accesibilidad críticos en las pantallas nuevas (Playwright + axe).
- Deuda: todo lo que se difiere queda anotado en `docs/plan.md` con motivo.
