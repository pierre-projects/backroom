-- Phase 3: stable codes that identify items and locations (Backroom QR tokens and manufacturer barcodes).
-- Codes identify a resource; they never grant access. A code is never rebound to another resource.

CREATE TABLE resource_codes (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    kind varchar(20) NOT NULL CHECK (kind IN ('GENERATED','MANUFACTURER')),
    value varchar(20) NOT NULL,
    item_id uuid,
    location_id uuid,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    created_by uuid NOT NULL REFERENCES users(id),
    revoked_at timestamptz,
    revoked_by uuid REFERENCES users(id),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, item_id) REFERENCES items(organization_id, id),
    FOREIGN KEY (organization_id, location_id) REFERENCES locations(organization_id, id),
    -- Exactly one typed target.
    CHECK ((item_id IS NULL) <> (location_id IS NULL)),
    -- Manufacturer barcodes identify a product type, so they only ever point at items.
    CHECK (kind = 'GENERATED' OR item_id IS NOT NULL),
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)),
    -- Generated tokens: 10 Crockford base32 characters. Manufacturer codes: GTIN-14 with leading zeros kept.
    CHECK (kind <> 'GENERATED' OR value ~ '^[0-9A-HJKMNP-TV-Z]{10}$'),
    CHECK (kind <> 'MANUFACTURER' OR value ~ '^[0-9]{14}$')
);

-- Generated tokens are globally unique and never recycled, even after revocation.
CREATE UNIQUE INDEX resource_codes_token_uq ON resource_codes(value) WHERE kind = 'GENERATED';
-- A barcode identifies at most one active item per organization. A wrong registration must be revoked explicitly.
CREATE UNIQUE INDEX resource_codes_gtin_uq ON resource_codes(organization_id, value) WHERE kind = 'MANUFACTURER' AND status = 'ACTIVE';
-- One active QR code per resource, so reprinting reuses it and concurrent "print" clicks can't create two.
CREATE UNIQUE INDEX resource_codes_active_qr_uq ON resource_codes(organization_id, (coalesce(item_id, location_id)))
    WHERE kind = 'GENERATED' AND status = 'ACTIVE';
CREATE INDEX resource_codes_item_idx ON resource_codes(organization_id, item_id);
CREATE INDEX resource_codes_location_idx ON resource_codes(organization_id, location_id);

-- A code's identity and target are permanent; only revocation may change, and only once.
CREATE FUNCTION guard_resource_code_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Resource codes are permanent; revoke them instead.' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.organization_id <> OLD.organization_id OR NEW.kind <> OLD.kind OR NEW.value <> OLD.value
       OR NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.location_id IS DISTINCT FROM OLD.location_id
       OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'A resource code cannot be reassigned.' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'REVOKED' AND NEW.status <> 'REVOKED' THEN
        RAISE EXCEPTION 'A revoked code stays revoked.' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER resource_codes_guard BEFORE UPDATE OR DELETE ON resource_codes
    FOR EACH ROW EXECUTE FUNCTION guard_resource_code_change();
