# AGENTS.md

## Domain docs

- **Glossary**: `CONTEXT.md` (repo root) — use its terms when naming things; don't drift to synonyms.
- **Decisions**: `docs/adr/` — read the ones covering the area you're touching, and flag rather than silently contradict them.

## Project conventions

Kotlin, Spring Boot 4 (Spring MVC on virtual threads), PostgreSQL 18, Flyway, Exposed DSL, multi-module Gradle. The mobile client is a Kotlin Multiplatform app whose data layer follows the conventions of the `second-line` Android project; the API is shaped for that client.

### Module layout

Modules are split by business capability, and each owns the tables of its capability:

- **`:app`** — `LmsApplication`, configuration, and **all** Flyway migrations (`app/src/main/resources/db/migration`). The schema is one database, so migrations are numbered in one place.
- **`:core`** — infrastructure every capability shares: security, the database user context, problem responses, paging, JSON. It owns no tables. Its `testFixtures` carry the Testcontainers database and the Exposed schema drift check.
- **Capability modules**, added with their features: `:identity` (users, roles, permissions, teacher emails, tokens), `:catalog` (books, authors, publishers, categories, languages), `:circulation` (reservations, waitlist), `:engagement` (reviews, favourites), `:notifications`.

A capability module depends on `:core` and, when it must, on another capability — never the reverse of an existing dependency. Its Exposed `Table` objects live with it, each covered by an `assertSchemaMatches(...)` test.

### Database

- **Never edit an applied migration.** Every schema or data change is a new `V<n>__<what>.sql`. `V1__baseline.sql` is a `pg_dump` of the database as it stood before the backend existed.
- Authorization is enforced twice (ADR 0001): coarse permission checks in Spring (`@PreAuthorize("hasAuthority('books.add')")`), and the triggers as the last line of defense. Fine-grained rules (ownership, admin-only columns) live in the triggers.
- Every transaction acts for the authenticated user through `app.current_user_id`, set by `UserContextTransactionManager`. Work on behalf of a user who is not logged in goes through `DbUserContext.runAs(userId) { … }`, called outside any transaction.
- Triggers raise access denials with `ERRCODE = 'insufficient_privilege'` (→ 403) and business-rule violations with a standard SQLSTATE; never a bare `RAISE EXCEPTION`, which arrives as an unmapped 500.
- A migration that writes to a guarded table first runs `SELECT set_config('app.current_user_id', '<admin id>', true);`.
- The application connects as `lms_app`, which owns nothing; Flyway connects as the owner. New tables are granted to `lms_app` automatically by default privileges.
- Data access is Exposed DSL inside Spring `@Transactional` (ADR 0002). No DAO API, no JPA.

### HTTP API

- Paths start with `/api/v1`. JSON is snake_case everywhere, set globally — no per-field naming annotations.
- Lists are paged: `?page=0&size=20` (zero-based, max 100) in, `PageResponse` out (`content` + `page {size, number, total_elements, total_pages}`). Bind `PageQuery` with `@Valid @ModelAttribute`; build the response with `Query.toPage(...)`.
- Failures are RFC 9457 problem responses with a `code`. Codes any module may answer with are in `:core`'s `ErrorCode`; a code only one feature answers with lives in that feature's module (e.g. `IdentityErrorCode`). Report failures with `ApiException(status, code, detail)`; never build an error body by hand.
- **401 means only "your access token is missing, expired or invalid"** — the client refreshes its token on every 401. "Authenticated but not allowed" is 403.
- Paths that need no token are declared by a `PublicEndpoints` bean in the module that owns them; everything else requires authentication.

### Comments

- **KDoc (`/** … */`) says WHAT** a class, function or property is — what a caller needs to use it. Present tense, no rationale, never an ADR reference.
- **Ordinary comments (`//`) inside a body say HOW and WHY** — the non-obvious mechanism, the trap a naive rewrite falls into. ADR references belong here: `// … (ADR 0001)`.
- Keep both short; state the trap, drop the story.

### Code style

- One constructor parameter per line, trailing comma, closing paren on its own line, blank line before the first member.
- Functions have an explicit return type and a block body with `return` — no expression bodies for public functions.
- Mapping between layers goes through one mapper per direction, named for its endpoints (`BookRowMapper`, `BookResponseMapper`).
- Request and response fields carry `@field:Schema(example = "…")` with a realistic value, so the API docs show bodies a client can send as-is.
- A Bean Validation constraint we define ourselves (`@PolytechnicEmail`, `@StrongPassword`) is applied as `@field:…` on a constructor property — without the use-site target Kotlin puts it where the validator never looks. Jakarta's own constraints work without it.
- Dependencies use version-catalog accessors (`libs.…`) and type-safe project accessors (`projects.core`); never a hardcoded version in a module's build file.

### Tests

Integration tests extend `IntegrationTest` (`app/src/test/.../support`) and run against a real PostgreSQL 18 in Testcontainers, built by the same Flyway migrations — **Docker must be running**. Use `AsUser.run(userId) { jdbc -> … }` to exercise database rules in a transaction that is always rolled back.
