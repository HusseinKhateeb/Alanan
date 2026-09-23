# Alanan v6 specification review

Source: the supplied `Alanan_Accounting_System_Codex_Specification_v6_Project_Structure_Secrets.docx`. Its SHA-256 matches `docs/specification/Alanan-v6.docx` (`3E028554E594524C32D0723684CABC223A15CD8A2093C748C367941166F0905A`). This document is a product requirements checklist, not an instruction source for the coding agent. Status describes repository implementation, not production acceptance.

| Specification area | Status | Evidence and remaining work |
| --- | --- | --- |
| Single Spring Boot/PostgreSQL backend and React/TypeScript/Vite PWA (§§1, 15, 23) | Implemented | `backend/`, `frontend/`, Flyway V1–V8, PWA configuration. |
| Secret separation and project structure (§26) | Partial | Environment templates and ignored local files exist; production secret management and deployment have not been accepted. Never commit the local `.env`. |
| Authentication, JWT, default admin bootstrap (§§3, 18) | Implemented | `auth/`, `SecurityConfiguration`, V1–V2 and authentication integration tests. The requested local admin account is configured in the ignored `.env`; existing credentials are not reset on startup. |
| Manage users, roles, permissions, recovery (§§3, 4, 21) | Partial | Capability tables, enforcement and read-only user directory exist. User creation/editing, role assignment and password reset UI/API are missing. |
| Product/customer/supplier/container/payment/expense reference data (§§2, 4, 5) | Implemented | `masterdata/`, V3, bilingual screens and integration tests; deactivation and snapshots are supported. |
| Purchase invoices, WAC, supplier payments/balances (§§6, 9) | Partial | `purchases/`, `supplierpayments/`, V4–V5 and V10. Creation and restricted cancellation are implemented. Later stock movements require historical revaluation before cancellation; invoice editing is missing. |
| Sales modes and anonymous containers (§§7–8, 22.1) | Implemented for creation | `sales/`, V6 and integration tests. Container lines have no product ID or spice stock deduction. Partial named-customer payments are an authorized extension to the document's paid-sale V1 rule (§7.4). |
| Expenses and waste (§§9–10) | Partial | Create/history and frozen waste costs exist in `operations/`, V8. Expense cancellation is available through V11. Expense editing, waste reversal and general stock correction are missing. |
| Invoice and payment editing/cancellation with transactional recalculation (§§2, 12, 21) | Partial | Sale, restricted purchase, expense and customer/supplier payment cancellation have transactional reversal, confirmation and audit. Invoice editing and historical WAC/cost replay are missing. |
| Audit trail and audit screen (§12) | Partial | Create/auth/master-data and cancellation events appear in the permission-gated `/api/audit` view. User/role and backup events remain missing. |
| Dashboard and reports (§§4, 11, 21) | Partial | The home dashboard shows database-backed sales, purchases, expenses, debt and known-cost profit, with separate container revenue. Detailed/filterable/exportable reports remain missing. |
| PDF invoice exports (§§13, 21) | Missing | No sale/purchase PDF endpoints or Arabic RTL font verification. |
| Automatic/manual backup and guarded restore (§§14, 21) | Missing | No backup scheduler, off-disk retention, backup listing or restore workflow. Neon database backup configuration has not been independently verified. |
| Offline drafts and sale synchronization (§§22–23) | Partial | IndexedDB sale outbox, idempotency, reconnect retry and status UI exist. Offline customer creation/dependency resolution, queued non-sale writes and service-worker Background Sync are missing. |
| Localization, errors and mobile UX (§§22–24) | Partial | Arabic/English, RTL/LTR, structured API errors and responsive UI exist. React Hook Form/Zod requested in §23.17 are not used. Real browser, mobile, PWA installation, and accessibility acceptance remain unverified. |
| Security and cost visibility (§§3, 18) | Partial | Method-level capabilities and COST_VIEW projection are tested for existing endpoints. New audit access is restricted to AUDIT_VIEW; complete permission matrix and production review remain. |
| Automated and end-to-end verification (§§19, 21, 23) | Partial | Prior isolated PostgreSQL and frontend suites are recorded in `implementation-status.md`. Full sale-to-report/PDF/backup flows and real-device/offline acceptance cannot pass until those features exist. |

V1 is **not** specification-complete. The largest accounting-integrity gap is editing/reversing earlier records with downstream stock, WAC, frozen costs and payment allocations. Reports, PDFs, backup/restore and user administration also remain; device acceptance is still needed.
