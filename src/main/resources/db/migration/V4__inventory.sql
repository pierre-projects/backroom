-- Phase 2: stock quantities, immutable movement history and idempotent stock operations.

-- One balance per item placement. Quantities are exact decimals in the item's base unit.
CREATE TABLE stock_balances (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL CHECK (quantity >= 0),
    updated_at timestamptz NOT NULL,
    UNIQUE (organization_id, id),
    CONSTRAINT stock_balances_slot_uq UNIQUE (organization_id, item_id, location_id),
    FOREIGN KEY (organization_id, item_id, location_id) REFERENCES item_placements(organization_id, item_id, location_id)
);
CREATE INDEX stock_balances_location_idx ON stock_balances(organization_id, location_id);

-- Every existing placement starts with an empty balance so counts always have a version to check.
INSERT INTO stock_balances (id, version, created_at, organization_id, item_id, location_id, quantity, updated_at)
SELECT gen_random_uuid(), 0, now(), organization_id, item_id, location_id, 0, now() FROM item_placements;

-- One row per business command. operation_key makes retries idempotent.
CREATE TABLE stock_operations (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    type varchar(20) NOT NULL CHECK (type IN ('OPENING','RECEIPT','USAGE','WASTE','TRANSFER','COUNT','REVERSAL')),
    item_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id),
    actor_name varchar(100) NOT NULL,
    operation_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    reference varchar(120),
    note varchar(500),
    reverses_operation_id uuid,
    UNIQUE (organization_id, id),
    CONSTRAINT stock_operations_key_uq UNIQUE (organization_id, operation_key),
    CONSTRAINT stock_operations_reversal_uq UNIQUE (organization_id, reverses_operation_id),
    FOREIGN KEY (organization_id, item_id) REFERENCES items(organization_id, id),
    FOREIGN KEY (organization_id, reverses_operation_id) REFERENCES stock_operations(organization_id, id),
    CHECK ((type = 'REVERSAL') = (reverses_operation_id IS NOT NULL))
);
CREATE INDEX stock_operations_item_idx ON stock_operations(organization_id, item_id, created_at DESC, id DESC);

-- Signed quantity changes. The sum of changes for a slot equals its balance.
CREATE TABLE stock_movements (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    operation_id uuid NOT NULL,
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    quantity_change numeric(15,3) NOT NULL CHECK (quantity_change <> 0),
    balance_after numeric(15,3) NOT NULL CHECK (balance_after >= 0),
    FOREIGN KEY (organization_id, operation_id) REFERENCES stock_operations(organization_id, id),
    FOREIGN KEY (organization_id, item_id, location_id) REFERENCES stock_balances(organization_id, item_id, location_id)
);
CREATE INDEX stock_movements_item_idx ON stock_movements(organization_id, item_id, created_at DESC, id DESC);
CREATE INDEX stock_movements_location_idx ON stock_movements(organization_id, location_id, created_at DESC, id DESC);
CREATE INDEX stock_movements_operation_idx ON stock_movements(organization_id, operation_id);

-- Physical count observations, including counts that matched the recorded quantity.
CREATE TABLE stock_counts (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    operation_id uuid NOT NULL,
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    counted_quantity numeric(15,3) NOT NULL CHECK (counted_quantity >= 0),
    recorded_quantity numeric(15,3) NOT NULL CHECK (recorded_quantity >= 0),
    CONSTRAINT stock_counts_operation_uq UNIQUE (organization_id, operation_id),
    FOREIGN KEY (organization_id, operation_id) REFERENCES stock_operations(organization_id, id),
    FOREIGN KEY (organization_id, item_id, location_id) REFERENCES stock_balances(organization_id, item_id, location_id)
);

-- Committed history is immutable. Mistakes are corrected with linked reversal operations.
CREATE FUNCTION reject_stock_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Stock history is immutable (%).', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;
CREATE TRIGGER stock_operations_immutable BEFORE UPDATE OR DELETE ON stock_operations
    FOR EACH ROW EXECUTE FUNCTION reject_stock_history_change();
CREATE TRIGGER stock_movements_immutable BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION reject_stock_history_change();
CREATE TRIGGER stock_counts_immutable BEFORE UPDATE OR DELETE ON stock_counts
    FOR EACH ROW EXECUTE FUNCTION reject_stock_history_change();
