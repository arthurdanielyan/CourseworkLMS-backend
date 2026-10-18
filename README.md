# LMS Backend

The backend of the Library Management System coursework: Kotlin, Spring Boot 4, PostgreSQL 18.

This is the **core setup** — security, database plumbing, error and paging conventions, migrations and tests. Feature endpoints are added per capability module (see `AGENTS.md`).

## Requirements

- JDK: none to install — Gradle provisions Java 21 itself.
- PostgreSQL 18, either a local install or `docker compose up -d` (see below).
- Docker Desktop, running, for the tests.

## First-time setup on a local PostgreSQL

1. Create the application role once per PostgreSQL server (as `postgres`):

   ```sql
   CREATE ROLE lms_app LOGIN PASSWORD 'choose-a-password';
   ```

2. Export the credentials in `~/.zshenv` (not `~/.zshrc` — non-interactive shells ignore it):

   ```sh
   export DB_PASSWORD='choose-a-password'   # lms_app
   export FLYWAY_PASSWORD='...'             # postgres, the schema owner
   ```

3. Run:

   ```sh
   ./gradlew :app:bootRun --args='--spring.profiles.active=local'
   ```

   On the first start against an existing `LMS` database, Flyway records it as version 1 and applies the later migrations. On an empty database it builds everything from `V1__baseline.sql`.

## Using Docker instead

```sh
docker compose up -d
DB_PASSWORD=lms_app FLYWAY_PASSWORD=postgres ./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

The container creates `lms_app` (password `lms_app`) on its first start; Flyway does the rest.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/LMS` | Database URL |
| `DB_USER` / `DB_PASSWORD` | `lms_app` / — | The role the application connects as |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | `postgres` / — | The schema owner Flyway migrates as |
| `JWT_SECRET` | dev value in the `local` profile only | HS256 signing key, at least 32 bytes |

## Useful endpoints

- `GET /actuator/health`
- `/swagger-ui.html` — API docs; authorize with a bearer token

## Tests

```sh
./gradlew build
```

Starts a PostgreSQL 18 container, builds it with the migrations, and exercises security, the database rules and the API conventions.
