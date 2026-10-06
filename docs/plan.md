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

**Estado:** `done` (2026-10-05). Backend **245 tests verdes** (8 de `ExportImportApiTest`, 8 de
`LoginThrottleTest`, 2 de login en `AuthApiTest`), frontend **65 verdes**, `oxlint` 0 avisos,
`tsc` limpio, 4 servicios `healthy`.

- **Migrar a otra máquina:** `GET /api/admin/export` y `POST /api/admin/import`. El documento lleva
  `schemaVersion`; una exportación de una versión más moderna se rechaza con
  `unsupported_schema_version` en vez de importarse a medias.
- El import es un **upsert por clave natural** (editorial por nombre, categoría por slug, estante por
  código, ejemplar por su código impreso, libro por ISBN y si no por título, préstamo por
  ejemplar+fecha). Por eso importar dos veces la misma exportación deja la biblioteca idéntica, que
  es la diferencia entre un respaldo en el que se puede confiar y uno que se restaura a ver qué pasa.
- **Las cuentas viajan con sus hashes**, incluidos los `password_hash`: si no, migrar sería pedirle
  a cada lector que se vuelva a registrar.
- `scripts/verify-restore.sh` (165 líneas) restaura el dump más reciente del volumen en una base
  temporal **dentro** del contenedor `db`, compara 8 tablas con la base viva y comprueba dos
  invariantes. Ejecutado de verdad: 18 tablas y conteos idénticos, `EXIT=0`. El agente que lo
  escribió dejó además una corrida de control negativa (un dump vacío → `EXIT=1`) porque un script
  que siempre dice "OK" es indistinguible de uno que funciona.
- **Bug real de backups encontrado por ese script:** el primer `pg_dump` tras crear el volumen salía
  antes de que Flyway migrara, y `backup.sh` lo aceptaba porque `pg_restore --list` no distingue
  "corrupto" de "base vacía". El healthcheck decía `healthy` con un único punto de restauración que
  no restauraba nada. Ahora un dump sin la tabla `users` se descarta (verificado: dump vacío de 15
  entradas → rechazado; dump real de 152 → aceptado).
- **Hardening:** CSP estricta (`default-src 'self'`, sin `unsafe-inline` en scripts), COOP,
  `Permissions-Policy` con `camera=(self)` (el lector de la ronda 4 la necesita), `nosniff`,
  `X-Frame-Options`, `Referrer-Policy`, límite de subida 64 MB y rate limit de login.
- El rate limit tiene **dos claves con umbrales distintos**: 5 fallos por cuenta y 50 por dirección.
  Con un solo umbral, la primera contraseña mal escrita bloqueaba a toda la biblioteca detrás de
  una NAT. El bloqueo es en memoria y caduca a los 15 minutos; no ensucia las cuentas con estado que
  pueda dejarlas atascadas.
- **Bug real de nginx:** un `add_header` dentro de un `location` cancela todos los `add_header`
  heredados del `server`. Los `Cache-Control` de `/assets/` y de `/` se estaban comiendo las
  cabeceras de seguridad, que **nunca se habían servido**. Cambiado a `expires`, que no compite con
  la herencia. Ahora se verifican en el documento y en un asset con hash.
- La CSP foothole al script anti-flash de `index.html`: ahora es `public/theme.js`, un fichero
  externo. Comprobado que el tema se sigue resolviendo antes del primer pintado en claro y oscuro.
- `LICENSE` es el texto íntegro de **AGPL-3.0**, que es lo que el README ya prometía. Y
  `CONTRIBUTING.md`.
- **`strict: true` activado** en `tsconfig.app.json`: el README y este plan lo afirmaban y no era
  cierto. El código ya estaba limpio, así que no hizo falta tocar ni una línea de `src`.
- **CI en `.github/workflows/ci.yml`**: dos jobs (backend con JDK 25 y Testcontainers, frontend
  con Node 24) ejecutando exactamente los comandos que se ejecutan a mano, en `push` y `pull_request`
  sobre `develop`.
- Salida real en `docs/evidence/round-7/` y capturas de las seis pantallas con la CSP activa en
  `docs/screenshots/round-7/`.

**Nota para la siguiente ronda:** la siguiente migración es **`V9`**. Hay huecos (`V4` y `V5` no
existen) y Flyway rechaza migraciones fuera de orden aunque el número esté libre.

---

## Ronda 8 — El rincón del lector

**Objetivo:** que quien tiene el carnet pueda ver y managear sus préstamos y sus reservas. Estaba
prometido en la ronda 4 ("mis préstamos, reservas") y no existía: el backend no exponía nada al lector
y no había ninguna pantalla suya.

