CREATE TABLE users (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    display_name varchar(100) NOT NULL,
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    CHECK (email = lower(btrim(email)))
);
CREATE TABLE organizations (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    name varchar(120) NOT NULL
);
CREATE TABLE memberships (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role varchar(20) NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'EMPLOYEE')),
    active boolean NOT NULL,
    UNIQUE (organization_id, user_id)
);
CREATE INDEX memberships_user_active_idx ON memberships(user_id, active, organization_id);
CREATE TABLE invitations (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    role varchar(20) NOT NULL CHECK (role IN ('MANAGER', 'EMPLOYEE')),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    accepted_at timestamptz,
    accepted_by uuid REFERENCES users(id),
    CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CHECK (revoked_at IS NULL OR accepted_at IS NULL)
);
CREATE INDEX invitations_organization_idx ON invitations(organization_id, created_at DESC);
