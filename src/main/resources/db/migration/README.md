# Database migrations

Flyway initializes its schema history in Phase 0 and applies three Phase 1 migrations:
access and organizations, locations and catalog, and audit events.

Phase 2 adds the next versioned migration for inventory quantities and movements.
Hibernate validates the schema and never creates or updates it.
Committed migrations must not be edited after they have been applied.
