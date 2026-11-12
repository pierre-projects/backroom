# Database migrations

Flyway initializes its schema history in Phase 0. There are no business tables yet.

Phase 1 introduces the first versioned SQL migration, named V1__describe_change.sql.
Hibernate validates the schema and never creates or updates it.
Committed migrations must not be edited after they have been applied.
