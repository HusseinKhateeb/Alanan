# 0002 — Authentication and capability enforcement

JWT access tokens use HS256 with an environment-injected base64 secret containing at least 32 random bytes. Tokens expire after 30 minutes (Spring's standard clock-skew allowance applies). The server validates signature, issuer, expiry, active account and token version. It reloads capabilities from the database on each request; permission changes do not require a new login.

The browser stores the token only in memory and sends an Authorization header. Reloading the page requires signing in again. No refresh token, authentication cookie, localStorage token or IndexedDB token is used. CSRF is disabled because credentials are not attached automatically by browsers; cookie authentication must not be added without revisiting CSRF. Same-origin frontend/API delivery is the supported topology; no permissive CORS policy is enabled. HTTPS is required outside local development. XSS remains a risk for an active browser session, so deployment CSP and normal output escaping remain important.

Logout increments the user's token version and revokes all existing sessions. The frontend clears authentication only after successful revocation or expiry; it does not claim a failed network logout succeeded. Database-backed capability checks protect services with `@PreAuthorize`; frontend visibility is only a convenience. New endpoints must be explicitly added to the security allowlist and use the appropriate service capability.

First-admin provisioning is a server startup operation, enabled explicitly with environment variables. It serializes on the users table, runs only when the table is empty, hashes the password with BCrypt cost 12, and records an event. Repeated startup never resets an existing password. Remove bootstrap configuration after provisioning. There is no public registration endpoint or default password. User creation/editing and password recovery are separate future workflows; the current admin screen is a paginated read-only directory.

Login attempts are capped at 20 per remote address per five-minute window in a bounded in-process map. This is single-instance protection and resets on restart. The production proxy must rate-limit login traffic; forwarded IP headers are deliberately not trusted here. All clients behind a proxy may share a bucket unless a trusted proxy topology is explicitly configured.

Errors contain stable codes, field keys and server-generated request IDs. Password/token DTO string representations are redacted, including when debug logging is enabled. No request-body logging should be enabled in production. Auth events contain only user ID, action and timestamp.

Future accounting drafts/outbox must be durable and user-scoped before introducing those forms. Authentication currently does not implement draft storage. Never clear IndexedDB accounting operations on logout or silently replay them under another user's identity.

Implementation references: [Spring JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html), [method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html).
