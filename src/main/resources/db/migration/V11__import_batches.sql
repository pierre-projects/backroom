-- Phase 7: one row per applied CSV import, so a retried import replays its result instead of running twice.
CREATE TABLE import_batches (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    import_key uuid NOT NULL,
    file_sha256 varchar(64) NOT NULL,
    file_name varchar(200),
    created_by uuid NOT NULL REFERENCES users(id),
    row_count integer NOT NULL CHECK (row_count >= 0),
    items_created integer NOT NULL CHECK (items_created >= 0),
    locations_created integer NOT NULL CHECK (locations_created >= 0),
    placements integer NOT NULL CHECK (placements >= 0),
    quantities integer NOT NULL CHECK (quantities >= 0),
    minimums integer NOT NULL CHECK (minimums >= 0),
    skipped integer NOT NULL CHECK (skipped >= 0),
    errors integer NOT NULL CHECK (errors >= 0),
    CONSTRAINT import_batches_key_uq UNIQUE (organization_id, import_key)
);
