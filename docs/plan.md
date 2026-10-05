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

## Ronda 1 — Auth y roles — `done`

**Estado:** verificado el 2026-10-04. Backend 47/47 (auth+matriz de permisos), frontend 20/20,
`tsc` limpio, `oxlint` 0 avisos. Capturas en `docs/screenshots/r1-*`, salida real en
`docs/evidence/round-1/`.

**Decisiones tomadas al implementarla (importan para el resto de rondas):**

- La autorización vive **en `SecurityConfig`, por URL**, no en `@PreAuthorize`. Una anotación de
  método sólo se evalúa cuando hay un handler, así que un endpoint nuevo quedaba accesible por
  descuido. Con reglas de URL la matriz de roles puede probarse desde la ronda 1, aunque el
  módulo aún no exista.
- El cliente envía el token **crudo** de la cookie `XSRF-TOKEN`; hace falta desactivar el
  enmascarado BREACH (`CsrfTokenRequestAttributeHandler` con atributo `null`), porque el handler
  por defecto rechaza el valor que él mismo acaba de publicar.
- La matriz de permisos usa una **cuenta desechable** para los cambios de rol: usar una cuenta de
  rol la degradaba y falseaba todas las comprobaciones posteriores.
- El paquete `isbn` (ronda 5) avanza en paralelo sobre el mismo árbol. Este commit toca solo los
  ficheros de la ronda 1.

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

## Ronda 2 — Catálogo — `done`

**Estado:** verificado el 2026-10-04. Backend 145/145 (19 API + 15 dominio de catálogo),
frontend 43/43, `tsc` limpio, `oxlint` 0 avisos, `docker compose up` con los cuatro servicios
`healthy`. Capturas en `docs/screenshots/r2-*` (claro/oscuro x escritorio/móvil, con lista,
búsqueda, estado vacío, detalle y formulario), salida real en `docs/evidence/round-2/`.

**Decisiones que importan para las rondas siguientes:**

- Autores, editoriales y categorías viajan **por nombre** en la API: el personal teclea lo que
  sabe y el catálogo enlaza o crea la fila. Evita tres pantallas de administración aparte para
  una biblioteca que teclea los mismos cincuenta autores.
- Búsqueda sobre una columna desnormalizada `search_text` (minúsculas, sin acentos, con
  título + subtítulo + ISBN + autores). Postgres no trae `unaccent` y esto evita la extensión
  o un join por cada pulsación. Se recalcula en `Book.reindex()`.
- **No se pueden hacer dos `join fetch` de colecciones `List` a la vez**
  (`MultipleBagFetchException`): los créditos y las categorías se cargan por lotes con
  `@BatchSize`, dos consultas extra por página en vez de dos por libro.
- Orden con sintaxis **`campo:direccion`** y lista blanca de campos. `ignoreCase()` solo se
  aplica a columnas de texto: `lower(publication_year)` es SQL inválido y Postgres lo rechaza.
- Paginación **base 0**.
- ISBN: se reutiliza el value object `Isbn` ya existente del módulo `isbn`, que normaliza
  10 → 13; por eso el ISBN-10 de una edición ya registrada da 409.
- Migraciones `V*.sql` inmutables una vez aplicadas (Flyway lo comprueba). Está documentado en
  el README porque romperlo impide arrancar el backend.

**➡️ Siguiente:** Ronda 7 — Resiliencia, backups, export/import y cierre.

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

**Estado:** `done` (2026-10-04). Backend **181 tests verdes** (18 de `CopyCodeTest` + 17 de
`InventoryApiTest`), frontend **48 verdes**, `oxlint` 0 avisos, `tsc` limpio, 4 servicios `healthy`.

- Migración **`V7__inventory.sql`** (no `V4`): `locations` (sala/pasillo/estante/depósito + `x/y/z`
  para el mapa 3D de la ronda 6), `copies`, `copy_moves` y secuencia `copy_code_sequence`.
  Se numera `V7` porque `V6` (ISBN) ya estaba aplicada: Flyway rechaza migraciones fuera de orden.
- Códigos: `CopyCode` deriva un **EAN-13 interno** (`2000000000015`, dígito de control válido)
  a partir del id de secuencia, más el código legible `OL-0000000001` que es lo que codifica el QR.
- Etiqueta PNG por ejemplar servida por `GET /inventory/copies/{id}/label.png` con ZXing: EAN-13,
  QR, código legible y título.
