# Alanan · محمص العنان

Accounting and business management for Al Anan Roastery. This first milestone is a runnable project foundation, not a finished accounting application. See [implementation status](docs/implementation-status.md) and [architecture decisions](docs/decisions/0001-project-foundation.md).

## Repository

- `frontend/`: React, TypeScript, Vite, PWA, TanStack Query and Arabic/English localization.
- `backend/`: Java 21, Spring Boot, Spring Security, PostgreSQL and Flyway.
- `docs/specification/Alanan-v6.docx`: supplied product specification.
- `infrastructure/`, `scripts/`: deployment and operational ownership.

## Frontend preview

Install Node 22.12+ (22 LTS recommended), then:

```powershell
cd frontend
npm ci
npm run dev
```

Open the URL Vite prints (normally http://localhost:5173). The shell works without the backend and accurately shows it as unavailable. Switch العربية / English in the header. No accounting data is collected yet.

```powershell
npm run lint
npm test
npm run build
npm run preview
```

The service worker is generated for production builds, not the normal dev server. Preview serves the static build; a same-origin reverse proxy is needed for backend reachability in production. Icons are initial monogram placeholders. Device installation/offline acceptance tests remain pending.

## Database and backend

Requirements: JDK 21, Maven 3.9+, Docker Desktop with its Linux engine running.

From the repository root:

```powershell
Copy-Item .env.example .env
# Edit .env and replace DB_PASSWORD with a random local password.
docker compose up -d postgres
```

In the backend terminal, export configuration using your IDE or shell. Spring does **not** automatically read `.env` files. Use the same database values as the root `.env`:

```powershell
cd backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' # adjust for your JDK
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5432'
$env:DB_NAME = 'alanan'
$env:DB_USERNAME = 'alanan_app'
# Set DB_PASSWORD in your IDE or terminal environment without committing it.
$env:SPRING_PROFILES_ACTIVE = 'dev'
mvn spring-boot:run
```

`GET http://localhost:8080/actuator/health` returns minimal backend/database health. Flyway initializes identity/capability tables and Cash/Bank/Other payment methods. No user credentials are seeded. Other paths are denied until the authentication milestone.

```powershell
mvn --batch-mode --no-transfer-progress verify
```

The current Java test verifies business endpoints remain inaccessible. It does not require a database and does not validate migrations. Database integration tests are a following milestone.

If Maven was downloaded by the initial setup, the ignored local copy is available at `.tools/apache-maven-3.9.11/bin/mvn.cmd`; it is a convenience, not a committed dependency. In CI, Maven is supplied by the runner.

## Configuration and data safety

Only `.env.example` templates belong in Git. All `VITE_*` values are public. Frontend API calls will use `VITE_API_BASE_URL` (default `/api`) as business APIs are introduced. Health currently uses the same-origin `/actuator/health` path. The Vite dev proxy forwards both to port 8080.

Production uses explicit database environment values and the `prod` Spring profile. Secrets must be injected by the deployment environment. Local Compose credentials must not be reused in production. There is no production deployment or backup workflow yet.

CI builds/tests both applications and lints the frontend. No commits or deployments are automatically made by local setup.
