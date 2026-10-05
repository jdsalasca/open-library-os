# Cómo contribuir en Open Library OS

Gestión de librerías autoalojada: React + Spring Boot + PostgreSQL en tu
propio servidor. El código es una parte; la otra es que **los datos son del
dueño de la instalación**, así que cualquier cambio que simplifique el
`docker compose up -d` es bienvenido.

Antes de escribir código, lee [`docs/plan.md`](docs/plan.md): es la fuente de
verdad de qué se construye y en qué ronda estamos. La licencia está en
[`LICENSE`](LICENSE).

## 1. Stack: versiones exactas

No las adivines, están fijadas en `backend/pom.xml` y
`frontend/package.json`.

| Pieza | Versión | Dónde se fija |
| --- | --- | --- |
| Java | 25 | `java.version` en `pom.xml` |
| Spring Boot | 4.1.1 | `<parent>` en `pom.xml` |
| Maven | 3.9 | imagen `maven:3.9-eclipse-temurin-25` |
| PostgreSQL | 18-alpine | `docker-compose.yml` (`db` y `backup`) |
| Flyway | la de Boot 4.1.1 | `spring-boot-flyway` + `flyway-core` |
| ZXing | 3.5.4 | EAN-13, QR y etiquetas PNG |
| Node | 24 | imagen `node:24-alpine` |
| React / React DOM | ^19.2.8 | `package.json` |
| React Router | ^7.18.4 | `package.json` |
| TanStack Query | ^5.104.1 | `package.json` |
| Vite | ^8.3.0 | `package.json` |
| TypeScript | ~6.0.2 | `package.json` |
| Sass | ^1.105.1 | `package.json` |
| Vitest | ^5.0.3 | `package.json` |
| oxlint | ^1.81.0 | `package.json` (**no** hay ESLint) |
| Playwright | ^1.63.0 | solo para la evidencia visual |

El `tsconfig.app.json` no activa `strict`; sí activa `noUnusedLocals`,
`noUnusedParameters`, `erasableSyntaxOnly`, `verbatimModuleSyntax` y
`noFallthroughCasesInSwitch`. Esos son los límites que vigila `tsc`.

## 2. Levantar el stack

```bash
docker compose up -d
```

Cuatro servicios (`db`, `backend`, `frontend`, `backup`), healthchecks y
volúmenes `pgdata` y `backups`. `down` conserva los datos; `down -v` los
borra (es la lava de datos manual).

| Qué | Puerto | Nota |
| --- | --- | --- |
| UI + API | `HTTP_PORT`, 8080 por defecto | el único puerto publicado |
| Vite (dev) | 5173 | hace proxy de `/api` |
| PostgreSQL | 5432 | **no** se publica por defecto |

En esta máquina el 8080 está ocupado, así que el stack va al 8090:

```bash
$env:HTTP_PORT = "8090"     # PowerShell, solo para esta terminal
docker compose up -d
```

Los scripts de captura ya usan `http://127.0.0.1:8090` por defecto, que es
por lo que ese es el puerto de la máquina. Para otros puertos o credenciales,
la vía documentada es `cp .env.example .env` y editarlo (`.env` está en
`.gitignore`; nunca se commitea).

Comprobar que vive:

```bash
docker compose ps
curl -fsS localhost:8090/api/actuator/health     # {"status":"UP"}
docker compose exec db psql -U openlibrary -d openlibrary \
  -c 'select version, description, success from flyway_schema_history'
```

### Desarrollo local

```bash
docker compose up -d db
cd backend && mvn spring-boot:run               # http://localhost:8080/api

cd frontend && npm install && npm run dev       # http://localhost:5173
```

El proxy de Vite apunta a `http://localhost:8080`; se cambia con la variable
de entorno `API_URL`.

## 3. Tests

```bash
cd backend  && mvn -B clean test
cd frontend && npx vitest run
cd frontend && npx tsc -b --noEmit
cd frontend && npx oxlint
```

