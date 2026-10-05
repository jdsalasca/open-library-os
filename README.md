# Open Library OS

Gestion de librerias autoalojada. Descarga, `docker compose up -d`, y ya.

- **Backend:** Spring Boot 4.1 (Java 25) + PostgreSQL + Flyway
- **Frontend:** React 19 + Vite + TypeScript + SCSS, con tema claro y oscuro
- **Despliegue:** un solo comando, cuatro contenedores, respaldos incluidos

Tus datos viven en tu servidor. Sin servicios externos, sin cuentas,
sin soporte de terceros.

## Que hace

- **Catalogo** con autores multiples, editoriales, categorias e ISBN.
- **Ejemplares** con codigo legible, EAN-13 y QR, etiqueta imprimible y
  ubicaciones fisicas (sala, pasillo, estante, deposito) con coordenadas.
- **Prestamos** en el mostrador con escaner USB, camara o a mano, con cola de
  reservas, renovaciones y vencimientos.
- **Mapa del local** en 3D para encontrar un libro desde el movil.
- **Mi biblioteca** para el lector: lo que tiene prestado, lo que espera y su
  historial.
- **Tus datos**: exporta la biblioteca entera a un JSON y restáurala donde
  quieras. Tus datos son tuyos.

---

## Puesta en marcha

Requisitos: Docker con Compose v2. Nada mas.

```bash
git clone <este-repositorio>
cd open-library-os
docker compose up -d
```

Abre <http://localhost:8080>.

La primera vez Docker construye las imagenes (2-4 minutos). A partir de ahi,
`docker compose up -d` levanta todo en segundos.

Para cambiar puertos o credenciales:

```bash
cp .env.example .env
$EDITOR .env
docker compose up -d
```

### Acceder a la base de datos

El puerto de Postgres no se publica por defecto. Usa el cliente del propio contenedor:

```bash
docker compose exec db psql -U openlibrary -d openlibrary
```

Si prefieres DBeaver o `psql` desde el escritorio, descomenta el bloque `ports:`
del servicio `db` en `docker-compose.yml` y reinicia.

---

## Estado del sistema

```bash
docker compose ps                                        # servicios y salud
curl -fsS localhost:8080/api/actuator/health             # {"status":"UP"}
docker compose logs -f backend                           # log del backend
docker compose exec db psql -U openlibrary -d openlibrary \
  -c 'select version, description, success from flyway_schema_history'
```

## Respaldo y restauracion

El servicio `backup` crea un `pg_dump` al arrancar y despues cada `BACKUP_INTERVAL`
segundos, en el volumen `backups`. Cada dump se valida con
`pg_restore --list`; los
que no se pueden leer se descartan. Se conservan los ultimos `KEEP_DAYS` dias.

```bash
docker compose exec backup ls -l /backups   # historial de respaldos
```

Copia el dump a otra instalacion:

```bash
docker compose exec -T db pg_restore -U openlibrary -d openlibrary
  --clean --if-exists < backup.dump
  < backup.dump
```

La migracion entre instancias tambien esta disponible desde la interfaz, en
**Ajustes > Tus datos**: se descarga un JSON con todo y se vuelve a subir. El
import es un *upsert* por clave natural, asi que repetirlo no duplica nada. Para
hacerlo desde la linea de ordenes:

```bash
curl -sc cookies.txt http://localhost:8080/api/auth/csrf > /dev/null
curl -sb cookies.txt -c cookies.txt -X POST
  http://localhost:8080/api/auth/login
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@local","password":"TU-CONTRASENA"}' > /dev/null
curl -sb cookies.txt http://localhost:8080/api/admin/export -o biblioteca.json
```

### Comprobar que un respaldo sirve de verdad

Un `pg_dump` que no se ha restaurado nunca es una suposición. Este script
restaura el último dump del volumen en una base temporal **dentro** del
contenedor, compara los conteos con la base viva y sale con código distinto de
cero si algo no cuadra:

```bash
sh scripts/verify-restore.sh
```

### Migraciones: la regla que no se rompe

Flyway comprueba el checksum de cada migracion ya aplicada. Si editas un
fichero `V*.sql` que ya se ejecuto, el backend **no arrancara** y lo dira
claramente. Es intencionado: es la proteccion que garantiza que tu base de
datos y tu codigo nunca diverjan en silencio.

- **Antes de una release:** las migraciones son inmutables. Corrige con
  una `V*.sql` nueva.
- **En desarrollo, si te pasa:** `docker compose run --rm backend` no sirve;
  recrea el volumen con `docker compose down`,
  `docker volume rm open-library-os_pgdata` y `docker compose up -d`, o
  actualiza los checksums con `repair` de Flyway.

---

## Desarrollo local

```bash
# Backend (necesita Postgres; Docker Compose lo levanta)
docker compose up -d db
cd backend && mvn spring-boot:run      # http://localhost:8080/api

# Frontend (proxy /api hacia el backend)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Puertas y credenciales de la base de datos:

| Variable      | Por defecto                          |
| ------------- | ------------------------------------ |
| `DB_URL`      | `jdbc:postgresql://host:5432/db`     |
| `DB_USER`     | `openlibrary`                        |
| `DB_PASSWORD` | `openlibrary`                        |

### Pruebas

```bash
cd backend  && mvn -B clean test    # JUnit 5 + Testcontainers (Postgres real)
cd frontend && npx vitest run      # Vitest + Testing Library
cd frontend && npx tsc -b --noEmit
cd frontend && npx oxlint
```

Los tests de backend levantan un Postgres efimero con Testcontainers, de modo que
las migraciones de Flyway se ejecutan de verdad en cada prueba.

En GitHub Actions, `.github/workflows/ci.yml` ejecuta exactamente esos cuatro
comandos en cada `push` a `develop`.

### Accesibilidad y capturas (QA visual)

```bash
cd frontend
node scripts/a11y-audit.mjs                       # axe sobre todas las pantallas
node scripts/screenshot-loans.mjs                 # y los demas scripts screenshot-*
```

`a11y-audit.mjs` recorre las ocho pantallas en claro y en oscuro, con los dos
roles, y falla si aparece cualquier violacion seria o critica. Los scripts de
captura fallan si la consola marca un error o si una pantalla desborda en movil.

---

## Documentacion

- [`docs/plan.md`](docs/plan.md) — plan por rondas y estado de entrega
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — decisiones de arquitectura

## Estructura

```text
backend/    Spring Boot (paquetes por modulo: auth, catalog, inventory, loans…)
frontend/   Vite + React (design system en src/design, componentes en src/components)
deploy/     Scripts de operacion (respaldos)
docs/       Plan, specs y evidencia visual
```

## licencia

AGPL-3.0. Tus datos siguen siendo tuyos.
