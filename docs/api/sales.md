# Sales

Restart the backend to apply Flyway V6. JWT authentication is required. ADMIN and EMPLOYEE have SALES_CREATE and SALES_VIEW by default. Cost fields in invoice details additionally require COST_VIEW.

- `POST /api/sales`: SALES_CREATE, returns 201 and `{id, invoiceNumber, total, paid, outstanding, status, negativeStock}`.
- `GET /api/sales?page=0&customerId=<optional UUID>`: SALES_VIEW, paginated history.
- `GET /api/sales/{id}`: SALES_VIEW, invoice snapshots and items.
- `GET /api/customer-balances?page=0`: SALES_VIEW, named customers' total sales, paid and outstanding amounts.

Lists return `{items, hasMore}`, up to 50 items. Read responses use snake_case fields; money and quantities are decimal strings.

```json
{
  "requestId": "203e5551-c9eb-4eec-a5ed-db49390648c5",
  "customerId": null,
  "invoiceDate": "2026-09-23",
  "notes": "Walk-in sale",
  "paidNow": "20.00",
  "paymentMethodId": null,
  "items": [{
    "productId": "90dedf22-bb38-4571-8c73-21671c12252a",
    "containerTypeId": null,
    "mode": "BY_AMOUNT",
    "quantity": null,
    "requestedAmount": "20.00",
    "unitPrice": "45.00"
  }]
}
```

Each of 1–100 items selects exactly one product or container type. Weight products accept BY_WEIGHT (quantity) or BY_AMOUNT (requestedAmount). Piece products and containers require BY_PIECE and a whole quantity. BY_AMOUNT stores requested revenue unchanged and rounds kg to three decimals HALF_UP. Other line totals round to two decimals HALF_UP. Zero rounded quantities/totals are rejected. Prices are line overrides and never change master defaults.

Null customer selects the active walk-in customer. Walk-in sales must be fully paid. Named customers allow zero, partial or full payment; overpayment is rejected. Null payment method selects the active default when paidNow is positive. Later settlement is available through [customer payments](customer-payments.md). Refunds and invoice editing are not implemented.

One transaction creates the numbered invoice, item snapshots, payment facts, stock movements and audit entry. Product sales deduct quantity and freeze current WAC and cost total. Unknown cost remains null. Containers never select a spice, deduct stock or invent cost. Negative stock is allowed, returned as a warning and recorded in audit. Cost fields are omitted entirely for users without COST_VIEW.

The request UUID is scoped to the authenticated user. An identical retry returns the saved receipt; changed content with the same key returns 409 IDEMPOTENCY_CONFLICT. Browser drafts are user-scoped and persist the immutable request before sending it. Retry is manual; automatic offline synchronization is not implemented.

After V7, `paid` is cumulative and `paid_at_sale` preserves the original payment. Sale details include later payment allocations in `payments`.