Equivalentes por script de npm: `npm test`, `npm run typecheck`,
`npm run lint` y `npm run build`.

Avisos que se ganan por el camino:

- **Docker tiene que estar arriba** para los tests de backend: Testcontainers
  levanta un Postgres real y las migraciones de Flyway se ejecutan de verdad.
  No es un doble: si una migración está rota, el test falla.
- **No hay `mvnw`**: hace falta Maven 3.9 en el `PATH`.
- Las APIs externas (Open Library, Google Books) se prueban con WireMock, no
  contra internet. Un test no depende de la disponibilidad de nadie más.
- Para la evidencia visual hace falta el Chromium de Playwright en la
  máquina (`npx playwright install chromium`, comando del propio CLI de la
  dependencia `playwright`, no del repo).

La última cifra verificada en el plan es **226 tests de backend y 65 de
frontend** (ronda 6). Si tu ronda baja ese número sin motivo, no cierra.

## 4. El trabajo va por rondas

`docs/plan.md` es el contrato de trabajo, y no es decorativo:

- Una sección por ronda, con estado `planned` → `in progress` → `done`.
- Cada ronda tiene **objetivo**, **entregable** (algo comprobable) y, si
  aplica, **criterio de cierre**.
- Al cerrarse, el estado se actualiza **con los números reales** (tests
  verdes, `tsc` limpio, `oxlint` sin avisos, contenedores `healthy`).
- Las **decisiones y desviaciones** se anotan ahí mismo, con el motivo. La
  ronda 6, por ejemplo, documentó que el plano se hace con CSS en vez de
  WebGL y por qué.
- Una ronda no cierra solo con documentación: cierra con código verificado.

## 5. Una ronda de principio a fin

1. **Contexto y plan.** Lee `docs/plan.md`, añade o actualiza tu ronda con
   objetivo, entregable y criterio de cierre.
2. **TDD.** Test rojo → mínimo código → verde. En backend, JUnit 5 +
   Testcontainers; en frontend, Vitest + Testing Library.
3. **Verificación visual.** Si tocaste la UI, ejecuta el script de captura
   de la ronda y **mira las imágenes**. Los errores de consola rompen el
   script (sale con código 1).
4. **Evidencia.** Guarda la salida real en `docs/evidence/round-<n>/`.
5. **Commit atómico** en `develop`, mensaje en imperativo, solo lo de la
   ronda. Nada de pushes ni merges a `main` sin pedirlo.

Y al terminar: si usaste worktree o rama, merge a `develop` en la **misma**
ronda con los tests verdes antes y después, y luego borra el worktree
(`git worktree remove <ruta>`, `git branch -d <rama>`, `git worktree prune`).
`git status` limpio: nada de trabajo aislado ni worktrees huérfanos.

## 6. Evidencia y capturas

```text
docs/evidence/round-<n>/     salida real de los comandos
docs/screenshots/round-<n>/  PNG de la ronda
```

En `docs/evidence/round-<n>/` se guarda lo que se ejecutó, con lo que salió
literalmente. Nombres ya usados en el repo:

| Fichero | Contenido |
| --- | --- |
| `backend-tests.txt` | salida de `mvn test` |
| `frontend-tests.txt` | salida de `vitest run` |
| `frontend-tsc.txt` | salida de `tsc -b --noEmit` |
| `frontend-lint.txt` | salida de `oxlint` |
| `compose-ps.txt` | `docker compose ps` con los healthchecks |
| `verify-restore.txt` | salida de `sh scripts/verify-restore.sh` |
| `map-contract.txt` | el contrato de `GET /api/map` en tabla |

## 7. Reglas del repo que no se negocian

- **SCSS únicamente**, con tokens y mixins
  (`frontend/src/design/_tokens.scss`, `_mixins.scss`). Nada de CSS suelto
  ni estilos inline. Temas claro y oscuro desde la primera ronda.
