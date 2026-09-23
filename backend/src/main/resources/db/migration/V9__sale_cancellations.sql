ALTER TABLE sales DROP CONSTRAINT sales_status_check;
ALTER TABLE sales ADD CONSTRAINT sales_status_check CHECK (status IN ('OPEN','PARTIAL','PAID','CANCELLED'));

CREATE TABLE sale_cancellations (
 sale_id UUID PRIMARY KEY REFERENCES sales(id),
 reason VARCHAR(500) NOT NULL,
 refund_amount NUMERIC(18,2) NOT NULL CHECK(refund_amount>=0),
 refund_method_id BIGINT REFERENCES payment_methods(id),
 refund_method_name VARCHAR(100),
 confirmed_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((refund_amount=0 AND refund_method_id IS NULL AND refund_method_name IS NULL)
     OR (refund_amount>0 AND refund_method_id IS NOT NULL AND refund_method_name IS NOT NULL))
);
