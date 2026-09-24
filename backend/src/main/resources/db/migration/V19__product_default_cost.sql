ALTER TABLE products ADD COLUMN default_purchase_cost NUMERIC(18,6)
    CHECK (default_purchase_cost > 0);
