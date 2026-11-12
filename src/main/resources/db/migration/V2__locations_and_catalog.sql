CREATE TABLE locations (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(120) NOT NULL,
    type varchar(20) NOT NULL CHECK (type IN ('ROOM','SHELF','CABINET','BIN')),
    parent_id uuid,
    archived boolean NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, parent_id) REFERENCES locations(organization_id, id),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX locations_parent_idx ON locations(organization_id, parent_id, archived);
CREATE TABLE items (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(160) NOT NULL,
    sku varchar(80),
    unit varchar(20) NOT NULL CHECK (unit IN ('EACH','GRAM','KILOGRAM','MILLILITER','LITER')),
    archived boolean NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, sku),
    CHECK (sku IS NULL OR (sku = upper(btrim(sku)) AND length(sku) > 0))
);
CREATE INDEX items_catalog_idx ON items(organization_id, archived, name, id);
CREATE TABLE item_placements (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    active boolean NOT NULL,
    FOREIGN KEY (organization_id, item_id) REFERENCES items(organization_id, id),
    FOREIGN KEY (organization_id, location_id) REFERENCES locations(organization_id, id),
    UNIQUE (organization_id, item_id, location_id)
);
CREATE INDEX placements_location_idx ON item_placements(organization_id, location_id, active);
