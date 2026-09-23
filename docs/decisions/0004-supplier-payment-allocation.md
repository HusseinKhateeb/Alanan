# 0004: Atomic supplier payment allocation

Payments must be fully allocated at creation. Manual allocations exactly equal the payment; automatic allocation settles oldest outstanding invoices first. Rejecting excess payments avoids introducing an undocumented supplier-credit balance.

The transaction first takes an advisory lock for the user/request key and checks its fingerprint. It then locks the supplier row for update, serializing competing payments for that supplier. Purchase creation holds a supplier share lock until commit. Future invoice editing/cancellation or allocation changes must follow this same supplier-first locking protocol.

Planning locks affected invoice rows and checks current allocation totals before inserting the payment. Automatic planning reads bounded batches; no accounting writes happen until the entire amount fits. Payment, allocations, invoice status/version changes and audit records commit together. Supplier balances remain derived from invoices and allocations.

Supplier and payment-method names are stored as snapshots. Inactive suppliers remain payable because deactivation does not remove existing debt. The new creation capability is ADMIN-only by default; reads reuse PURCHASES_VIEW.

The browser keeps one durable draft per user. It saves an immutable submitted request before sending it and permits explicit same-request retry after ambiguous failures. It does not automatically synchronize drafts. A confirmed payment can be followed by a new draft with a new request key.

PostgreSQL integration tests cover concurrency, same-key retries, overpayment, manual validation, inactive suppliers, snapshots, audit records and permissions. Frontend tests cover persistence/retry, manual totals and validation recovery. Real-browser/device acceptance remains pending.
