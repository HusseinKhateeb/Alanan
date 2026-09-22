# Implementation status

## Started: repository foundation

- Separate frontend/backend, environment templates, ignore rules and editor conventions.
- Spring Boot and Flyway foundation with users, roles, capabilities and payment methods.
- Business endpoints deny access until authentication is implemented.
- Responsive bilingual shell; Arabic RTL/English LTR; actual backend health check.
- PWA shell caching, placeholder monogram icons and update prompt.
- Frontend language/connection tests; backend access-denial test; CI build checks.

This is not a functional accounting release. It has no login, invoices, master-data CRUD, accounting calculations, synchronization, reports, PDFs or backups yet. Dexie is installed for the planned offline work; no draft/outbox behavior is claimed.

## Next milestones

1. Backend foundation: administrator provisioning, login, JWT/session strategy, capability enforcement, structured localized errors, request IDs and OpenAPI.
2. Master data: products, customers/normalized phone matching, suppliers, container types, payment methods and expense categories.
3. Purchases and inventory: server numbering, transactional WAC/stock movements, partial payment, audit and concurrency tests.
4. Supplier payments: bounded allocation and oldest-first settlement.
5. Sales invoices: mixed items, explicit money/weight choice, durable local drafts, user-scoped outbox, backend idempotency and dependency-aware synchronization.
6. Expenses/waste, safe editing, reports, Arabic PDFs, backup/restore and production QA.

Database integration checks require a running PostgreSQL instance. PWA installation and offline behavior require browser/device acceptance tests before release.

## Bootstrap verification — 2026-09-22

- Frontend ESLint, TypeScript/production PWA build and 2 tests passed.
- Maven verify passed, including the business-endpoint access-denial test.
- Docker Compose configuration validation passed.
- Git ignore checks confirmed local secrets, dependencies and generated output are excluded.
- PostgreSQL startup/Flyway execution were not verified: the local Docker engine was stopped.
- No browser/device visual or installation checks have been performed.
