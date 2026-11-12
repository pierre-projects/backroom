-- Phase 4: internal restock requests and the deliveries recorded against them.
-- Approval is coordination only; stock changes solely through inventory stock operations.

CREATE TABLE restock_requests (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    number integer NOT NULL CHECK (number > 0),
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    requested_quantity numeric(15,3) NOT NULL CHECK (requested_quantity > 0),
    approved_quantity numeric(15,3) CHECK (approved_quantity > 0),
    received_quantity numeric(15,3) NOT NULL CHECK (received_quantity >= 0),
    status varchar(20) NOT NULL CHECK (status IN ('SUBMITTED','APPROVED','PARTIALLY_RECEIVED','FULFILLED','REJECTED','CANCELLED','CLOSED_SHORT')),
    requester_id uuid NOT NULL REFERENCES users(id),
    requester_name varchar(100) NOT NULL,
    note varchar(500),
    decided_by uuid REFERENCES users(id),
    decided_by_name varchar(100),
    decided_at timestamptz,
    decision_note varchar(500),
    closed_by uuid REFERENCES users(id),
    closed_by_name varchar(100),
    closed_at timestamptz,
    close_reason varchar(500),
    updated_at timestamptz NOT NULL,
    UNIQUE (organization_id, id),
    CONSTRAINT restock_requests_number_uq UNIQUE (organization_id, number),
    FOREIGN KEY (organization_id, item_id) REFERENCES items(organization_id, id),
    FOREIGN KEY (organization_id, location_id) REFERENCES locations(organization_id, id),
    -- An approved quantity exists exactly for requests that were approved.
    CHECK ((approved_quantity IS NULL) = (status IN ('SUBMITTED','REJECTED','CANCELLED'))),
    CHECK (approved_quantity IS NULL OR received_quantity <= approved_quantity),
    CHECK (received_quantity = 0 OR status IN ('APPROVED','PARTIALLY_RECEIVED','FULFILLED','CLOSED_SHORT')),
    CHECK (status <> 'FULFILLED' OR received_quantity = approved_quantity),
    CHECK (status <> 'PARTIALLY_RECEIVED' OR (received_quantity > 0 AND received_quantity < approved_quantity)),
    CHECK (status <> 'REJECTED' OR decision_note IS NOT NULL),
    CHECK (status <> 'CLOSED_SHORT' OR close_reason IS NOT NULL)
);
CREATE INDEX restock_requests_status_idx ON restock_requests(organization_id, status, created_at DESC, id DESC);
CREATE INDEX restock_requests_slot_idx ON restock_requests(organization_id, item_id, location_id);

-- One row per delivery recorded against a request, tied to the stock operation that added the stock.
CREATE TABLE restock_receipts (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    request_id uuid NOT NULL,
    stock_operation_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL CHECK (quantity > 0),
    receiver_id uuid NOT NULL REFERENCES users(id),
    receiver_name varchar(100) NOT NULL,
    CONSTRAINT restock_receipts_operation_uq UNIQUE (organization_id, stock_operation_id),
    FOREIGN KEY (organization_id, request_id) REFERENCES restock_requests(organization_id, id),
    FOREIGN KEY (organization_id, stock_operation_id) REFERENCES stock_operations(organization_id, id)
);
CREATE INDEX restock_receipts_request_idx ON restock_receipts(organization_id, request_id, created_at);

-- Delivery history is permanent, like stock movements (function from V4).
CREATE TRIGGER restock_receipts_immutable BEFORE UPDATE OR DELETE ON restock_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_stock_history_change();