- `GET /inventory/lookup?code=` acepta código legible o EAN-13 (cámara o scanner USB).
- El árbol de ubicaciones **acumula los ejemplares de los descendientes**: una sala no guarda
  ejemplares, así que sin el roll-up "Sala principal: 0" se leía como vacía junto a un estante lleno.
- Los traslados se registran en `copy_moves` y quedan consultables por ejemplar.
- Frontend: `frontend/src/api/inventory.ts`, `pages/Inventory.tsx` + `Inventory.scss`,
  sección de navegación restringida a BIBLIOTECARIO/ADMINISTRATIVO/ADMINISTRADOR.
  El lector no ve el enlace y recibe 403 en la API (matriz de permisos).
- **Desviación documentada:** el barcode se imprime como EAN-13 y no como Code 128. El EAN-13
  interno es el que el plan pide derivar, y es el símbolo que cualquier lector de códigos de
  librerías sabe usar; añadir Code 128 encima del mismo dato sería un segundo símbolo redundante.
- Capturas en `docs/screenshots/round-3/` (listado, filtro, alta masiva, códigos generados,
  ubicaciones, etiqueta imprimible, lector sin acceso, tema oscuro, móvil) y salida real en
  `docs/evidence/round-3/`.

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

**Estado:** `done` (2026-10-04). Backend **219 tests verdes** (16 de `LoanPolicyTest` + 20 de
`LoanApiTest`), frontend **51 verdes**, `oxlint` 0 avisos, `tsc` limpio, 4 servicios `healthy`.

- Migración **`V8__loans.sql`** (no `V5`, porque `V6` y `V7` ya estaban aplicadas): `loans`,
  `reservations`. Un índice único parcial (`copy_id` donde `returned_at is null`) es la garantía real
  de que dos mostradores no pueden prestar el mismo ejemplar a la vez.
- `LoanPolicy` contiene **todas** las reglas sin Spring ni base de datos, y cada rechazo lleva un
  código estable (`copy_not_available`, `reader_limit_reached`, `book_reserved_by_other_reader`,
  `renewal_limit_reached`, `loan_overdue`…) que la pantalla traduce a un mensaje. `LoanService` carga
  los hechos y deja decidir a la política: una sola implementación de las reglas.
- Los plazos se leen de `app_config` (`loans.days_default`, `loans.max_active_per_reader`,
  `loans.max_renewals`), sembradas en `V1`: se cambian sin redesplegar. `GET /loans/settings` los
  enseña en la cabecera de la pantalla.
- Vencimiento por **día completo**, no por hora: un préstamo devuelto a las 23:59 del día límite no
  está vencido.
- Reservas: cola por `created_at`, la de `/loans/queue` es del mostrador (staff) y
  `/loans/reservations` es solo la del propio lector. El orden de las reglas de seguridad importa:
  la regla de reservas va **antes** de `GET /loans/**` o el lector recibe 403.
- Lector de códigos (`frontend/src/hooks/useScanner.ts`): escáner USB por *wedge* de teclado
  (ráfaga corta + Enter), `BarcodeDetector` nativo cuando existe y campo manual. Sin `@zxing/browser`:
  no hizo falta.
- `GET /loans` devuelve por defecto **solo los activos**. Mezclaba los ya devueltos y la pantalla
  llegaba a decir "en plazo" de un libro que ya estaba en la estantería; el historial va aparte
  (`state=CLOSED`).
- Dos bugs encontrados por las capturas y cubiertos con test: prestar a un id de lector inexistente
  devolvía **500** por la clave foránea (ahora 404 `reader_not_found`), y en móvil el botón
  "Devolver" quedaba fuera de pantalla tras un scroll lateral (ahora cabe, con aserción en el script).
- Capturas en `docs/screenshots/round-4/` (mostrador vacío, ejemplar encontrado, prestado, renovado,
  devuelto, rechazo legible, oscuro y móvil) y salida real en `docs/evidence/round-4/`.

---

## Ronda 5 — Rellenado automático por ISBN

**Estado:** `done` (2026-10-04). Backend **111 tests verdes** y frontend **43 verdes**
(typecheck, oxlint y build limpios).

- Backend: `Isbn` (checksum y normalización ISBN-10/13), `ExternalBook` (modelo común),
  `OpenLibraryProvider` y `GoogleBooksProvider` (ambos con WireMock, sin salir a internet),
  `IsbnProviderChain` (fallback), `CircuitBreaker` y `GET /api/isbn/{isbn}` con caché en
  Postgres (`V6`).
