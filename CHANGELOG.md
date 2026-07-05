# Changelog

All notable changes to the NOVA Bank Core API are documented here. The API itself is versioned
via the URI path (`/api/v1/...`); this file tracks what changed release-to-release underneath
that contract, per the code review's "no versioned changelog" finding.

This project does not yet tag formal releases (no `git tag`/GitHub Releases workflow exists), so
entries below are grouped by remediation pass rather than a semantic version number. If/when
this project starts tagging releases, entries should switch to `## [x.y.z] - YYYY-MM-DD` headers
following [Keep a Changelog](https://keepachangelog.com/) conventions.

## [Unreleased] — Security & reliability remediation pass

### Security

- **Critical:** Removed the hardcoded default admin password fallback (`admin12345`).
  `BootstrapConfig` now refuses to start the application in any non-`dev`/non-`local` Spring
  profile (including no profile at all) unless `APP_BOOTSTRAP_ADMIN_PASSWORD` is set explicitly;
  under `dev`/`local` it generates and logs a fresh random password instead of using a fixed
  default. `render.yaml` now auto-provisions this value.
- Removed a hardcoded JWT secret and database password from `docker-compose.yml`; both are now
  required environment variables sourced from a local, gitignored `.env` file, with a fail-fast
  error if unset.
- Added refresh-token rotation and revocation (`RefreshTokenService`, `refresh_tokens` table).
  Access tokens are now short-lived (15 min, down from 24h); a longer-lived (7 day), individually
  revocable refresh token replaces the lost convenience. Reuse of an already-rotated refresh
  token revokes every active session for that user.
- Admin audit-log and fraud-log listing endpoints now return dedicated DTOs
  (`AuditLogResponse`/`FraudLogResponse`) instead of raw JPA entities.
- Every admin read of account/audit/fraud listings is now itself recorded as an audit event
  (`ADMIN_ACCOUNT_LIST_READ` / `ADMIN_AUDIT_LOG_READ` / `ADMIN_FRAUD_LOG_READ`).

### API

- **Breaking:** all endpoints moved from `/api/*` to `/api/v1/*`.
- Added `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`.
- Added filtering (`actor`/`action`, `username`/`eventType`) and sorting (`sort=field,dir`) to
  the admin audit/fraud log listing endpoints, and sorting to the admin accounts listing endpoint.
- `AuthResponse` gained a `refreshToken` field alongside the existing `token` field.

### Reliability

- Introduced a transactional outbox (`WebhookOutboxEvent`, `WebhookOutboxService`,
  `WebhookDispatcher`) for webhook notifications. `TransactionCommandService`/`AccountService`
  no longer make a synchronous external HTTP call from inside a database transaction — a slow or
  hung webhook target can no longer extend a live transfer's lock hold time.

### Architecture

- Split `TransactionService` into `TransactionCommandService` (writes) and
  `TransactionQueryService` (reads).
- Introduced `AdminService`, removing `AdminController`'s direct dependency on
  `AccountRepository`/`AuditLogRepository`/`FraudLogRepository`.
- Added `ArchitectureFitnessTests` (ArchUnit) to mechanically enforce the layering rules the
  README already claimed.

### Observability

- Added a real metrics registry (`micrometer-registry-prometheus`, `/actuator/metrics`,
  `/actuator/prometheus`).
- Added structured JSON logging (`JsonLogLayout`, no new dependency) under `staging`/`prod`
  profiles, with request-scoped correlation-ID propagation (`CorrelationIdFilter`) in every
  profile.
- Added distributed tracing instrumentation (Micrometer Tracing + Brave + Zipkin reporter) —
  **not verified against a live collector**.

### DevOps

- Added `application-dev.yml` / `application-staging.yml` / `application-prod.yml` environment
  profiles.
- Added a real containerized-PostgreSQL integration test (`FlywayMigrationPostgresIT`,
  Testcontainers) exercising the Flyway migration chain end-to-end; wired via a new
  `maven-failsafe-plugin` execution so `-DskipITs` is a real, functioning flag.
- Added a CD pipeline (`.github/workflows/cd.yml`) gating deployment on CI success, with a
  post-deploy health check and automatic rollback — **unverified against a live Render
  account/service**. `render.yaml`'s `autoDeploy` is now `false` accordingly.
- Split CI into a fast unit/component-test job and a separate Testcontainers-backed integration
  test job.

## Baseline (prior remediation pass, predates this changelog)

- JWT authentication, BCrypt password hashing, role-based access control (`ADMIN`/`CUSTOMER`/`AUDITOR`).
- Transfer idempotency (SHA-256 request hashing), optimistic locking (`@Version`) for concurrent
  balance updates.
- Flyway-managed schema (`ddl-auto: validate`), replacing Hibernate `ddl-auto: update`.
- `@EntityGraph`-based N+1 query prevention on admin account listing.
- Database-level filtering/sorting/pagination for transaction listing via JPA `Specification`.
- In-process rate limiting (Bucket4j) on `/api/auth/login`/`register`.
- JaCoCo coverage reporting; Mockito-based unit tests for `FraudService`/`AuditService`/`JwtService`/`WebhookService`.
