# 0003 — Purchase transaction integrity

Status: implemented for initial purchase creation.

Use a Spring transaction and PostgreSQL row locks for inventory/WAC updates. Lock referenced supplier/payment records to avoid racing deactivation; lock product records in stable UUID order. A per-year invoice counter is updated in the same transaction.

Idempotency uses a creator/request UUID unique constraint plus a PostgreSQL transaction advisory lock. Store a SHA-256 fingerprint of the typed serialized request. Return the existing receipt for the same payload and refuse a changed payload. This provides retry protection even when the server commits but the browser never receives the response.

Persist the complete submitted request to IndexedDB before sending. An unconfirmed request stays immutable across route changes, reauthentication and restarts; retry never invents a new key. A fresh key is created only for a fresh invoice. No automatic synchronization is claimed yet.

Store immutable-at-creation product/supplier name snapshots in the invoice and a before/after inventory-effect audit record, including payment ID and authoritative creator. Prevent master-data unit changes after purchase history exists.

WAC has six decimal places. Aggregate duplicate product lines before updating WAC so line ordering does not introduce repeated-rounding differences. Invoice currency rounds each line HALF_UP to two decimals. WAC follows acceptance order, not retroactive business-date ordering.

Negative stock and unknown-cost positive stock require reconciliation before purchase valuation. This is a conservative guard for this milestone, not a finalized negative-stock sales policy. Sales, historical edits and reconciliation require their own explicit valuation rules before implementation.

Purchase invoices may expose their entered acquisition prices under PURCHASES_VIEW. COST_VIEW separately protects current WAC and inventory movement costs; no profit endpoints are introduced.

The integration suite verifies rollback after an earlier product update, concurrent stock updates, concurrent same-key retries, numbering uniqueness, partial payments, balance derivation and capability boundaries. Frontend unit/component tests verify exact-decimal previews and draft retry after remount; these do not replace real-browser acceptance tests.

Reference: [PostgreSQL row and transaction advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html).
