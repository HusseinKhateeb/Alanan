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
- Memory-only sessions; reopen/session expiry requires online sign-in. No offline customer creation or service-worker background submission.

## Implemented: expenses and waste

- Expense category/payment snapshots and atomic audit records, with inclusive date/category history filters.
- Waste product/quantity/reason, stock deductions, frozen costs, negative-stock warnings and audit records.
- Capability restrictions, hidden cost fields, exact-request retries and durable bilingual forms.
- Waste history filters by dates/product; product units locked after waste history.

This is not a completed V1 release. Automatic synchronization for non-sales workflows, reports, PDFs and backups are not implemented. Historical invoice editing/cancellation remains pending. User creation/editing/password recovery UI remains outside the implemented read-only user directory.

## Next milestones

1. Remaining foundation: generated OpenAPI and user administration/password recovery workflows.
2. Invoice integration of master data: inline customer/supplier creation and reference caching for offline entry.
3. Inventory valuation extensions: negative-stock reconciliation and safe historical invoice edits.
4. Offline extensions: customer creation and dependency-aware synchronization, other accounting operations, and real-device acceptance.
5. Safe editing/reversals, sales/expenses/profit reports, Arabic PDFs, backup/restore and production QA.

Database integration checks use isolated PostgreSQL Testcontainers. PWA installation and offline behavior require browser/device acceptance tests before release.

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
