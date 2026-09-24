# Implementation status

## Started: repository foundation

- Separate frontend/backend, environment templates, ignore rules and editor conventions.
- Spring Boot and Flyway foundation with users, roles, capabilities and payment methods.
- Business endpoints remain denied unless explicitly enabled and protected by capabilities.
- Responsive bilingual shell; Arabic RTL/English LTR; actual backend health check.
- PWA shell caching, placeholder monogram icons and update prompt.
- Frontend language/connection tests; backend access-denial test; CI build checks.

## Implemented: authentication and permissions

- Explicit one-time server-side administrator provisioning, BCrypt hashing, no public registration.
- JWT login, current user, language preference and logout with server-side revocation.
- Active-account and capability checks against the database on every request.
- Bilingual login, reauthentication on expiry, memory-only tokens and admin-only paginated user directory.
- Admin-only account creation with display name, username, password, preferred language, active status, roles and per-user permission overrides; creation is audited without recording passwords.
- Bounded login throttling, safe error envelopes/request IDs and authentication event records.
- Local startup helper, API documentation and security architecture decision.

## Implemented: master data

- Protected list/create/update APIs and bilingual screens for products, customers, suppliers, container types, payment methods and expense categories.
- Paginated prefix search, active filtering and deactivation instead of deletion.
- Positive decimal prices, enforced spice/honey units, normalized unique customer phone lookup and protected walk-in customer.
- Transactional default-payment changes, optimistic version checks and before/after audit records.
- User-scoped IndexedDB form drafts, explicit discard, duplicate-customer selection and safe error messages.

## Implemented: purchase invoices, inventory and supplier balances

- Multi-product purchase entry with inline supplier creation for admins, decimal previews, payment selection and partial/full payment.
- Server numbering, BigDecimal totals, stock movements, six-decimal WAC and atomic audit/payment/allocation effects.
- Idempotency and stable product locks for safe retry and concurrent saves.
- Invoice list/details with preserved product/supplier names; derived supplier balances and invoice history.
- Inventory quantities/movements with WAC/cost capability checks and a physical-stock estimate label.
- Opening-stock entries for old goods: an authorized user records product, quantity, unit cost, date and reason. The transaction updates stock/WAC and writes an idempotent movement and audit record without creating supplier debt.
- Admin hard deletion for invoices, payments, expenses, waste and opening-stock adjustments, with stock reversal where applicable and a surviving deletion-audit snapshot. Linked payments and later stock movements block deletion; paid records require the refund/cancellation flow first.
- User-scoped durable purchase drafts and immutable submitted requests across reconnect/relogin; manual retry after uncertain outcomes.

## Implemented: supplier payments

- Full manual allocation or oldest-invoice-first settlement, with overpayment rejection.
- Supplier locking, atomic audit/status/balance changes and idempotent retries.
- Bilingual entry/history/details, inactive supplier settlement and per-user durable drafts.
- ADMIN-only creation capability; purchase-view permission for history.

## Implemented: sales

- Mixed weight/piece/container items, explicit weight or money input, exact decimal calculations and per-line price overrides.
- Atomic numbering, stock deductions, cost snapshots, payment facts and audit records; concurrent retry protection.
- Named-customer partial payments and derived balances; fully paid walk-in sales.
- Bilingual entry/list/details, per-user durable drafts, manual retry and negative-stock warnings.
- Cost fields restricted to COST_VIEW; anonymous containers do not deduct stock.

## Implemented: customer payments

- Manual and oldest-first allocation to named customer sales, with serialized overpayment protection.
- Cumulative sale paid/status/balance updates, preserved original payment, immutable payment snapshots and audit records.
- ADMIN-only creation, sales-view history, bilingual screens and user-scoped durable retry drafts.
- Inactive customer settlement; walk-in payments and unallocated credit are rejected.

## Implemented: offline sales synchronization

- Per-user complete reference snapshots and network-failure fallback with cache-age notices.
- Durable sales outbox, automatic connected-session retry, multi-tab leases and immutable request IDs.
- Pending/Syncing/Synced/Needs attention panel, rejected-sale correction and preserved drafts.
- Access JWTs remain in memory; same-origin reload restores a seven-day rotating HttpOnly refresh session while online. A seven-day non-secret profile keeps the sale-only workspace available during backend outages, including reopen and token renewal. Queued sales wait for authenticated reconnection. No offline customer creation or service-worker background submission.

## Implemented: expenses and waste

