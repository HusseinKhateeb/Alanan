CREATE TABLE products (
 id UUID PRIMARY KEY, code VARCHAR(60) NOT NULL, name VARCHAR(200) NOT NULL,
 category VARCHAR(10) NOT NULL CHECK (category IN ('SPICE','HONEY','OTHER')),
 tracking_type VARCHAR(10) NOT NULL CHECK (tracking_type IN ('WEIGHT','PIECE')),
 default_selling_price NUMERIC(18,2) NOT NULL CHECK(default_selling_price > 0),
 current_wac NUMERIC(18,6), current_stock NUMERIC(18,3) NOT NULL DEFAULT 0,
 notes VARCHAR(2000), active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (category <> 'SPICE' OR tracking_type = 'WEIGHT'),
 CHECK (category <> 'HONEY' OR tracking_type = 'PIECE')
);
CREATE UNIQUE INDEX products_code_unique ON products(lower(code));
CREATE TABLE customers (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, phone VARCHAR(60),
 normalized_phone VARCHAR(30), address VARCHAR(500), notes VARCHAR(2000),
 is_walk_in BOOLEAN NOT NULL DEFAULT FALSE,
 active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX customers_phone_unique ON customers(normalized_phone) WHERE normalized_phone IS NOT NULL;
CREATE UNIQUE INDEX customers_one_walk_in ON customers(is_walk_in) WHERE is_walk_in;
INSERT INTO customers(id,name,is_walk_in) VALUES ('00000000-0000-0000-0000-000000000001','Walk-in Customer',TRUE);
CREATE TABLE suppliers (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, phone VARCHAR(60), address VARCHAR(500), notes VARCHAR(2000),
 active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE container_types (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, default_price NUMERIC(18,2) NOT NULL CHECK(default_price > 0),
 notes VARCHAR(2000), active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX container_types_name_unique ON container_types(lower(name));
CREATE TABLE expense_categories (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX expense_categories_name_unique ON expense_categories(lower(name));
CREATE UNIQUE INDEX payment_methods_name_unique ON payment_methods(lower(name));
CREATE INDEX products_name_search ON products(lower(name) text_pattern_ops);
CREATE INDEX customers_name_search ON customers(lower(name) text_pattern_ops);
CREATE INDEX suppliers_name_search ON suppliers(lower(name) text_pattern_ops);
CREATE TABLE master_data_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES users(id), entity_type VARCHAR(40) NOT NULL,
 entity_id VARCHAR(40) NOT NULL, action VARCHAR(10) NOT NULL,
 old_values JSONB, new_values JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX master_data_audit_entity ON master_data_audit(entity_type,entity_id,created_at);
