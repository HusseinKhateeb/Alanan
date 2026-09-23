CREATE TABLE purchase_cancellations (
 purchase_invoice_id UUID PRIMARY KEY REFERENCES purchase_invoices(id),
 reason VARCHAR(500) NOT NULL,
 refund_received NUMERIC(18,2) NOT NULL CHECK(refund_received>=0),
 refund_method_id BIGINT REFERENCES payment_methods(id),
 refund_method_name VARCHAR(100),
 confirmed_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((refund_received=0 AND refund_method_id IS NULL AND refund_method_name IS NULL)
     OR (refund_received>0 AND refund_method_id IS NOT NULL AND refund_method_name IS NOT NULL))
);
