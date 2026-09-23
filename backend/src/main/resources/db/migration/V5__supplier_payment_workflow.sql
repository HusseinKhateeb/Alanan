INSERT INTO permissions(code,description) VALUES ('SUPPLIER_PAYMENTS_CREATE','Record and allocate supplier payments');
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.name='ADMIN' AND p.code='SUPPLIER_PAYMENTS_CREATE';

ALTER TABLE supplier_payments ADD COLUMN supplier_name VARCHAR(200);
UPDATE supplier_payments p SET supplier_name=s.name FROM suppliers s WHERE s.id=p.supplier_id;
ALTER TABLE supplier_payments ALTER COLUMN supplier_name SET NOT NULL;
ALTER TABLE supplier_payments ADD COLUMN allocation_mode VARCHAR(10) NOT NULL DEFAULT 'PURCHASE'
 CHECK(allocation_mode IN ('PURCHASE','MANUAL','AUTO'));
ALTER TABLE supplier_payments ADD COLUMN request_id UUID;
ALTER TABLE supplier_payments ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE supplier_payments ADD CONSTRAINT supplier_payment_request_pair CHECK((request_id IS NULL)=(request_hash IS NULL));
CREATE UNIQUE INDEX supplier_payments_request_unique ON supplier_payments(created_by,request_id) WHERE request_id IS NOT NULL;
CREATE INDEX supplier_payments_history ON supplier_payments(payment_date DESC,created_at DESC,id);

CREATE TABLE supplier_payment_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id UUID NOT NULL REFERENCES supplier_payments(id),
 user_id UUID NOT NULL REFERENCES users(id),
 action VARCHAR(30) NOT NULL,
 new_values JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX supplier_payment_audit_payment ON supplier_payment_audit(payment_id,created_at);
