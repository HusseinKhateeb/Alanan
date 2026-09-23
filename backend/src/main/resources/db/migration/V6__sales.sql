CREATE TABLE sales (
 id UUID PRIMARY KEY, invoice_number VARCHAR(40) NOT NULL UNIQUE,
 customer_id UUID NOT NULL REFERENCES customers(id), customer_name VARCHAR(200) NOT NULL,
 invoice_date DATE NOT NULL, notes VARCHAR(2000), total NUMERIC(18,2) NOT NULL CHECK(total > 0),
 paid NUMERIC(18,2) NOT NULL CHECK(paid >= 0 AND paid <= total),
 payment_method_id BIGINT REFERENCES payment_methods(id), payment_method_name VARCHAR(100),
 status VARCHAR(12) NOT NULL CHECK(status IN ('OPEN','PARTIAL','PAID')),
 negative_stock BOOLEAN NOT NULL, created_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 version BIGINT NOT NULL DEFAULT 0, request_id UUID NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE(created_by,request_id), CHECK(paid=0 OR payment_method_id IS NOT NULL)
);
CREATE INDEX sales_customer_date ON sales(customer_id,invoice_date DESC,id);
CREATE INDEX sales_date ON sales(invoice_date DESC,id);
CREATE TABLE sale_items (
 id UUID PRIMARY KEY, sale_id UUID NOT NULL REFERENCES sales(id), line_number INTEGER NOT NULL,
 product_id UUID REFERENCES products(id), container_type_id UUID REFERENCES container_types(id),
 item_name VARCHAR(200) NOT NULL, product_code VARCHAR(60),
 item_type VARCHAR(20) NOT NULL CHECK(item_type IN ('WEIGHT_PRODUCT','PIECE_PRODUCT','SPICE_CONTAINER')),
 selling_mode VARCHAR(12) NOT NULL CHECK(selling_mode IN ('BY_WEIGHT','BY_AMOUNT','BY_PIECE')),
 quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0), unit_price NUMERIC(18,2) NOT NULL CHECK(unit_price > 0),
 requested_amount NUMERIC(18,2), line_total NUMERIC(18,2) NOT NULL CHECK(line_total > 0),
 cost_at_sale NUMERIC(18,6), cost_total NUMERIC(18,2),
 UNIQUE(sale_id,line_number),
 CHECK((item_type='SPICE_CONTAINER' AND product_id IS NULL AND container_type_id IS NOT NULL AND cost_at_sale IS NULL AND cost_total IS NULL)
 OR (item_type<>'SPICE_CONTAINER' AND product_id IS NOT NULL AND container_type_id IS NULL)),
 CHECK((item_type='WEIGHT_PRODUCT' AND selling_mode IN ('BY_WEIGHT','BY_AMOUNT')) OR (item_type<>'WEIGHT_PRODUCT' AND selling_mode='BY_PIECE' AND quantity=trunc(quantity))),
 CHECK((selling_mode='BY_AMOUNT' AND requested_amount=line_total) OR (selling_mode<>'BY_AMOUNT' AND requested_amount IS NULL))
);
ALTER TABLE inventory_movements ADD COLUMN sale_item_id UUID UNIQUE REFERENCES sale_items(id);
CREATE TABLE sale_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, sale_id UUID NOT NULL REFERENCES sales(id),
 user_id UUID NOT NULL REFERENCES users(id), action VARCHAR(30) NOT NULL,
 new_values JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
