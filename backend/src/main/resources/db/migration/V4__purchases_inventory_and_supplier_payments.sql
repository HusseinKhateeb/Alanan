CREATE TABLE invoice_counters (
 kind VARCHAR(10) NOT NULL, invoice_year INTEGER NOT NULL, next_value BIGINT NOT NULL CHECK(next_value > 0),
 PRIMARY KEY(kind,invoice_year)
);
CREATE TABLE purchase_invoices (
 id UUID PRIMARY KEY, invoice_number VARCHAR(40) NOT NULL UNIQUE,
 supplier_id UUID NOT NULL REFERENCES suppliers(id), supplier_name VARCHAR(200) NOT NULL,
 invoice_date DATE NOT NULL, supplier_reference VARCHAR(200), notes VARCHAR(2000),
 total NUMERIC(18,2) NOT NULL CHECK(total > 0),
 status VARCHAR(12) NOT NULL CHECK(status IN ('OPEN','PARTIAL','PAID','CANCELLED')),
 created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 version BIGINT NOT NULL DEFAULT 0, request_id UUID NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE(created_by,request_id), UNIQUE(id,supplier_id)
);
CREATE INDEX purchases_supplier_date ON purchase_invoices(supplier_id,invoice_date,id);
CREATE INDEX purchases_date ON purchase_invoices(invoice_date DESC,id);
CREATE TABLE purchase_items (
 id UUID PRIMARY KEY, purchase_invoice_id UUID NOT NULL REFERENCES purchase_invoices(id),
 line_number INTEGER NOT NULL, product_id UUID NOT NULL REFERENCES products(id),
 product_name VARCHAR(200) NOT NULL, product_code VARCHAR(60) NOT NULL,
 tracking_type VARCHAR(10) NOT NULL CHECK(tracking_type IN ('WEIGHT','PIECE')),
 quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
 unit_cost NUMERIC(18,6) NOT NULL CHECK(unit_cost > 0),
 line_total NUMERIC(18,2) NOT NULL CHECK(line_total > 0),
 UNIQUE(purchase_invoice_id,line_number),
 CHECK(tracking_type <> 'PIECE' OR quantity=trunc(quantity))
);
CREATE INDEX purchase_items_product ON purchase_items(product_id);
CREATE TABLE inventory_movements (
 id UUID PRIMARY KEY, product_id UUID NOT NULL REFERENCES products(id),
 movement_type VARCHAR(20) NOT NULL CHECK(movement_type IN ('PURCHASE','SALE','WASTE','CORRECTION','REVERSAL')),
 quantity_change NUMERIC(18,3) NOT NULL CHECK(quantity_change <> 0),
 reference_type VARCHAR(20) NOT NULL, reference_id UUID NOT NULL,
 purchase_item_id UUID UNIQUE REFERENCES purchase_items(id),
 unit_cost NUMERIC(18,6), notes VARCHAR(2000),
 created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX inventory_movements_product_date ON inventory_movements(product_id,created_at DESC,id);
CREATE TABLE supplier_payments (
 id UUID PRIMARY KEY, supplier_id UUID NOT NULL REFERENCES suppliers(id),
 amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
 payment_method_id BIGINT NOT NULL REFERENCES payment_methods(id), payment_method_name VARCHAR(100) NOT NULL,
 payment_date DATE NOT NULL, notes VARCHAR(2000),
 created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id,supplier_id)
);
CREATE INDEX supplier_payments_supplier ON supplier_payments(supplier_id,payment_date,id);
CREATE TABLE supplier_payment_allocations (
 payment_id UUID NOT NULL, purchase_invoice_id UUID NOT NULL, supplier_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
 PRIMARY KEY(payment_id,purchase_invoice_id),
 FOREIGN KEY(payment_id,supplier_id) REFERENCES supplier_payments(id,supplier_id),
 FOREIGN KEY(purchase_invoice_id,supplier_id) REFERENCES purchase_invoices(id,supplier_id)
);
CREATE INDEX allocations_invoice ON supplier_payment_allocations(purchase_invoice_id);
CREATE TABLE purchase_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, purchase_invoice_id UUID NOT NULL REFERENCES purchase_invoices(id),
 user_id UUID NOT NULL REFERENCES users(id), action VARCHAR(30) NOT NULL,
 new_values JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