- Frontend: `api/isbn.ts` (errores tipados: `invalid` / `unknown` / `unreachable`) y
  `IsbnLookupForm`, **conectado al formulario de libro**: el ISBN se teclea una sola vez,
  el resultado se muestra con su origen y exige confirmación, y al aplicar solo se
  rellenan los campos que el proveedor conoce (nunca borra trabajo manual).

Evidencia (salida de tests y capturas reales de los estados, claro y oscuro, más móvil)
en [`docs/evidence/round-5/`](evidence/round-5/).

**Objetivo:** escribir 13 dígitos y obtener la ficha completa.

- Migración `V6`: `isbn_cache` - **hecha**. Guarda también los fallos: sin eso, quien teclea
  un ISBN desconocido golpearía la API externa en cada intento.
- Proveedores: Open Library (sin key, **hecho**) y Google Books (opcional, **hecho**), ambos
  normalizados al modelo común `ExternalBook`. `IsbnProviderChain` los consulta en orden
  (Open Library primero, porque no necesita clave) y guarda en la caché qué proveedor
  respondió, para que la UI pueda indicar el origen de cada campo. Timeout de 5 s en ambos.
  Ambos degradan a vacío ante cualquier fallo -incluido el 429 de cuota de Google Books-
  sin propagar errores: una biblioteca sin internet, o sin cuota, debe poder dar de alta
  un libro a mano. Google Books **funciona sin API key**; `ISBN_GOOGLE_BOOKS_KEY` solo sube
  la cuota diaria, así que no hay que registrarse para usar el relleno automático.
- **Circuit breaker — hecho.** Tras 3 fallos consecutivos de un proveedor se le salta
  2 minutos, para que una API caída no cueste un timeout por cada ISBN tecleado. El estado
  es por proveedor (una API rota no bloquea a la otra) y solo cuentan los fallos que parecen
  del proveedor: un bug nuestro no debe abrir el circuito.
  El endpoint responde 400 con `code: invalid_isbn` si el checksum no cuadra y 404 si nadie
  conoce el ISBN, para que la UI ofrezca el formulario manual en ambos casos.
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

**Estado:** `done` (2026-10-04). Backend **226 tests verdes** (7 de `MapApiTest`), frontend **65
verdes** (14 de `map.test.ts`), `oxlint` 0 avisos, `tsc` limpio, 4 servicios `healthy`.

- `GET /api/map` devuelve **todo en una llamada**: límites del plano, y por nodo geometría
  (x/y/z + width/depth/height), número de ejemplares, ocupación por estado y los ejemplares que
  contiene. Un solo request porque el mapa se dibuja en el teléfono y cada ida a la red se nota.
- **La geometría se resuelve en el servidor.** x/y/z son nullables a propósito (una biblioteca
  registra sus estantes antes de que nadie mida la sala) y lo que falta se coloca con un
  despliegue automático, de modo que todos los clientes dibujan el mismo plano y nunca sale vacío.
- La API de ubicaciones ahora **persiste** la geometría que se le enviaba y la descartaba.
- El mapa es de lectura, así que `GET /map` lo puede ver cualquier sesión; los lectores son
  precisamente quien lleva el teléfono en la biblioteca. Abastecer estantes sigue siendo del
  personal (`/inventory/**`).
- **Desviación documentada:** el plan pedía `@react-three/fiber`. La escena se hace con
  `perspective` + `rotateX/rotateZ` en CSS, sin dependencias nuevas: para un plano de planta son
  unos metros y unas cajas, y sale más ligero, no hay WebGL que falle ni que probear con mocks, y
  la   lista gemela accesible es la misma información en texto. Si algún día hace falta rotación y zoom
  libres con cámara propia, el contrato del endpoint ya está.
- Accesibilidad: la lista gemela es la interfaz primaria, el `prefers-reduced-motion` quita la
  transición (comprobado por el script) y la selección encadenada sala → pasillo → estante →
  libro → ejemplar se puede hacer entera sin tocar el plano.
- Tres bugs encontrados por las capturas y cubiertos con test: `pathTo` entraba en bucle
  infinito con un ciclo de padres (hubiera colgado la pestaña), el suelo de la sala tapaba los
  estantes que estaban encima, y el plano empujaba la página 272px de lado en móvil.
- El script de capturas **falla si** el plano no está en 3D, si no cabe en el escenario, si un
  estante queda tapado, si un nodo es demasiado pequeño para el dedo o si la página desborda.
- Capturas en `docs/screenshots/round-6/` (general, ocupación, estante seleccionado, pasillo,
  oscuro, móvil, móvil con ejemplares y sin animación) y salida real en `docs/evidence/round-6/`.

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
