# 0005: Sales, stock and customer balances

The approved sales scope extends the specification's original fully-paid V1 with partial payments and named customer balances. Walk-in customers must pay in full so anonymous debt is not accumulated. Payment facts are stored on the immutable sale; V7 adds a separate audited payment/allocation workflow and preserves the original amount as paid_at_sale.

Use BigDecimal on the server and integer-scaled BigInt previews in the browser. Money rounds HALF_UP to two decimals; kg rounds HALF_UP to three. Amount-based sales preserve requested revenue even when rounded kg multiplied by price differs by a cent. Invalid zero or out-of-range results are rejected.

Sales lock product rows in UUID order, matching purchases. The transaction changes stock, freezes WAC/cost snapshots, assigns a counter-based invoice number and records audit data. Failure rolls back all effects. Anonymous containers have no product or stock effect and retain unknown cost. Negative calculated stock is allowed with a persisted warning because inventory is approximate. Sales do not recalculate WAC.

Same-user request-key locking and payload fingerprints serialize duplicate retries. Durable browser drafts freeze the submitted payload before POST; uncertain outcomes are retried unchanged. The later [offline-sales milestone](0006-offline-sales.md) adds an explicit outbox and automatic submission while the authenticated app is open. Product units cannot change after either purchase or sale history.

Sales details omit cost fields for users without COST_VIEW. JWT and current database capabilities remain authoritative on every request. User-scoped drafts and query keys avoid sharing another user's unsaved work.

Tests cover mixed invoices, rounding, container isolation, unknown costs, negative stock, partial balances, permission boundaries, concurrent saves/retries, rollback and draft recovery. Real-browser visual and PWA acceptance remain pending.
