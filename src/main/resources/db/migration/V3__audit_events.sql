CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    actor_id uuid NOT NULL REFERENCES users(id),
    actor_name varchar(100) NOT NULL,
    action varchar(80) NOT NULL,
    resource_type varchar(30) NOT NULL,
    resource_id uuid NOT NULL,
    metadata jsonb NOT NULL
);
CREATE INDEX audit_events_activity_idx ON audit_events(organization_id, created_at DESC, id DESC);
