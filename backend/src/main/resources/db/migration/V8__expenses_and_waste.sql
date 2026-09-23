CREATE TABLE expenses (
 id UUID PRIMARY KEY, category_id UUID NOT NULL REFERENCES expense_categories(id), category_name VARCHAR(200) NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), expense_date DATE NOT NULL,
 payment_method_id BIGINT NOT NULL REFERENCES payment_methods(id), payment_method_name VARCHAR(100) NOT NULL,
 notes VARCHAR(2000), created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 request_id UUID NOT NULL, request_hash VARCHAR(64) NOT NULL, UNIQUE(created_by,request_id)
);
CREATE INDEX expenses_date ON expenses(expense_date DESC,created_at DESC,id);
CREATE INDEX expenses_category_date ON expenses(category_id,expense_date DESC,id);
CREATE TABLE waste_records (
 id UUID PRIMARY KEY, product_id UUID NOT NULL REFERENCES products(id), product_name VARCHAR(200) NOT NULL,
 product_code VARCHAR(60) NOT NULL, tracking_type VARCHAR(10) NOT NULL CHECK(tracking_type IN ('WEIGHT','PIECE')),
 quantity NUMERIC(18,3) NOT NULL CHECK(quantity>0), waste_date DATE NOT NULL, reason VARCHAR(500) NOT NULL,
 notes VARCHAR(2000), cost_at_waste NUMERIC(18,6), cost_total NUMERIC(18,2), negative_stock BOOLEAN NOT NULL,
 created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 request_id UUID NOT NULL, request_hash VARCHAR(64) NOT NULL, UNIQUE(created_by,request_id),
 CHECK(tracking_type<>'PIECE' OR quantity=trunc(quantity))
);
CREATE INDEX waste_date ON waste_records(waste_date DESC,created_at DESC,id);
CREATE INDEX waste_product_date ON waste_records(product_id,waste_date DESC,id);
ALTER TABLE inventory_movements ADD COLUMN waste_id UUID UNIQUE REFERENCES waste_records(id);
CREATE TABLE expense_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, expense_id UUID NOT NULL REFERENCES expenses(id),
 user_id UUID NOT NULL REFERENCES users(id), action VARCHAR(30) NOT NULL, new_values JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE waste_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, waste_id UUID NOT NULL REFERENCES waste_records(id),
 user_id UUID NOT NULL REFERENCES users(id), action VARCHAR(30) NOT NULL, new_values JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
