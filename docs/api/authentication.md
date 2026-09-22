# Authentication API

All API responses are marked `Cache-Control: no-store`. Protected requests use `Authorization: Bearer <accessToken>`. Tokens expire after 30 minutes.

| Method | Path | Access / behavior |
| --- | --- | --- |
| POST | `/api/auth/login` | Public; JSON username/password; returns accessToken, expiresAt and user |
| GET | `/api/auth/me` | Authenticated; returns id, username, displayName, preferredLanguage, permissions |
| PUT | `/api/auth/language` | Authenticated; JSON language (`ar` or `en`); 204 |
| POST | `/api/auth/logout` | Authenticated; revokes all sessions of current user; 204 |
| GET | `/api/users?page=0` | USERS_MANAGE capability; up to 50 safe user summaries, no password hashes |
| GET | `/actuator/health` | Public; minimal database/application health |

Errors have timestamp, HTTP status, errorCode, messageKey, fieldErrors and traceId. Codes include INVALID_CREDENTIALS, LOGIN_RATE_LIMITED, UNAUTHENTICATED, FORBIDDEN, VALIDATION_FAILED and SERVER_ERROR. User existence/inactive status is not disclosed through different login responses. Passwords and tokens must never be logged by API clients.

OpenAPI generation remains pending. This document describes the implemented endpoints only.
