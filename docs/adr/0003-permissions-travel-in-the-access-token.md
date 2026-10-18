# Permissions travel in the access token

A user's Role and Permissions are written into the access token's `role` and `perms` claims when `:identity` issues it, and `:core` turns those claims into Spring authorities without touching the database. This keeps `:core` free of any dependency on the tables `:identity` owns, and costs no lookup per request. The price is staleness: a change to a Role's Permissions reaches a logged-in user only when their access token next refreshes, at most one token lifetime later. That is accepted because the database triggers check live Permissions on every write (ADR 0001), so a stale token can at worst produce a `403` from the database, never an unauthorized change.

## Considered Options

- **A `PermissionLookup` port in `:core`, implemented by `:identity` with a cache** — always fresh, but makes the core depend on a feature module being present and adds a lookup to every request.