- Expense category/payment snapshots and atomic audit records, with inclusive date/category history filters.
- Waste product/quantity/reason, stock deductions, frozen costs, negative-stock warnings and audit records.
- Capability restrictions, hidden cost fields, exact-request retries and durable bilingual forms.
- Waste history filters by dates/product; product units locked after waste history.

This is not a completed V1 release. Automatic synchronization for non-sales workflows remains open. A daily offsite backup installer and disposable restore drill are available, but no real backup schedule is installed until an offsite destination is configured. Sales and purchase invoice PDFs and bounded full-period report CSV exports are available. Purchase corrections are guarded to invoices without later stock movements; full historical revaluation, user editing/deactivation and password recovery remain pending. See the requirement-by-requirement [v6 specification review](specification-review.md).

## Implemented: read-only audit history

- An `AUDIT_VIEW`-protected API and bilingual screen combine existing master-data, invoice, payment, expense, waste and authentication events.
- History is paginated with type, actor and UTC date filters. Each event exposes its recorded JSON snapshot when present; create-only audit tables have no before snapshot.
- This is partial coverage of specification §12: edit/cancel, user/permission and backup events require the corresponding workflows before they can appear.

## Next milestones

1. Remaining foundation: generated OpenAPI and user editing/password recovery workflows.
2. Invoice integration of master data: offline customer creation and dependency-aware synchronization; online quick customer creation, admin inline supplier creation and reference caching are available.
3. Inventory valuation extensions: negative-stock reconciliation and safe historical invoice edits.
4. Offline extensions: customer creation and dependency-aware synchronization, other accounting operations, and real-device acceptance.
5. Safe historical editing/revaluation, scheduled offsite backups, guarded restore and production QA.

Database integration checks use isolated PostgreSQL Testcontainers. PWA installation and offline behavior require browser/device acceptance tests before release.

## Quick recording improvements — 2026-09-23

- Sale product search accepts Enter to choose the first visible result; selecting a product focuses quantity/amount, and Enter on a valid line adds and focuses the next item. Existing default prices remain editable.
- Customer payments now share the one-field customer search and can start from a customer balance or unpaid sale with the customer preselected. An existing draft for a different customer is preserved until the user chooses to switch.
- Sales history searches invoice number, customer name or date. Reports export their selected period to a UTF-8 CSV (up to 10,000 rows), with the same access rules and spreadsheet-formula protection.
- A manual pg_dump script checks archive readability and records a SHA-256 checksum; it passed against a disposable PostgreSQL container. It does not schedule or store backups offsite and does not prove a full restore.
- Backend verification passed 85 tests before the final focused customer lookup and CSV permission tests; those focused tests also passed. Frontend 49 tests, lint and production build passed. The configured Neon database was not used in these checks.
- Historical invoice editing/revaluation, queued non-sale writes, guarded restore and mobile/PWA acceptance remain open.

## Bootstrap verification — 2026-09-22

- Frontend ESLint, TypeScript/production PWA build and 2 tests passed.
- Maven verify passed, including the business-endpoint access-denial test.
- Docker Compose configuration validation passed.
- Git ignore checks confirmed local secrets, dependencies and generated output are excluded.
- Initial bootstrap could not verify PostgreSQL because Docker was stopped; authentication verification below supersedes this limitation.
- No browser/device visual or installation checks have been performed.

## Authentication verification — 2026-09-22

- PostgreSQL 17 integration tests passed with both Flyway migrations applied.
- Backend: 8 authentication integration scenarios plus a login-throttle unit test passed.
- Frontend: 6 login, language, network-failure, logout, permission and reauthentication tests passed.
- Lint and production build are checked alongside tests.
- Tests provision only disposable test accounts. No persistent development or production admin credentials were created.

## Master-data verification — 2026-09-22

- Backend Maven verify passed: 15 tests, including PostgreSQL migration, duplicate phone, version conflict, payment-default, audit and permission scenarios.
- Frontend lint, 9 tests and production PWA build passed. Draft remount/recovery uses a test IndexedDB implementation.
- This verification did not include a real browser/device visual or PWA installation check.

## Purchase verification — 2026-09-22

- Backend: 25 tests passed, including PostgreSQL V4, concurrent purchases/retries, atomic rollback, partial payments, WAC, balances, snapshots and permissions.
- Frontend: 13 tests passed, including integer-decimal previews and exact-payload retry across a form remount.
- Lint and production PWA build passed. Browser/device visual and installability checks remain pending.

## Supplier-payment verification - 2026-09-22

