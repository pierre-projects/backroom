-- Phase 6: one photo per item, resized in the browser and stored here (no object storage on a $0-5 host).
-- Size and dimension limits are enforced by the application and repeated here as a backstop.
CREATE TABLE item_photos (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    item_id uuid NOT NULL,
    content_type varchar(40) NOT NULL CHECK (content_type = 'image/jpeg'),
    bytes bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 4 AND 614400),
    width integer NOT NULL CHECK (width BETWEEN 1 AND 2048),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 2048),
    updated_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL,
    CONSTRAINT item_photos_item_uq UNIQUE (organization_id, item_id),
    FOREIGN KEY (organization_id, item_id) REFERENCES items(organization_id, id)
);
