# Open Library OS

Gestion de librerias autoalojada. Descarga, `docker compose up -d`, y ya.

- **Backend:** Spring Boot 4.1 (Java 25) + PostgreSQL + Flyway
- **Frontend:** React 19 + Vite + TypeScript + SCSS, con tema claro y oscuro
- **Despliegue:** un solo comando, cuatro contenedores, respaldos incluidos

Tus datos viven en tu servidor. Sin servicios externos, sin cuentas, sin soporte de terceros.

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
segundos, en el volumen `backups`. Cada dump se valida con `pg_restore --list`; los
que no se pueden leer se descartan. Se conservan los ultimos `KEEP_DAYS` dias.

```bash
docker compose exec backup ls -l /backups   # historial de respaldos
```

Copia el dump a otra instalacion:

```bash
docker compose exec -T db pg_restore -U openlibrary -d openlibrary --clean --if-exists \
  < backup.dump
```

La migracion entre instancias tambien esta disponible desde la UI (Ajustes >
Exportar / Importar) en una version posterior.

### Migraciones: la regla que no se rompe

Flyway comprueba el checksum de cada migracion ya aplicada. Si editas un fichero `V*.sql`
que ya se ejecutó, el backend **no arrancará** y lo dirá claramente. Es intencionado: es la
protección que garantiza que tu base de datos y tu código nunca diverjan en silencio.

- **Antes de una release:** las migraciones son inmutables. Corrige con una `V*.sql` nueva.
- **En desarrollo, si te pasa:** `docker compose run --rm backend` no sirve; recrea el volumen
  (`docker compose down && docker volume rm open-library-os_pgdata && docker compose up -d`)
  o actualiza los checksums con `repair` de Flyway.

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

| Variable      | Por defecto     |
| ------------- | --------------- |
| `DB_URL`      | `jdbc:postgresql://localhost:5432/openlibrary` |
| `DB_USER`     | `openlibrary`   |
| `DB_PASSWORD` | `openlibrary`   |

### Pruebas

```bash
cd backend  && mvn test          # JUnit 5 + Testcontainers (Postgres real)
cd frontend && npm test          # Vitest + Testing Library
cd frontend && npx tsc -b --noEmit
```

Los tests de backend levantan un Postgres efimero con Testcontainers, de modo que
las migraciones de Flyway se ejecutan de verdad en cada prueba.

### Capturas de pantalla (QA visual)

```bash
cd frontend
node scripts/screenshot.mjs http://127.0.0.1:8080 ../docs/screenshots r0
```

Genera claro/oscuro x escritorio/movil y falla si hay errores en la consola.

---

## Documentacion

- [`docs/plan.md`](docs/plan.md) — plan por rondas y estado de entrega
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — decisiones de arquitectura

## Estructura

```
backend/    Spring Boot (paquetes por modulo: auth, catalog, inventory, loans…)
frontend/   Vite + React (design system en src/design, componentes en src/components)
deploy/     Scripts de operacion (respaldos)
docs/       Plan, specs y evidencia visual
```

## licencia

AGPL-3.0. Tus datos siguen siendo tuyos.