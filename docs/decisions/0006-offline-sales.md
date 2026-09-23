# 0006: Offline sales queue and reference cache

Completed sales enter a user-scoped IndexedDB outbox before any POST. The same transaction preserves the frozen submitted draft. Drafts are not submitted merely because they exist: the user explicitly chooses Queue sale. Once queued, a sale can synchronize automatically while the authenticated app is open.

The outbox tracks PENDING, SYNCING, SYNCED and NEEDS_ATTENTION, attempt count, next retry time, lease expiry, error and receipt. An IndexedDB read/write transaction claims one entry with a 60-second lease. Multiple tabs coordinate through the same database. Abandoned claims become eligible after expiry. Backend user/request UUID idempotency remains the final duplicate-prevention boundary, including an accepted request whose response was lost.

Requests use the token only when its in-memory owner matches the queue owner. Session loss pauses transmission; another account cannot send the previous account's queue. An old request's 401 cannot invalidate a newer session. Tokens are never persisted. Reopening the application requires online sign-in; offline authentication is not implemented.

Synchronization runs immediately on sign-in/reconnect and every ten seconds while open. Transient failures retry with exponential delay, capped at five minutes. Authentication failures wait for sign-in. Validation/permission/conflict failures remain visible as NEEDS_ATTENTION. Explicit retry sends the same payload. Only definitively rejected 400/403 entries can reopen for correction using the same key; ambiguous conflicts cannot be converted into a new sale. Existing unsaved drafts are protected from replacement.

Active products/prices, customers, container types and payment methods are cached per user on sign-in, reconnect and every five minutes, with manual refresh available. Each catalog is replaced only after all pages load successfully (maximum 200 pages per catalog). Failed refresh preserves its last complete snapshot. Selectors fall back to cached records only for network/server failures, not authentication/permission failures. The UI displays cache age. Backend validation remains authoritative; old default prices do not overwrite the facts entered for a sale.

The service worker caches the app shell, not tokens or authenticated API responses. No service-worker background sync is added: the app must remain open and signed in. Pending work survives refresh/restart in IndexedDB, but clearing browser storage removes it. This milestone handles existing references and sales only; offline customer creation/dependency resolution and other accounting operations remain separate work.

Tests exercise lease coordination, interrupted requests, database reopen, user isolation, expiration and session races, reconnect, rejected-sale correction, conflicts, new-draft preservation and cache refresh failure. These use fake IndexedDB and mocked network responses; real-browser/device PWA acceptance is still required.
