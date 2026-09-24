CREATE TABLE inventory_adjustments (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id),
    product_name VARCHAR(200) NOT NULL,
    product_code VARCHAR(60) NOT NULL,
    tracking_type VARCHAR(10) NOT NULL,
    quantity NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(18,6) NOT NULL CHECK (unit_cost > 0),
    recorded_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    old_stock NUMERIC(18,3) NOT NULL,
    new_stock NUMERIC(18,3) NOT NULL,
    old_wac NUMERIC(18,6),
    new_wac NUMERIC(18,6) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    UNIQUE (created_by, request_id)
);
CREATE INDEX inventory_adjustments_product_date ON inventory_adjustments(product_id, recorded_date DESC, id);

ALTER TABLE inventory_movements DROP CONSTRAINT inventory_movements_movement_type_check;
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_movement_type_check
    CHECK (movement_type IN ('PURCHASE','SALE','WASTE','CORRECTION','REVERSAL','OPENING'));

CREATE TABLE inventory_adjustment_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    adjustment_id UUID NOT NULL REFERENCES inventory_adjustments(id),
    user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(30) NOT NULL,
    new_values JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