- **Las migraciones `V*.sql` son INMUTABLES una vez aplicadas.** Flyway
  comprueba el checksum y el backend no arranca si una cambia. Se corrige
  con una migración nueva. Están en
  `backend/src/main/resources/db/migration/`.
- **Commits pequeños y atómicos en `develop`.**
- **Cero estado en RAM**: Postgres es la única fuente de verdad.
- **Nada de secretos**: ni `.env`, ni tokens, ni claves. Ni en el código ni
  en la evidencia que se commitea.
- **TDD** en backend y frontend.

### Numeración de migraciones

Las aplicadas son `V1`, `V2`, `V3`, `V6`, `V7` y `V8` (`V4` y `V5` no existen:
el ISBN llegó antes que el inventario y el plan lo documenta). Flyway
rechaza migraciones fuera de orden, así que **la siguiente es `V9`**, aunque
`V4` y `V5` estén libres.

## 8. Scripts de evidencia visual

Viven en `frontend/scripts/`. Todos usan Playwright contra el stack ya
levantado, y **no son simples capturas**: el script aserta sobre el DOM y
lanza excepción si la pantalla está rota.

| Script | Argumentos | Qué hace |
| --- | --- | --- |
| `screenshot.mjs` | `[baseUrl] [outDir] [label]` | home |
| `screenshot-auth.mjs` | `[baseUrl] [outDir] [label]` | login y clave forzada |
| `screenshot-catalog.mjs` | `[baseUrl] [outDir] [label]` | catálogo |
| `screenshot-inventory.mjs` | `BASE_URL` (env) | inventario y etiquetas |
| `screenshot-loans.mjs` | `[baseUrl] [outDir]` | préstamo por la UI |
| `screenshot-map.mjs` | `[baseUrl] [outDir]` | el plano 3D y sus asertos |
| `reset-admin.mjs` | — | deja la cuenta de QA en su estado |
| `seed-demo.mjs` | `[baseUrl]` | siembra 8 libros por la API |

`screenshot.mjs` recorre la home en claro/oscuro x escritorio/móvil y falla
si hay errores de consola. `screenshot-catalog.mjs` cubre listado, búsqueda,
estado vacío, detalle y formulario. `screenshot-inventory.mjs` incluye al
lector sin acceso a la sección.

Los de ronda (`loans`, `map`, `inventory`) **no** aceptan etiqueta: sin
argumentos escriben en su directorio `docs/screenshots/round-<n>/` y en
`inventory` esa ruta está fija en el código. Y `qa:auth` encadena
`reset-admin.mjs` + `screenshot-auth.mjs`:

```bash
cd frontend
npm run qa:auth
```

Los asertos que más pican: `screenshot-map.mjs` falla si el plano no está
proyectado en 3D, si no cabe en el escenario, si un estante queda tapado por
su propia sala, si un nodo es demasiado pequeño para el dedo o si la página
desborda en móvil. Añade el tuyo con el mismo estilo y el siguiente error ya
no te sorprende.

## 9. Respaldo y restauración

El servicio `backup` saca un `pg_dump` al arrancar y cada `BACKUP_INTERVAL`
segundos (86400 por defecto), valida cada dump con `pg_restore --list` y
conserva `KEEP_DAYS` días (14). `healthy` significa "hay un punto de
restauración que se sabe que se puede leer".

Un backup que nadie restauró es un rumor, así que está el simulacro:

```bash
sh scripts/verify-restore.sh     # desde la raíz, con el stack arriba
```

Restaura el dump más reciente en una base de datos de usar y tirar dentro
del contenedor `db` (la viva no se toca), compara los conteos de filas y
limpia incluso si falla.

## 10. Antes de dar por cerrada una ronda

```bash
cd backend  && mvn -B clean test
cd frontend && npx vitest run && npx tsc -b --noEmit && npx oxlint
sh scripts/verify-restore.sh
docker compose ps
```

Sin evidencia pegada en `docs/evidence/round-<n>/`, la ronda no está cerrada.