- `GET /loans/mine` devuelve en una llamada lo que tiene prestado, lo que ha devuelto y sus reservas
  con **puesto en la cola** y si el libro está ya en la estantería. El lector sale de la sesión, nunca
  de la petición.
- Cancelar una reserva ajena responde **404**, no 403: el lector no tiene por qué saber que existe.
- Regla nueva: **no se reserva un libro que ya está en la estantería** (`book_available`). Una entrada
  en la cola bloquea el préstamo de ese ejemplar a cualquier otro lector, así que se le manda al
  mostrador en vez de dejarle esperando un libro que ya puede coger.
- Pantalla `/mi-biblioteca` para todo el que tenga sesión, con la navegación limitada por rol: el
  lector ve Inicio, Catálogo, Mi biblioteca y Mapa, y nada del mostrador.
- Botón **Reservar** en la ficha de libro, que explica el rechazo en lugar de dejar un identificador
  en pantalla.
- Las fechas se leen en palabras ("Quedan 14 días", "Vence mañana") y se ponen en ámbar a tres días
  y en rojo al vencerse, contando **días completos**.

**Estado:** `done` (2026-10-05). Backend **255 tests verdes** (10 de `MyLibraryApiTest`), frontend
**70 verdes**, `oxlint` 0 avisos, `tsc` limpio, 4 servicios `healthy`.

- Al añadir la regla de "no reservar lo que está disponible" hubo que corregir dos tests de la ronda 4
  que reservaban con ejemplares en la estantería: montaban una situación imposible.
- Capturas en `docs/screenshots/round-8/` (préstamos, ficha con reserva ya hecha, reserva rechazada
  con su motivo, cola, oscuro y móvil) y salida real en `docs/evidence/round-8/`.

---

## Ronda 9 — Tus datos, accesibilidad y documentación

**Objetivo:** que la propiedad de los datos sea una botón y no una promesa, y comprobar con una
herramienta real que la interfaz es usable.

- **Ajustes > Tus datos** (`/ajustes/datos`, solo ADMINISTRADOR): descarga la biblioteca entera a
  un JSON y la restaura desde el mismo sitio. El informe de importación dice cuántos registros se
  crearon, cuántos se actualizaron y cuántos se omitieron, en palabras y no con identificadores.
- El selector de ficheros es propio (`publico/theme.js`-style label + input oculto): el nativo dice
  "Choose File" en inglés y **no hay CSS que lo traduzca**. Sigue siendo foco de teclado y lo lee el
  lector de pantalla.
- `api.postJson` en el cliente: un documento que el servidor ya produjo tiene que enviarse tal cual,
  porque `JSON.stringify` lo convertiría en un literal JSON y el backend recibiría una cadena.
- **Auditoría de accesibilidad con axe** (`scripts/a11y-audit.mjs`): 2 roles × 2 temas × 9 pantallas =
  36 combinaciones. Falla si aparece cualquier violación seria o crítica.
  Resultado: **0 violaciones serias o críticas**, con el informe completo en
  `docs/evidence/round-9/axe-report.json`.
- **Contraste corregido con números, no a ojo.** `--text-subtle` daba 2.99:1 sobre la cabecera de
  tabla (WCAG AA pide 4.5:1 a 12px) y `--text-muted` del tema oscuro 3.58:1 sobre la superficie
  elevada. Ahora son 4.97:1 y 5.23:1, medidos, y se documenta el porqué en `_tokens.scss`.
- **README** al día: qué hace la aplicación, cómo exportar por línea de órdenes, cómo comprobar que
  un respaldo se restaura de verdad (`scripts/verify-restore.sh`), los comandos de pruebas y los de
  accesibilidad. `markdownlint` sin una sola queja.

**Estado:** `done` (2026-10-05). Backend **257 tests verdes** (11 de export/import), frontend
**70 verdes**, `oxlint` 0 avisos, `tsc` limpio, `markdownlint` limpio, 4 servicios `healthy`.

- **Tres bugs reales encontrados por el viaje completo de exportar → importar → importar otra vez**,
  ninguno cubierto antes porque ninguna prueba había exportado un documento con préstamos dentro:
  1. Las fechas iban como `Instant` sin tipo SQL y cualquier biblioteca con un préstamo devolvía 500.
  2. Reimportar chocaba con `loans_one_active_per_copy`: la fila nueva tiene id nuevo y `ON CONFLICT`
     no la ve. Ahora se pregunta si el ejemplar ya está fuera y se omite.
  3. Lo mismo con `reservations_one_open_per_reader`.
