# Infrastructure

The root `docker-compose.yml` provides a local PostgreSQL service bound to loopback only. Its bootstrap user is for local development; production must use a separately provisioned, least-privileged application role and a migration role.

Production packaging/reverse-proxy configuration will live here. Serve static frontend assets and forward `/api` and `/actuator/health` to the backend on the same origin. The frontend expects that health path. Use HTTPS; never expose PostgreSQL publicly. SPA fallback must not rewrite API requests to index.html. Hashed assets can be cached; index.html and service-worker files require update-safe cache headers.

Production deployment and backup/restore are not implemented in this milestone.
