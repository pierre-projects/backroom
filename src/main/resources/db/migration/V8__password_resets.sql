-- Phase 5: owner-issued, single-use password reset links. Only the SHA-256 of a token is stored.
ALTER TABLE users ADD COLUMN credentials_version integer NOT NULL DEFAULT 0;

CREATE TABLE password_resets (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    issued_by uuid NOT NULL REFERENCES users(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR revoked_at IS NULL)
);
CREATE INDEX password_resets_open_idx ON password_resets(user_id) WHERE used_at IS NULL AND revoked_at IS NULL;
