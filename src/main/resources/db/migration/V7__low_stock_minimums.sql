-- Phase 5: optional minimum quantity per item at each location. Stock below it is "low".
ALTER TABLE item_placements ADD COLUMN minimum_quantity numeric(15,3) CHECK (minimum_quantity > 0);
CREATE INDEX placements_minimum_idx ON item_placements(organization_id, item_id, location_id) WHERE minimum_quantity IS NOT NULL AND active;
