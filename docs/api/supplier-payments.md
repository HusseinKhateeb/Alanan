# Supplier payments

All endpoints require JWT authentication. POST requires `SUPPLIER_PAYMENTS_CREATE` (granted to ADMIN by V5). History/details require `PURCHASES_VIEW`; open invoices accept either capability.

- `POST /api/supplier-payments`: atomically create and fully allocate a payment; returns 201 and a receipt.
- `GET /api/supplier-payments?page=0&supplierId=<optional UUID>`: history, including payments made with purchases.
- `GET /api/supplier-payments/{id}`: payment snapshots and invoice allocations.
- `GET /api/supplier-payments/open-invoices?supplierId=<UUID>&page=0`: outstanding invoices, oldest first.

Lists return `{items, hasMore}`, with up to 50 entries. Money is returned as decimal strings. Read responses use snake_case keys; creation receipts use camelCase.

```json
{
  "requestId": "31d01dfb-726c-49ed-b16d-e791c756e18b",
  "supplierId": "28794f65-839a-4ed6-b610-5ba25cc0c4a4",
  "paymentDate": "2026-09-22",
  "amount": "100.00",
  "paymentMethodId": null,
  "notes": "Bank settlement",
  "mode": "AUTO",
  "allocations": []
}
```

`AUTO` allocates by invoice date, creation time and ID, oldest first. `MANUAL` requires 1–100 distinct `{invoiceId, amount}` allocations whose sum equals the payment amount. Every allocation must fit the current outstanding amount and belong to the selected supplier. A payment cannot create unallocated credit. Dates cannot be in the future; amounts must be positive with at most two decimal places. A null method selects the active default. Inactive suppliers can still be paid.

The receipt includes `id`, `supplierName`, `amount`, `paymentMethodName`, and `allocations` containing `invoiceId`, `invoiceNumber`, `amount`. Each affected invoice becomes PARTIAL or PAID, increments its version, and receives an audit entry. Stock and WAC do not change.

Repeated identical requests from the same user return the stored receipt. Reusing a request ID with changed content returns 409 `IDEMPOTENCY_CONFLICT`. The client persists the submitted body before POST and retries it unchanged after an uncertain outcome. Validation failures return 400 without accounting writes.

Allocation occurs within creation, rather than through a separate auto-allocation endpoint. Editing, cancellation, reallocation, refunds and supplier credits are not implemented.
