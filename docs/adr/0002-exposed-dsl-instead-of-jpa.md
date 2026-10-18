# Exposed DSL instead of JPA

The backend reads and writes the database with JetBrains Exposed's DSL rather than Spring Data JPA, even though JPA is the default for a Spring Boot project. The database changes rows behind the application's back — triggers maintain `books.rating` and `books.copies_available` — and JPA's persistence context would serve stale values for them within a transaction; the DSL simply runs SQL and returns fresh rows. The book search is a dynamic multi-filter query that reads close to SQL in the DSL but is verbose with JPA Criteria, and the DSL returns plain immutable Kotlin data classes with no lazy-loading proxies or hidden N+1 queries. Only the DSL API is used, not Exposed's DAO API.

## Consequences

- Exposed has no startup check comparable to Hibernate's `ddl-auto=validate`, so each module that declares `Table` objects carries a test asserting they match the Flyway-built schema (`assertSchemaMatches` in `:core`'s test fixtures).
- There are no derived repository queries; every query is written by hand.
