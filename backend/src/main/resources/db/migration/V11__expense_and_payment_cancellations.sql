CREATE TABLE expense_cancellations (
 expense_id UUID PRIMARY KEY REFERENCES expenses(id),
 reason VARCHAR(500) NOT NULL,
 cancelled_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE customer_payment_cancellations (
 payment_id UUID PRIMARY KEY REFERENCES customer_payments(id),
 reason VARCHAR(500) NOT NULL,
 refund_confirmed BOOLEAN NOT NULL,
 cancelled_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE supplier_payment_cancellations (
 payment_id UUID PRIMARY KEY REFERENCES supplier_payments(id),
 reason VARCHAR(500) NOT NULL,
 refund_confirmed BOOLEAN NOT NULL,
 cancelled_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE customer_payment_allocations ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE supplier_payment_allocations ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
