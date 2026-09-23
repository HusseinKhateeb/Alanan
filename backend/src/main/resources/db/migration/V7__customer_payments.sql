INSERT INTO permissions(code,description) VALUES ('CUSTOMER_PAYMENTS_CREATE','Record and allocate customer payments');
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.name='ADMIN' AND p.code='CUSTOMER_PAYMENTS_CREATE';

-- Preserve the original payment independently from the cumulative paid balance.
ALTER TABLE sales ADD COLUMN paid_at_sale NUMERIC(18,2);
UPDATE sales SET paid_at_sale=paid;
ALTER TABLE sales ALTER COLUMN paid_at_sale SET NOT NULL;
ALTER TABLE sales ADD CONSTRAINT sales_initial_payment CHECK(paid_at_sale>=0 AND paid_at_sale<=paid);
ALTER TABLE sales ADD CONSTRAINT sales_customer_unique UNIQUE(id,customer_id);
-- A later payment has its own method, even if the original sale was unpaid.
ALTER TABLE sales DROP CONSTRAINT sales_check1;
ALTER TABLE sales ADD CONSTRAINT sales_initial_method CHECK(paid_at_sale=0 OR payment_method_id IS NOT NULL);

CREATE TABLE customer_payments (
 id UUID PRIMARY KEY, customer_id UUID NOT NULL REFERENCES customers(id), customer_name VARCHAR(200) NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0),
 payment_method_id BIGINT NOT NULL REFERENCES payment_methods(id), payment_method_name VARCHAR(100) NOT NULL,
 payment_date DATE NOT NULL, notes VARCHAR(2000), created_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 allocation_mode VARCHAR(10) NOT NULL CHECK(allocation_mode IN ('MANUAL','AUTO')),
 request_id UUID NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE(created_by,request_id), UNIQUE(id,customer_id)
);
CREATE INDEX customer_payments_history ON customer_payments(payment_date DESC,created_at DESC,id);
CREATE INDEX customer_payments_customer ON customer_payments(customer_id,payment_date,id);
CREATE TABLE customer_payment_allocations (
 payment_id UUID NOT NULL, sale_id UUID NOT NULL, customer_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), PRIMARY KEY(payment_id,sale_id),
 FOREIGN KEY(payment_id,customer_id) REFERENCES customer_payments(id,customer_id),
 FOREIGN KEY(sale_id,customer_id) REFERENCES sales(id,customer_id)
);
CREATE INDEX customer_allocations_sale ON customer_payment_allocations(sale_id);
CREATE TABLE customer_payment_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id UUID NOT NULL REFERENCES customer_payments(id),
 user_id UUID NOT NULL REFERENCES users(id), action VARCHAR(30) NOT NULL, new_values JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