- Backend Maven verify: 34 tests passed, including 9 payment scenarios against PostgreSQL with V5.
- Frontend: 16 tests, lint and TypeScript/production PWA build passed.
- Production build reports a non-blocking chunk-size warning (main bundle about 507 kB before gzip).
- Browser/device acceptance and development-database migration remain to be performed; tests used isolated databases.

## Sales verification - 2026-09-23

- Backend Maven verify: 45 tests passed, including 11 sales integration scenarios with PostgreSQL and V6.
- Frontend: 22 tests passed, including sales rounding, mixed totals, durable retry, validation recovery and walk-in full payment.
- ESLint, TypeScript and production PWA build passed. The main bundle is approximately 527 kB before gzip and triggers a non-blocking size warning.
- Real-browser visual/device acceptance and applying V6 to the development database remain pending. Integration tests used isolated databases.

## Customer-payment verification - 2026-09-23

- Backend Maven verify: 55 tests passed, including 10 customer-payment PostgreSQL integration scenarios with V7.
- Frontend: 25 tests, ESLint, TypeScript and production PWA build passed.
- Verified oldest/manual allocation, inactive customer debt, walk-in rejection, concurrent overpayment/retry protection, permissions and original-payment preservation.
- The production build retains a non-blocking bundle-size warning (about 542 kB before gzip). Real-browser/device acceptance and development-database migration remain pending.

## Offline-sales verification - 2026-09-23

- Frontend: 37 tests passed, including queue recovery, competing-tab claims, exact retries, account/session isolation, reconnect, correction and offline selector behavior.
- ESLint, TypeScript and production PWA build passed. The existing bundle-size warning remains (about 553 kB before gzip).
- Backend code was unchanged for this milestone; its last full verification remains 55 passing tests.
- Real-browser/device PWA acceptance remains pending. No new server migration or dependency is required; IndexedDB upgrades automatically to version 6.

## Expenses/waste verification - 2026-09-23

- Backend Maven verify: 64 tests passed, including 9 expense/waste PostgreSQL integration scenarios with V8.
- Frontend: 41 tests passed; ESLint, TypeScript and production PWA build passed.
- Existing production bundle-size warning remains (approximately 563 kB before gzip).
- Development database migration and real-browser/device acceptance remain pending. Test databases were isolated. No new dependency or environment variable is required.

## Audit/specification verification - 2026-09-23

- Backend Maven verify passed: 66 tests, including audit filtering, date validation and denied access without `AUDIT_VIEW`, against disposable PostgreSQL 17 Testcontainers databases.
- Frontend: 41 tests, ESLint, TypeScript and production PWA build passed. Audit screen has no dedicated component test or real-browser acceptance yet.
- The production bundle remains above Vite's advisory 500 kB threshold (about 567 kB before gzip).
- The local `.env`/Neon database was not used or modified during these checks. The v6 review records the remaining implementation and acceptance gaps.

## Frontend entry and theme improvements - 2026-09-23

- Workspace now prioritizes New Sale, New Purchase, Expense and Waste, with records below and administration in a disclosure.
- Sale entry offers direct product/container picks, one-tap sale type and weight/money choices, search focus for a new line, and automatic full-payment totals until the user explicitly edits the payment. Purchase and payment/operation forms share clearer fields and mobile save bars.
- The interface and SVG mark use cream, cocoa brown, gold and muted spice/olive colors drawn from the supplied roastery sticker. The PWA browser theme colors match. Existing raster install icons still need a matching export.
- Infrequent pages load on navigation; the initial JavaScript bundle decreased from about 569 kB to 336 kB before gzip. This is a bundle-size comparison, not a measured page-load benchmark.
- Frontend lint, 42 tests and production PWA build passed. Real-browser/mobile visual and task-timing acceptance remain pending.

## Local admin bootstrap - 2026-09-23

- The development starter reads admin bootstrap settings from the ignored root `.env` and does not prompt on each run. The named account is created only if absent; existing credentials are never reset.
- Backend Maven verify passed 68 tests, including provisioning when other users already exist and idempotent repeat bootstrap, using disposable PostgreSQL databases.
- A temporary localhost-only backend startup provisioned/verified the requested admin login against the configured Neon database, then stopped. The credential remains only in the ignored local configuration, not in tracked source.

## Dashboard and record cancellation - 2026-09-23

