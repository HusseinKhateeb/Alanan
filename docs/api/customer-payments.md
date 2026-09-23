# Customer payments

Restart the backend to apply V7, then sign in again. `CUSTOMER_PAYMENTS_CREATE` is granted to ADMIN by default. History and details require SALES_VIEW; open invoices accept either permission. All endpoints require JWT authentication.

- `POST /api/customer-payments`: create and fully allocate a payment, returning 201.
- `GET /api/customer-payments?page=0&customerId=<optional UUID>`: standalone payment history.
- `GET /api/customer-payments/{id}`: payment snapshot and allocations.
- `GET /api/customer-payments/open-invoices?customerId=<UUID>&page=0`: oldest unpaid sales first.

Lists return `{items, hasMore}` with at most 50 rows. Money is returned as decimal strings.

```json
{
  "requestId": "686b39f4-2d37-4410-882c-484065d40b87",
  "customerId": "40eaa5bb-23d2-4c45-829d-464e7c0ea59e",
  "paymentDate": "2026-09-23",
  "amount": "100.00",
  "paymentMethodId": null,
  "notes": "Debt settlement",
  "mode": "AUTO",
  "allocations": []
}
```

AUTO allocates oldest first by invoice date, creation time and ID. MANUAL accepts 1–100 distinct `{invoiceId, amount}` entries summing exactly to the payment. Allocations must belong to the customer and fit the current outstanding amount. No unallocated credit is created. A null payment method uses the active default. Inactive named customers may settle debt; walk-in customers cannot receive standalone payments.

The receipt contains `id`, `customerName`, `amount`, `paymentMethodName`, and `allocations` containing `invoiceId`, `invoiceNumber`, `amount`. Read endpoints use snake_case fields.

The customer row is locked before planning allocations; sale creation holds a share lock on that row. Competing payments therefore cannot overpay the same sales. Payment, allocations, cumulative sale paid/status/version and audit changes commit together. Stock and cost snapshots do not change. Same-user request UUIDs support exact retries; changed content returns 409 IDEMPOTENCY_CONFLICT.

V7 preserves the original sales payment in `paid_at_sale`; `paid` now includes later payments. Sale details expose both the original payment and standalone payment allocations. History lists only standalone payments, avoiding duplication of the original sale payment. Customer balances use the cumulative paid value.

The bilingual form stores a user-scoped draft and freezes the submitted request before POST. Unknown outcomes require manual same-request retry. Editing, refunds, reallocation and automatic offline synchronization are not included.
