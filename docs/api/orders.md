# Orders

Orders use the same customer, product/container, quantity, price, planned payment, payment method, and notes fields as a sale. They require `SALES_CREATE` to create, edit, deliver, or delete and `SALES_VIEW` to list. An order has its own `ORD-YYYY-NNNNNN` number and does not appear in sale reports, customer balances, or inventory movements while open.

- `GET /api/orders?page=0&q=` lists open and delivered orders.
- `POST /api/orders` accepts the same body as `POST /api/sales` and returns the saved order. The `requestId` makes creation retries idempotent.
- `GET /api/orders/{id}` returns the order, saved request, and references needed to edit it.
- `PUT /api/orders/{id}` accepts `{ "version": 0, "sale": <sale request> }`. It replaces the proposed order only while open. A stale version returns `ORDER_VERSION_CONFLICT`.
- `POST /api/orders/{id}/deliver` accepts `{ "version": 0, "deliveryDate": "YYYY-MM-DD" }`. In one transaction, it creates a sale using the delivery date and marks the order delivered with the sale ID. Retrying a delivered order returns the same linked sale. Sale validation, stock updates, payment rules, and invoice numbering are unchanged.
- `DELETE /api/orders/{id}` accepts `{ "version": 0, "reason": "..." }`. It hides an open order from the list but retains its request and reason in the audit history. A delivered order cannot be deleted here; correct or cancel its sale invoice through the sales workflow.

If delivery fails validation or the database write fails, the transaction rolls back, leaving the order open with no sale or inventory movement.
