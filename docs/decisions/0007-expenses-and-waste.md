# 0007: Expense and waste records

Expenses and waste use separate feature modules and immutable accounting tables. They share only request serialization/fingerprinting and existing pagination utilities. Existing capabilities protect both creation and history, with COST_VIEW separately protecting waste cost fields.

Expense amounts and references represent a paid expense; there is no payable balance. Payment method and category names are snapshotted. A separate audit row commits with the expense.

Waste locks the product row using the same stock serialization boundary as purchases and sales. It deducts stock without changing WAC, stores current cost snapshots, writes a WASTE inventory movement and records before/after stock in audit. Negative stock is allowed with a visible persisted warning, consistent with the approximate-inventory policy. Unknown costs remain null. The waste date is the business event date; current WAC at recording time is used even for backdated entries, without historical valuation recalculation.

Both workflows lock the authenticated user/request key before checking fingerprints. Identical retries do not create duplicate records, stock changes or audit entries. Transactional writes roll back on failure. Product units become immutable after waste as well as sale/purchase history.

The bilingual UI stores separate drafts per user/workflow and persists submitted requests before POST. Network uncertainty keeps input locked for exact retry; definitive validation/permission rejection allows correction. Expenses and waste are not added to the sales-only automatic outbox.

Verification covers cost snapshots, unknown cost, negative stock, reference validation, fractional pieces, filters, permissions, unit locking and concurrent retries. Frontend tests cover remount retry, preserved validation input, warnings/cost visibility and filters. Real-browser/device acceptance remains pending.