- Y uno cuarto que solo apareció al leer el informe de la UI: **71 ejemplares se omitían** porque una
  copia sin estantería se confundía con una irrestaurable. Un ejemplar sin
  asignar es lo normal: está esperando a que le toque una estantería. Ahora se
  importa y el informe dice `copies 0 115 0`.

---

## Ronda 10 — El mostrador encuentra al lector por su nombre

**Objetivo:** el flujo del mostrador era inusable tal cual estaba. Pedía el
**id numérico** del lector, y el campo ni siquiera era de texto
(`inputMode="numeric"` sobre una caja donde el empleado tenía que leer un
carnet). Nadie ricarta ids; la gente teclea nombres.

- `GET /loans/readers?q=` (solo quien tenga `loans:operate`): busca por nombre,
  correo o número de carnet. **Nunca devuelve la lista completa sin
  consulta**: una caja vacía es un «escribe algo», no un volcado de la tabla
  de cuentas.
- Sin acentos: `translate()` pliega a ascii para que `garcia` y `GARCIA`
  encuentren a `Bruno García Ordóñez`. Es el mismo truco que ya usaba el
  catálogo con `search_text`, sin extensión de Postgres ni migración nueva.
- Devuelve lo que el mostrador necesita antes de prestar: préstamos activos
  y vencidos. Sin `passwordHash`, sin `role`, sin `mustChangePassword`.
- `ReaderPicker` con debounce de 250 ms: teclear `brun` son **una** petición,
  no cuatro. Con resultados, con «Sin resultados», y con estado elegido +
  «Cambiar de lector».
- **Contraste: `opacity: 0.8` eliminada.** El historial de préstamos atenuaba
  el `<li>` entero, y con él el código del libro bajaba a **4.3:1** (AA pide
  4.5:1). axe solo lo detectó cuando el lector de prueba ya tenía historial.

