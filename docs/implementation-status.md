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

This is not a functional accounting release. It has no invoices, master-data CRUD, accounting calculations, synchronization, reports, PDFs or backups yet. User creation/editing/password recovery UI is not included; the user directory is read-only. Dexie is installed for the planned offline work; no draft/outbox behavior is claimed.

## Next milestones

1. Remaining foundation: generated OpenAPI and user administration/password recovery workflows.
2. Master data: products, customers/normalized phone matching, suppliers, container types, payment methods and expense categories.
3. Purchases and inventory: server numbering, transactional WAC/stock movements, partial payment, audit and concurrency tests.
4. Supplier payments: bounded allocation and oldest-first settlement.
5. Sales invoices: mixed items, explicit money/weight choice, durable local drafts, user-scoped outbox, backend idempotency and dependency-aware synchronization.
6. Expenses/waste, safe editing, reports, Arabic PDFs, backup/restore and production QA.

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
