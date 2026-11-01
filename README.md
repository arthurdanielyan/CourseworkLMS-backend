# LMS Backend

The backend of the Library Management System coursework: Kotlin, Spring Boot 4, PostgreSQL 18.

Built so far: the core (security, database plumbing, error and paging conventions, migrations, tests) and **account access** — signup with an emailed verification code, login and token refresh under `/api/v1/auth`. Further features are added per capability module (see `AGENTS.md`).

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

3. Start Mailpit, the fake mail server that catches signup emails, then run the backend:

   ```sh
   docker compose up -d mailpit
   ./gradlew :app:bootRun --args='--spring.profiles.active=local'
   ```

   Verification codes appear in Mailpit's inbox at http://localhost:8025 instead of being delivered.

   On the first start against an existing `LMS` database, Flyway records it as version 1 and applies the later migrations. On an empty database it builds everything from `V1__baseline.sql`.

## Using Docker instead

```sh
docker compose up -d
DB_PASSWORD=lms_app FLYWAY_PASSWORD=postgres ./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

The container creates `lms_app` (password `lms_app`) on its first start; Flyway does the rest. Mailpit starts alongside it.

## Sending real emails (e.g. for a demo)

Outside the `local` profile the backend sends through any SMTP server. With a Gmail account:

1. Turn on 2-Step Verification for the account, then create an **app password** at https://myaccount.google.com/apppasswords.
2. Run with:

   ```sh
   export MAIL_HOST=smtp.gmail.com
   export MAIL_PORT=587
   export MAIL_USERNAME='your.account@gmail.com'
   export MAIL_PASSWORD='the 16-character app password'
   export MAIL_FROM='your.account@gmail.com'
   ```

Gmail sends up to about 500 emails a day from one account.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/LMS` | Database URL |
| `DB_USER` / `DB_PASSWORD` | `lms_app` / — | The role the application connects as |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | `postgres` / — | The schema owner Flyway migrates as |
| `JWT_SECRET` | dev value in the `local` profile only | HS256 signing key, at least 32 bytes |
| `MAIL_HOST` / `MAIL_PORT` | Mailpit (`localhost:1025`) in the `local` profile only | SMTP server for verification emails |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | — | SMTP login |
| `MAIL_FROM` | `no-reply@lms.local` in the `local` profile only | Sender address of every email |

## Useful endpoints

- `GET /actuator/health`
- `/swagger-ui.html` — API docs; authorize with a bearer token
- `POST /api/v1/auth/registrations`, `/registrations/resend`, `/registrations/verify` — signup
- `POST /api/v1/auth/login`, `/api/v1/auth/refresh` — sessions

## Tests

```sh
./gradlew build
```

Starts a PostgreSQL 18 container, builds it with the migrations, and exercises security, the database rules and the API conventions.
