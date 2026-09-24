CREATE TABLE quick_customer_requests (
 user_id UUID NOT NULL REFERENCES users(id),
 request_id UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 customer_id UUID NOT NULL REFERENCES customers(id),
 PRIMARY KEY (user_id, request_id)
);
