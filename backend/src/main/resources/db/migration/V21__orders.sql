CREATE TABLE orders (
 id UUID PRIMARY KEY,
 order_number VARCHAR(40) NOT NULL UNIQUE,
 customer_id UUID NOT NULL REFERENCES customers(id),
 customer_name VARCHAR(200) NOT NULL,
 order_date DATE NOT NULL,
 notes VARCHAR(2000),
 total NUMERIC(18,2) NOT NULL CHECK (total > 0),
 planned_paid NUMERIC(18,2) NOT NULL CHECK (planned_paid >= 0 AND planned_paid <= total),
 request_json JSONB NOT NULL,
 request_id UUID NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DELIVERED','DELETED')),
 sale_id UUID UNIQUE REFERENCES sales(id),
 version BIGINT NOT NULL DEFAULT 0,
 created_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(created_by,request_id),
 CHECK ((status = 'DELIVERED') = (sale_id IS NOT NULL))
);
CREATE INDEX orders_status_date ON orders(status,order_date DESC,created_at DESC);
CREATE TABLE order_events (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id UUID NOT NULL REFERENCES orders(id),
 user_id UUID NOT NULL REFERENCES users(id),
 action VARCHAR(20) NOT NULL CHECK (action IN ('CREATE','EDIT','DELIVER','DELETE')),
 reason VARCHAR(500),
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
