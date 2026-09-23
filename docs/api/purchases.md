# Purchase invoices, inventory and supplier balances

## Implemented endpoints

| Method | Path | Capability |
| --- | --- | --- |
| POST | /api/purchases | PURCHASES_CREATE |
| GET | /api/purchases?page=0&supplierId=UUID | PURCHASES_VIEW |
| GET | /api/purchases/{id} | PURCHASES_VIEW |
| GET | /api/supplier-balances?page=0 | PURCHASES_VIEW |
| GET | /api/inventory?page=0 | PURCHASES_VIEW, PURCHASES_CREATE, INVENTORY_ADJUST or COST_VIEW |
| GET | /api/inventory/movements?productId=UUID&page=0 | Same as inventory |

Lists return `{items, hasMore}` and are bounded to 50 rows. Monetary values and quantities are JSON strings. Inventory WAC and movement unit costs are omitted unless COST_VIEW is granted. Purchase-view permission exposes the commercial costs on purchase invoices; it does not grant inventory WAC/profit access.

## Creating a purchase

The request contains:

- requestId: client-generated UUID, stable across retries.
- supplierId: active supplier UUID.
- invoiceDate: ISO date, not in the future.
- supplierReference, notes: optional strings (200 / 2000 characters).
- paidNow: decimal string, zero or positive, at most total.
- paymentMethodId: active method ID, or null to use the current active default when payment is positive.
- items: 1–100 objects with productId, quantity (up to 3 decimals) and unitCost (up to 6 decimals). Both must be positive; piece quantities must be whole.

No total, invoice number, creator or WAC supplied by the client is authoritative. The backend calculates all financial effects.

The receipt contains id, invoiceNumber, total, paid, outstanding and status (OPEN, PARTIAL or PAID). Invoice details preserve supplier/product names and tracking units used at creation and include payments allocated to that invoice. There is no editing, cancellation or PDF endpoint yet.

## Transaction and retry guarantees

Each accepted purchase atomically creates the invoice, items, inventory movements, WAC/stock changes, optional supplier payment/allocation and an audit entry. Product locks are acquired in UUID order. Number allocation uses a transactional per-year counter; numbers are assigned only at acceptance, using the invoice date's year.

Request keys are unique per authenticated creator. A transaction-scoped lock serializes same-key retries. A repeated identical request returns the existing receipt without new effects. Reusing the key with a different serialized request returns IDEMPOTENCY_CONFLICT (409). Clients must preserve the exact payload while the outcome is unknown.

Line totals use HALF_UP to two decimals. Invoice total is the sum of rounded line totals. Each line must round to at least 0.01 NIS. WAC uses aggregate unrounded acquisition value per product and is rounded to six decimals once per purchase/product. Existing stock must be nonnegative; positive stock must have known WAC. Otherwise the entire purchase fails with WAC_REVIEW_REQUIRED. Backdated invoice dates do not retroactively reorder valuation: WAC follows transaction acceptance order.

Supplier balances are derived from non-cancelled invoice totals minus their allocations, not manually maintained balances. Standalone later payments are available through the [supplier-payment API](supplier-payments.md). Allocation editing is not implemented.

## Client behavior

The purchase form stores one draft per user in IndexedDB. Its exact submitted payload is durably stored before the HTTP request. An uncertain outcome locks editing; manual retry resends the same key and payload. Confirmed validation/permission rejection makes the draft editable again. A saved receipt is retained locally until the user starts a new invoice.

This milestone provides durable drafts and safe manual retry, not automatic background outbox synchronization. Do not describe an offline draft as synchronized. The PWA update button is disabled while a form is open. Master-data and supplier inline creation preserve the purchase draft.

The browser preview uses BigInt-scaled decimal arithmetic, never binary floating-point for amounts. The backend is authoritative.