**Estado:** `done` (2026-10-05). Backend **266 tests verdes**, frontend **76
verdes** (13 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas** en 36 combinaciones, 4 servicios `healthy`.

- El 401 de `/api/auth/me` al abrir `/login` no es un fallo: es la pregunta
  «¿quién soy?» antes de tener sesión. Los scripts de captura lo filtran
  **por URL exacta**, no por código, para que un 401 de verdad siga
  rompiendo la QA.
- Un lector recién creado por la API cae siempre en «cambiar contraseña». La
  captura del mostrador no necesita esa sesión: la cuenta existe solo para que
  la búsqueda la encuentre.

---

## Ronda 11 — El stack entero, afirmado

**Objetivo:** los scripts de QA manejaban el navegador real pero solo miraban la
consola. Un flujo roto producía igual una captura bonita. Este script **falla**.

- `frontend/scripts/e2e-smoke.mjs`: abrir sesión, montar la escena por API, buscar
  al lector en el mostrador, prestar, y comprobar que el lector lo ve en su
  rincón. Repite en el navegador lo que ningún test unitario puede ver: rutas,
  sesión, guards y el DOM.
- Las reglas de negocio (no prestar un libro ya fuera, tope de renovaciones,
  cola de reservas) **no** se repiten aquí: ya las cubren 267 tests contra un
  Postgres real. Duplicarlas en el navegador sería lento y frágil.

**Tres bugs reales que encontró el propio smoke:**

1. **El backend era correcto, la ruta no.** El menú escondía «Préstamos» y
   «Inventario» a un lector, pero la **ruta** no: `/prestamos` se abría igual.
   La API lo bloqueaba (fail-closed, 267 tests), así que no era una fuga de
   datos, era una pantalla llena de peticiones fallidas. Ahora ambas rutas usan
   el `RequireRole` que ya existía para `/cuentas`.
2. **`ibañ` no encontraba a nadie; `ibanez` sí.** Al revés de como se espera.
   La columna se plegaba con `translate()` en SQL, pero el término buscado solo
   pasaba por `toLowerCase()`. Se reutiliza `Book.fold()` del catálogo, que ya
   hacía NFD y quitaba los diacríticos: 10/10 en `ReaderSearchApiTest`.
3. **La sesión sí sobrevive al reinicio, y ahora está probado.** El script
   `verify-restart.sh` reinicia backend y frontend con la sesión abierta y
   comprueba que la misma cookie sigue valiendo. Era una afirmación de la ronda
   7 que nadie había medido.

**Tres trampas del propio script, documentadas porque Costaron tiempo:**

- `browser.newPage()` **comparte cookies** con la página anterior: la mitad del
  test «del lector» se estaba pasando con la sesión del administrador. Hace
  falta un `newContext()`.
- `/login` no existe; la ruta real es `/entrar` y `/login` solo funciona por el
  `catch-all`. Rellenar el formulario antes de que la SPA asiente es una
  carrera. Ahora se espera al campo.
- Esperar milisegundos fijos en vez de esperar el contenido. `waitForSelector`
  sobre el texto que importa quita la intermitencia de raíz.

**Estado:** `done` (2026-10-05). Backend **267 tests verdes**, frontend **76
verdes** (13 suites), `oxlint` 0 avisos, `tsc` limpio, smoke e2e **0 fallos**,
sesión resistente al reinicio, 4 servicios `healthy`.

---

## Ronda 12 — Inicio: qué hay que hacer hoy

**Objetivo:** la primera pantalla de la aplicación saludaba con el estado de la
base de datos y una lista de **las rondas 2 a 6, todas ya terminadas**. Era un
resto de desarrollo servido como interfaz, idéntico para los cuatro roles. Para
una persona que abre la app a las nueve de la mañana, valor cero.

- `GET /loans/dashboard` (solo quien tenga `loans:operate`): cuatro números en una
  consulta — fuera, vencidos, para hoy, en estantería — y los **6 más atrasados**
  con nombre, correo y días de retraso. La lista se corta a propósito: una
  pantalla con cincuenta deudas es una pantalla que nadie lee.
- Inicio saluda por el nombre y la hora («Buenos días, Ana»), y el panel va
  primero. El estado del stack pasa a ser la nota al pie que siempre fue.
- Un LECTOR no recibe ni los números ni la lista: ve «Tu rincón» con el camino a
  Mi biblioteca y al mapa. El `403` del backend y el `enabled` del cliente
  coinciden; el test lo comprueba por los dos lados.

**Dos cosas que el trabajo destapa:**

- `current_date - l.due_at::date` devuelve los días directamente. Lo primero fue
  `(intervalo)::int`, que Postgres **no** puede castear: 500 en toda la pantalla.
- El montón de deudas se construye prestando bien y envejecendo los préstamos
  después, porque la política (muy razonablemente) no presta a quien ya está
  atrasado. El primer intento del test fallaba con 409 y no era el bug: era la
  regla funcionando.

**Estado:** `done` (2026-10-05). Backend **274 tests verdes** (7 del panel),
frontend **79 verdes** (14 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, 4 servicios `healthy`, 4 capturas en
`docs/screenshots/round-12/`.

- Un test dependía del orden: `ReaderSearchApiTest` buscaba «bruno» y el nuevo
  `DashboardApiTest` dejaba un Brunoeseed en la misma base. Ahora busca el correo
  propio, que es lo que el test quiere comprobar.
- `screenshot-home.mjs` **no inventa estado**: para ver la lista de vencidos hay
  que envejecer un préstamo a mano, y el propio script lo dice en su cabecera.

---

## Ronda 13 — Tu biblioteca, y una suite que deja de mentir

**Objetivo:** Inicio ya decía quién debía algo (ronda 12), pero seguía sin
responder a la pregunta más frecuente de una biblioteca: *«¿tenéis esto?»*.
Además, la Ronda 12 dejó al descubierto que **la suite frontend era
intermitente**, y eso es peor que un test rojo: es un test que miente.

- `GET /catalog/suggestions` (**no** es solo de personal: cualquiera con sesión
  puede ojear las estanterías): cuántos libros hay, los 6 últimos que entraron
  y una búsqueda rápida por título, autor o ISBN sin acentos. Reutiliza
  `BookRepository.search` en vez de inventar una segunda búsqueda que se
  desincronizaría de la primera.
- `LibrarySummary` en Inicio, para los cuatro roles, con debounce de 250 ms,
  «Sin resultados», catálogo vacío y enlaces a la ficha.
- `CatalogService.toSummary` pasa a ser package-private: el panel **reutiliza**
  la proyección del catálogo en vez de copiarla. Una copia habría sido la deriva
  garantizada.

**La deuda que salió al benar una captura:**

1. `screenshot-home.mjs` esperaba 1200 ms y a veces sacaba la pantalla de login.
   Una sonda con reloj mostró que la app va bien a los 300 ms: **el bug estaba en
   el script**. Ahora espera a `.home__stats`. Y espera a que la API responda,
   porque tras reconstruir el backend nginx se queda con la IP vieja un rato.
2. **`auth-flow.test.tsx` fallaba según el orden de las llamadas.** Los
   `mockResolvedValueOnce` solo encajan si el componente pregunta exactamente lo
   mismo en exactamente ese orden; un refetch y los mocks caen en la petición
   equivocada. Ahora `mockApi()` responde **por URL**.
3. **`book-form-isbn-autofill.test.tsx` afirmaba fuera del `waitFor`.** El
   formulario rellena campo a campo en renders distintos: esperar `Titulo` y
   afirmar `Editorial` a continuación es una carrera. Todo dentro del `waitFor`.
4. El test del debounce afirmaba «exactamente 1 llamada», que depende de la
   velocidad del teclado. Afirma lo que importa: **menos llamadas que teclas** y
   que la última lleve la palabra entera.

Cinco pasadas seguidas en verde después de arreglarlo (antes fallaba 2 de 4).

**Estado:** `done` (2026-10-05). Backend **280 tests verdes** (6 de sugerencias),
frontend **84 verdes** (15 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, sesión resistente al reinicio,
4 servicios `healthy`.

- `Intl.NumberFormat('es-ES')` **no** pone punto en 1284 (RAE: los números de
  cuatro cifras no llevan separador). El test daba por hecho que sí, y el
  componente estaba bien. Ahora hay un test para las dos cosas.

---

## Ronda 14 — Renovar sin ir a la biblioteca

**Objetivo:** un lector con un libro que vence mañana tenía que acercarse al
mostrador a renovarlo. Es, probablemente, el motivo más frecuente de una visita
que no hace falta.

- `POST /loans/{id}/renew` pasa a estar abierto a **cualquier sesión**, con una
  comprobación en el servicio: si el préstamo no es tuyo y no operas el mostrador,
  **403 antes de evaluar una sola regla**. Prestar y devolver siguen siendo solo
  del mostrador, y hay un test que lo dice.
- Botón «Renovar» en Mi biblioteca, con la confirmación en pantalla («Renovado
  hasta el …») y el motivo del rechazo del servidor cuando no puede. No se
  ofrece en un préstamo ya vencido: la fecha alcanza para saberlo, sin inventar
  un endpoint de ajustes para lectores.
- `CurrentUser.authorities()` para que un servicio distinga al lector del
  mostrador sin repetir el cálculo de roles.

**El bug de dominio que salió al probarlo de verdad:**

`checkRenewal` hacía `newDue = hoy + periodo`. Eso significa que **renovar el
primer día no daba nada** (misma fecha) y que **renovar tarde recortaba** el
préstamo. Ahora es `max(hoy, vencimiento) + periodo`, que es lo que hace toda
biblioteca: renovar nunca te quita días. Dos tests de dominio nuevos; el viejo
fijaba la aritmética antigua.

El e2e lo cazó solo: la renovación «funcionaba» y el smoke detectó que la fecha
no se movía. Sin esa comprobación habría salido un botón inútil.

- `new Date()` en el render era un aviso legítimo de oxlint (`react(purity)`).
  El botón usa el `overdue` que ya envía el servidor: ni una impureza ni una
  regla duplicada en el cliente.

**Estado:** `done` (2026-10-05). Backend **289 tests verdes** (7 de renovación,
20 de política), frontend **87 verdes** (16 suites), `oxlint` 0 avisos, `tsc`
limpio, axe **0 serias/críticas**, smoke e2e **0 fallos**, 4 servicios `healthy`.

---

## Ronda 15 — Las reglas de préstamo se cambian sin servidor

**Objetivo:** los días de préstamo, el límite por lector y las renovaciones
vivían en `app_config`, que se había creado para eso pero **no tenía puerta de
entrada**: la única forma de cambiarlos era abrir un `psql` dentro del
contenedor. Para una app que promete no necesitar soporte externo, eso es
justo lo que no puede quedar así.

- `PUT /loans/settings` (`settings:manage`, solo administrador). El mostrador
  sigue **leyendo** la política; escribirla es del administrador.
- **Todo o nada:** los tres números se validan antes de escribir nada, así que
  un formulario con un 0 a medias no deja la biblioteca prestando libros por un
  mes. Rangos: 1–365 días, 1–50 libros, 0–10 renovaciones.
- Un test comprobar que la política nueva **se usa en el siguiente préstamo**,
  no que solo se guardó. Guardar un número y que el dominio siga con el otro
  sería un ajuste de fachada.
- `Settings` en `/ajustes/datos`: reglas de préstamo y datos en la misma
  pantalla, con `LibraryData` en modo embebido para que no haya dos encabezados
  de página apilados.

**El bug que salió al probarlo en el navegador:**

La confirmación de «Guardado» **desaparecía al instante**. La causa no era el
servidor: invalidar la caché de la política cambiaba el `key` del formulario, lo
que lo remontaba entero. Se arregla usando la respuesta de la mutación como
nuevo valor (`setQueryData`) en vez de un refetch, y sin `key`: la línea «Ahora
mismo: 14 días · max 5 por lector · 2 renovaciones» ya deja ver si alguien cambió
la política por otro lado.

- oxlint señaló dos cosas ciertas en el camino: un `setState` síncrono dentro de
  un efecto (sustituido por un formulario hijo que inicializa su estado) y
  `Date` en el render de la ronda 14. Ninguna era cosmetics: eran estados que
  el componente no controlaba bien.

**Estado:** `done` (2026-10-05). Backend **296 tests verdes** (7 de ajustes),
frontend **91 verdes** (17 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, 4 servicios `healthy`, 6 capturas en
`docs/screenshots/round-15/` (la captura de QA deja los 14 días originales).

---

## Ronda 16 — Colocar las estanterías desde el navegador

**Objetivo:** el mapa 3D dibuja la geometría que hay en la base de datos, y esa
geometría **no tenía ninguna puerta de entrada**: una instalación nueva se
quedaba con el mapa vacio y la unica forma de colocar una estanteria era
llamar a la API a mano. Toda una ronda de trabajo (la 6) servia de adorno.

- `LocationSummary` devuelve ahora `x/y/z/width/depth/height`. Un editor que
  tiene que pedir las coordenadas por separado es un editor que nadie usa.
- LayoutEditor en Inventario: la lista de estanterias con donde esta cada una
  y cuantos ejemplares guarda, dos campos para X y Z en metros, y nada de rangos
  imposibles (mas de 1000 m es una errata, no una coordenada).

**El bug que llevaba dos rondas escondido:**

`updateLocation` **aceptaba la geometría y la tiraba**: solo renombraba. Se
podian crear una estanteria ya colocada, pero **moverla despues no hacia
nada**, y la API respondia 200 como si si. Solo se noto al leer el
servicio entero en vez de fiarse de que el PUT existia.

- `@DecimalMin("-1000")` sin máximo: una estantería de 5000 m pasaba el filtro.
- El proyecto **omite los nulos** en JSON, así que «sin colocar» y «en el origen»
  no son lo mismo. El test lo fija: una coordenada ausente se queda ausente.

**Estado:** `done` (2026-10-05). Backend **301 tests verdes** (5 de geometría),
frontend **95 verdes** (18 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, 4 servicios `healthy`, 5 capturas en
`docs/screenshots/round-16/` (la última es el mapa con estanterías colocadas).

---

## Ronda 17 — El resguardo que el lector lleva al mostrador

**Objetivo:** la app no manda correos a propósito: sin cuentas, sin servicios
externos, sin terceros. Pero eso deja un agujero real: un lector con un libro
vencido no puede renovarlo (`LoanPolicy` lo impide, y con razón) y **nadie se lo
dice**. Solo puede enterarse si abre la aplicación.

- `/mi-biblioteca/resguardo`: el papel que el lector entrega en el mostrador.
  Nombre y correo arriba (el mostrador lo recibe de una mano, no de un id), cada
  libro con su **código de ejemplar** en monoespaciado para escanear o teclear,
  y la fecha de devolución con «Quedan 28 días» o «Venció hace 6 días».
- Si hay algo vencido, el aviso va **en rojo y en días**, no como «1 de 2»: los
  días son el número que se discute en el mostrador, el recuento no.
- Es una pantalla primero y una impresión después (`@media print` quita la
  barra y los bordes). Un resguardo que no se puede leer en el móvil no lo abre
  nadie.
- Botón «Resguardo para el mostrador» en Mi biblioteca, solo cuando hay algo
  prestado.

**Sinceridad sobre el alcance:** esto **no avisa**. Es la pieza que hace posible
avisar: sin correo ni SMS no hay forma de empujar un aviso, y decirlo de otro
modo sería vender humo. Lo que sí cierra es el círculo — el lector ve lo que debe
y el mostrador lo escanea de vuelta— y deja ellistado del CSV para avisos de
masiva.

- oxlint señaló `new Date()` en el render (segunda vez que aparece). La fecha del
  resguardo se fija **una vez al montar** con `useState(() => new Date())`: es la
  fecha en que se escribió, no la de cada render.

**Estado:** `done` (2026-10-05). Backend **301 tests verdes**, frontend **99
verdes** (19 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, 4 servicios `healthy`, 5 capturas en
`docs/screenshots/round-17/` (incluida la emulación de impresión).

---

## Ronda 18 — La hoja para trabajar la lista de llamadas

**Objetivo:** la tabla del mostrador enseña quince préstamos por página. Cuando
hay que llamar a veinte personas, eso no es una lista de trabajo: es scroll.

- `GET /loans/overdue.csv` (`loans:operate`): lector, correo, libro, ejemplar,
  vencimiento y **días de retraso**, ordenados por vencimiento, para empezar por
  el más viejo.
- Botón «Descargar vencidos (CSV)» en el mostrador, con confirmación en pantalla.

**Tres detalles que separan un CSV útil de uno que tira una tarde:**

- **BOM al principio.** Sin él Excel lee «García» como «GarcÃ­a», y quien lo
  genera cree que los datos están mal. Comprobado en los bytes: `EF BB BF`.
- **Comillas de verdad.** Un título de libro tiene derecho a contener una coma y
  comillas: `"Cien años, de ""sol"""`. Concatenar a pelo partiría el título en
  tres columnas.
- **Protección contra fórmulas.** Una celda que empieza por `= + - @` la ejecuta
  Excel en cuanto alguien la toca. Un nombre del tipo =HYPERLINK(...) se
  guarda como texto, con un apostrofo delante. El test lo comprueba con un
  =HYPERLINK de verdad.

- La descarga va por **`fetch` con la sesión**, no por un `<a href>` pelado: un
  enlace directo sería un segundo camino a la API sin las reglas de CSRF ni la
  sesión, y justo el tipo de puerta trasera que este proyecto no quiere.

**Estado:** `done` (2026-10-05). Backend **309 tests verdes** (8 del CSV),
frontend **101 verdes** (20 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, 4 servicios `healthy`. La descarga se
verificó de verdad en el navegador: fichero `prestamos-vencidos.csv` guardado y
contenido correcto, no solo un 200.

---

## Ronda 19 — Tu biblioteca se llama como tú quieras

**Objetivo:** «Open Library OS» es el nombre del proyecto, no el de la biblioteca.
Alguien que se autoaloja esto tiene una biblioteca con nombre propio, y la
**primera pantalla que ve nunca** es la de acceso.

- `GET /api/system/library` devuelve el nombre y **no pide sesión**: el formulario
  de acceso todavía no tiene cookie. Sustituye al título del acceso y aparece en
  el resguardo de préstamos.
- `PUT /api/system/library/name` (`settings:manage`): nombre vacío o de más de
  120 caracteres se rechazan con 400 en vez de truncar.
- Si el nombre no se puede leer, la pantalla **deja entrar igual** y cae al nombre
  del proyecto. Un nombre es decoración; no puede ser una puerta.

**La otra mitad de la ronda: decidir la configuración muerta.**

`inventory.barcode_prefix`, `isbn.providers` y `library.locale` las escribió
`V1__baseline.sql` y **no las leía nadie**: el prefijo de código vive en el value
object, la cadena de proveedores es código, y el idioma de la interfaz es fijo.
Editar esas filas no hacía nada, que es peor que no existieran. La migración
**`V9__drop_dead_config.sql`** las borra y explica su motivo en el propio
fichero, que es lo que hace útil una migración.

Quedan 7 claves en `app_config`, todas leídas por algo.

**Estado:** `done` (2026-10-05). Backend **315 tests verdes** (6 del perfil),
frontend **103 verdes** (21 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, V9 aplicada en la base de QA, 4
servicios `healthy`.

---

## Ronda 20 — ¿Están mis respaldos? Sin leer logs

**Objetivo:** esta aplicación vende que tus datos son tuyos y sobreviven. La
pregunta que tiene que responder quien la opera («¿sigue siendo verdad?») solo se
podía contestar con `docker compose logs backup`. Y `backup:manage` protegía un
`/backup/**` que no existía: un permiso que no protege nada.

- El contenedor `backup` ya escribía un marcador `.last-ok` con la hora del último
  volcado validado, y su healthcheck depende de él. El backend monta ese volumen
  **en solo lectura** (`backups:/backups:ro`) y lo lee.
- GET /backup/status (ackup:manage): si esta al dia, cuantos ficheros hay,
  el nombre y tamano del mas reciente, y **la edad en horas**. Tres estados
  claros: nunca se ha hecho, el marcador existe pero su volcado ya no, o tiene
  mas de 48 h.
- Tarjeta en Ajustes, con el aviso en rojo cuando algo va mal, y el
  recordatorio de que restaurar se comprueba con scripts/verify-restore.sh.
**Lo que este servicio deliberadamente NO hace:** lanzar un `pg_dump`. Un botón
que ejecutes `pg_dump` desde la aplicación sería **una manera de perder datos**
—una segunda ruta hacia la base, con su propia superficie— y no una de
mantenerlos. Hacer respaldos es del contenedor; restaurar y comprobar es del
script.

**Sobre los permisos, una corrección de las expectativas del test:**

El test daba 403 a ADMINISTRATIVO porque me lo había inventado. El código ya lo
decía bien desde la ronda 1: *«el personal administrativo es dueño de los datos»*

y por eso tiene ackup:manage. LECTOR y BIBLIOTECARIO quedan fuera, que es lo
correcto: no tienen por qué contar las copias de seguridad de nadie.

**Estado:** `done` (2026-10-05). Backend **320 tests verdes** (5 del estado),
frontend **107 verdes** (22 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, sesión resistente al reinicio, 4
servicios `healthy`. Comprobado contra los **6 volcados reales** del volumen de
QA, no contra un mock: «Respaldos al día: 6 guardados, el último hace 22 h».

---

## Ronda 21 — La otra lista de llamadas: la cola de reservas

**Objetivo:** cuando un libro vuelve hay alguien esperando y hay que
llamarle. Esa lista solo existia en pantalla, y eran cinco filas iguales
que decian «Rayuela, 5/10/2026» cinco veces. Al mostrador no le dice nada.

- `GET /loans/reservations.csv` (`loans:operate`): libro, lector, correo, **puesto**
  y dias esperando, ordenados por libro y por posicion. El orden del
  fichero *es* el orden de trabajo.
- La tarjeta del mostrador se agrupa por libro y muestra **quién** espera en cada
  puesto, con el número de turno al lado.
- `OverdueCsv` pasa a escribir los dos informes: la parte difícil no eran las
  columnas, era el entrecomillado (coma en un titulo, acento en un nombre,
  celda que Excel ejecuta como formula). Esa parte se escribe una vez y la
  comparten.
**El hueco de fondo era de datos, no de dibujo:** `ReservationSummary` no devolvía
**quién** espera. La pantalla no podía agrupar porque no tenía con qué: cinco filas
del mismo libro sin identidad. Ahora trae lector y puesto, calculados en la misma
consulta.

**La trampa de siempre, tercera vez:** `JdbcTemplate` no sabe tipar un
`java.time.Instant` y devuelve 500. Ya salió en el import (ronda 9) y en el panel
de vencidos (ronda 12). Esta vez el comentario explica el porqué en el sitio, para
que el cuarto que aparezca sepa lo que cuesta.

**Estado:** `done` (2026-10-05). Backend **324 tests verdes** (4 de la cola),
frontend **107 verdes** (22 suites), `oxlint` 0 avisos, `tsc` limpio, axe **0
serias/críticas**, smoke e2e **0 fallos**, sesión resistente al reinicio, 4
servicios `healthy`. La descarga se comprobó en el navegador: `cola-de-reservas.csv`
con BOM y contenido correcto.

---

## Métricas de calidad (revisadas cada ronda)

- Backend: tests verdes, 0 warnings de compilación, `ruff`-style cleanliness no aplica (Java);
  `mvn -q verify` como puerta.
- Frontend: `tsc --noEmit` limpio, ESLint limpio, sin `any` implícito.
- UX: 0 problemas de accesibilidad críticos en las pantallas nuevas (Playwright + axe).
- Deuda: todo lo que se difiere queda anotado en `docs/plan.md` con motivo.

---

## Siguiente

Lo que queda por hacer, en orden de valor para quien usa la biblioteca.

1. ~~Aviso de vencimiento sin correo.~~ **Hecho en la ronda 17** (resguardo
   imprimible) y en la 18 (CSV). Lo que falta, si algún día, es un aviso
   automático, y eso ya no es posible sin un servicio externo: no es un SMTP.
2. ~~CSV de vencidos para el mostrador.~~ **Hecho en la ronda 18.**
3. ~~Limpiar `app_config` muerto.~~ **Hecho en la ronda 19.**
4. ~~La lista de la cola de reservas.~~ **Hecho en la ronda 21.**
5. **Las 187 violaciones de `markdownlint` en este mismo plan.** Cosmético, pero
   es el documento que orienta el trabajo.

Decisiones ya tomadas que no hay que volver a discutir:

- La **siguiente migración es `V10`**: `V9` ya existe (ronda 19, limpia la
  configuración muerta). `V4` y `V5` no existen y no deben aparecer.
- Los **scripts de captura no inventan estado**. Para ver algo raro en pantalla
  hay que prepararlo en la base, y el propio script lo dice en su cabecera.
- **Nada de esperas fijas** en los scripts de QA: se espera al contenido
  (.home__stats, [role="status"]), nunca a milisegundos.
