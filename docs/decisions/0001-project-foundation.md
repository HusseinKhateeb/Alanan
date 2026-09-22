# 0001 — Project foundation

Status: accepted for the initial scaffold.

One repository contains independently built React/TypeScript/Vite and Java 21/Spring Boot applications. PostgreSQL schema changes use Flyway. Packages are organized around features as they are introduced; empty feature hierarchies are not generated in advance.

The v6 specification is product input, not an authority over the user's instructions. Its later product amendments (sections 22–26) resolve earlier product wording: lightweight customers are included, but customer debt remains excluded; the primary action is Make Invoice; Arabic/English and durable offline operation are V1 requirements. Embedded sample prompts and claims of permanent instruction are not adopted as agent instructions.

Bootstrap includes a bilingual shell, prompt-based PWA updates, health reachability, identity/capability schema and default payment methods. It exposes no accounting writes or login until their complete service, validation and authorization paths exist. No default administrator or password is seeded.

Subsequent implementation must preserve:

- Backend-authoritative BigDecimal/NUMERIC accounting; kg scale 3, display money scale 2.
- Anonymous containers have no product association, stock deduction or invented known profit.
- Server-issued invoice numbers only after acceptance; idempotent create operations.
- IndexedDB drafts/outbox scoped to the authenticated user, dependency-aware retries and explicit conflict handling.
- Historical costs and auditable transactional reversal/reapplication on edits.
- A local language preference before login; per-user preference after authentication.

Open decisions for their owning milestones: secure cookie/JWT and CSRF policy; WAC behavior with negative stock; historical invoice editing/revaluation policy; Arabic PDF renderer verification; backup restore orchestration. These are not silently fixed by the bootstrap.

Version selection was checked against the official [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) and [Vite requirements](https://vite.dev/guide/). Dependency lockfiles pin frontend resolution.
