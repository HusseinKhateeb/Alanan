# Authentication API

All API responses are marked `Cache-Control: no-store`. Protected requests use `Authorization: Bearer <accessToken>`. Tokens expire after 30 minutes.

| Method | Path | Access / behavior |
| --- | --- | --- |
| POST | `/api/auth/login` | Public; JSON username/password; returns accessToken, expiresAt and user, and sets the HttpOnly refresh cookie |
| POST | `/api/auth/refresh` | Refresh cookie plus `X-Alanan-Session: refresh` header; rotates the cookie and returns a new access token and user |
| GET | `/api/auth/me` | Authenticated; returns id, username, displayName, preferredLanguage, permissions |
| PUT | `/api/auth/language` | Authenticated; JSON language (`ar` or `en`); 204 |
| POST | `/api/auth/logout` | Authenticated; revokes all sessions of current user, clears refresh cookie; 204 |
| GET | `/api/users?page=0` | USERS_MANAGE capability; up to 50 safe user summaries, no password hashes |
| GET | `/api/users/{id}` | Admin with USERS_MANAGE; returns current roles and effective permissions |
| PUT | `/api/users/{id}/access` | Admin with USERS_MANAGE; replaces roles and effective permission choices; revokes the user's sessions |
| DELETE | `/api/users/{id}` | Admin with USERS_MANAGE; deactivates and removes the account from the directory while retaining accounting and audit history |
| GET | `/actuator/health` | Public; minimal database/application health |

Errors have timestamp, HTTP status, errorCode, messageKey, fieldErrors and traceId. Codes include INVALID_CREDENTIALS, LOGIN_RATE_LIMITED, UNAUTHENTICATED, FORBIDDEN, VALIDATION_FAILED and SERVER_ERROR. User existence/inactive status is not disclosed through different login responses. Passwords and tokens must never be logged by API clients.

OpenAPI generation remains pending. This document describes the implemented endpoints only.