- The home screen now displays database-backed sales, purchases, expenses, supplier debt and known-cost profit indicators. Container revenue is reported separately because its product cost is unknown. Figures are gated by the user's capabilities.
- Master-data screens expose Archive and Restore for products, suppliers, customers, containers, payment methods and expense categories. These change active status and retain transaction history; protected default records remain protected.
- Sale invoices can be cancelled with a reason, stock reversal and audit record. A paid sale requires confirmation that the customer refund occurred. Cancelled invoices no longer contribute to customer debt or dashboard revenue.
- Purchase invoices can be cancelled with a reason, stock/WAC reversal and audit record only when no later stock movement or cost change depends on the purchase. A paid purchase requires confirmation that the supplier refund was received. Otherwise the API reports that manual revaluation is needed.
- Existing payment records remain visible as historical facts alongside the cancellation/refund record. The application records refund confirmation but does not execute a bank transfer. Invoice editing and historical cost replay remain open work.
- These changes were checked against isolated PostgreSQL Testcontainers databases; the configured Neon database was not migrated as part of this work.
- Backend Maven verify passed 72 tests (including cancellation and dashboard integration cases); frontend lint, 42 tests and production PWA build passed. Cancellation retry receipt changes received a further focused sale/purchase integration run. Browser acceptance remains pending.

## Expense and payment cancellation - 2026-09-23

- Expenses now have a reasoned, audited cancellation record. Cancelled expenses stay in history and are excluded from current dashboard totals; the user confirms the payment was voided or returned.
- Customer and supplier payments can be cancelled by an authorized user after refund confirmation. Allocations remain in the database as history but become inactive; the affected invoices and balances are recalculated atomically. Payments tied to cancelled invoices cannot be reversed independently because that invoice already has a refund record.
- Payment detail and history show cancellation status. The user must still handle the real-world refund; there is no bank integration or cash ledger. The migration is V11 and was verified only on disposable PostgreSQL Testcontainers databases, not the configured Neon database.
- Backend Maven verify passed 76 tests, including reversals and the cancelled-invoice guard. Frontend lint, 42 tests and a production build passed. Real-browser acceptance remains pending.

## Date-filtered accounting reports - 2026-09-23

- The authenticated Reports screen shows paginated sales, purchases, expenses, supplier balances and known-cost profit, with period totals independent of pagination and Arabic/English labels.
- Sales/purchase/expense totals exclude cancelled records. Purchase and supplier paid figures use active allocations after payment reversal. Profit separates revenue without known cost and labels known margin less expenses as an estimate, not exact net profit.
- Supplier purchased/paid figures use the selected period; current debt is explicitly a live all-time balance. Report views enforce the underlying sales, purchases, expenses and cost permissions.
- No new migration or external service is required. CSV/PDF export, historical as-of supplier balances and full cost reconciliation remain open work. The report suite used isolated PostgreSQL Testcontainers databases; the configured Neon database was not accessed.
- Backend Maven verify passed 77 tests, including report totals, cancellation effects, date validation and permission checks. Frontend lint, 42 tests and the production PWA build passed. Browser/device acceptance remains pending.

## Invoice PDFs, quick customers and browser acceptance - 2026-09-23

- Authenticated sales and purchase invoice detail pages can download branded Arabic/English PDFs using saved invoice line names and amounts. Cancelled invoices are marked. PDFs are raster pages, so text is not selectable or searchable.
- Sales users can create a named customer with optional phone directly in New Sale and select it immediately. The sale customer field combines search and selection by name or phone, including cached offline references. Customer creation itself requires a connection.
- Backend PDF and customer authorization tests passed using isolated PostgreSQL. Frontend lint, 45 tests and production build passed after the customer and layout work. The shared-footer change was reverted at the user's request.
- A headless Chrome acceptance run against a disposable local PostgreSQL container completed login, quick customer creation, single-field customer search, partial sale, PDF download with a `%PDF-` header, customer payment and a resulting paid invoice with zero outstanding. This was one desktop browser path, not mobile/PWA installation or accessibility acceptance. The configured Neon production branch was not used for the browser run.

## Neon recovery check - 2026-09-23

- Project `soft-bread-93552394` reports `history_retention_seconds=21600` (six hours). This is a short recovery window, not a durable backup policy.
- A separate `recovery-check-20260923` branch was created without compute, restored from the production branch to a timestamp within that window, reached `ready`, and a temporary read-only compute confirmed customer, sale and purchase tables could be queried. The production branch was not restored or changed.
- The recovery-test branch and its read-only compute were deleted after verification; the production branch remained. Application-managed offsite backups, a longer retention plan and a production restore procedure remain open.
