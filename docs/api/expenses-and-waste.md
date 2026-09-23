# Expenses and waste

Restart the backend to apply Flyway V8. Existing EXPENSES_CREATE and INVENTORY_ADJUST capabilities control expense and waste writes/history respectively; both are ADMIN-only by default. All endpoints require JWT authentication. Waste cost fields additionally require COST_VIEW and are omitted otherwise.

## Expenses

- `POST /api/expenses`: returns 201 with the saved record.
- `GET /api/expenses?page=0&from=2026-01-01&to=2026-01-31&categoryId=<UUID>`: optional inclusive date/category filters.
- `GET /api/expenses/{id}`: saved expense details.

```json
{
  "requestId": "71eb694d-b47f-4b21-91da-c1393ecce4e6",
  "categoryId": "fc2691b2-de14-44fb-9f51-56550d07f144",
  "amount": "125.50",
  "expenseDate": "2026-09-23",
  "paymentMethodId": null,
  "notes": "Monthly rent"
}
```

The category must be active. A null method selects the active default. Amount must be positive with at most two decimal places. Expense and audit records commit together. Category and payment-method names are stored as snapshots. Expenses do not affect product stock, supplier balances or customer balances.

## Waste

- `POST /api/waste`: returns 201 with the saved record.
- `GET /api/waste?page=0&from=2026-01-01&to=2026-01-31&productId=<UUID>`: optional inclusive date/product filters.
- `GET /api/waste/{id}`: saved waste details, including costs only when permitted.

```json
{
  "requestId": "ce710d62-5789-4ab0-bcbb-a823d3bead4e",
  "productId": "92e58c73-3f42-4b62-83a0-485f26746360",
  "quantity": "0.750",
  "wasteDate": "2026-09-23",
  "reason": "Damaged packaging",
  "notes": "Morning stock inspection"
}
```

Quantity must be positive with at most three decimals; piece products require whole quantities. Product must be active and reason nonblank (maximum 500 characters). The product row is locked before updating stock. Waste, negative inventory movement and audit commit atomically. Current WAC is unchanged and copied to the waste record; cost total rounds HALF_UP to two decimals. Unknown cost stays null. Negative calculated stock is allowed, returned as `negative_stock`, and audited. Product tracking units cannot change after waste history.

## Shared behavior

Dates cannot be in the future; notes are optional, up to 2,000 characters. Histories return `{items, hasMore}`, up to 50 rows per page. Reversed date ranges return INVALID_DATE_RANGE. Response keys use snake_case and decimal values are strings.

Request UUIDs are scoped to the authenticated user and workflow. Identical retries return the existing record; changed content with the same key returns 409 IDEMPOTENCY_CONFLICT. Browser drafts persist before sending and freeze after uncertain outcomes. Retry is manual; the automatic sales outbox does not submit expenses or waste. No editing, deletion, reversal or export endpoints are implemented.
