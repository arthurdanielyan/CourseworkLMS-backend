# Authorization is enforced in the database too

Every write is authorized twice: Spring Security checks the caller's Permissions first, and PostgreSQL triggers check them again as the last line of defense, so that a bug in the backend cannot corrupt data the caller has no right to touch. The triggers learn who the caller is from `app.current_user_id`, which the backend sets at the start of every transaction with `set_config(..., true)` — transaction-local, so it can never leak to the next request on a pooled connection. The application connects as `lms_app`, a role that is not a superuser and owns no tables, because a superuser can switch triggers off (`session_replication_role`, `DISABLE TRIGGER`) and the defense would be gone; Flyway alone connects as the owner.

## Consequences

- Triggers raise access denials with SQLSTATE `42501` (`insufficient_privilege`), which the backend maps to `403`. A trigger that finds no user set raises `L0001`, which is a backend bug and maps to `500` — never `401`, because the mobile client answers every `401` by refreshing its token and would loop.
- Work done on behalf of a user who is not logged in (password recovery) runs inside `DbUserContext.runAs(userId)` rather than bypassing the triggers.
- Data migrations that write to guarded tables set `app.current_user_id` to an Admin for their own transaction.
