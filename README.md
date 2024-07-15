# NOVA Bank Core

A secure, API‑driven Core Banking System built with Spring Boot 3, PostgreSQL, JWT security, and OpenAPI. It provides user authentication, account management, transaction processing, audit/fraud logging, and is production‑ready with Docker.

- Tech stack: Java 17, Spring Boot, Spring Security + JWT, Spring Data JPA (Hibernate), PostgreSQL, OpenAPI/Swagger, Actuator, Maven, JUnit 5.
- Status: Actively developed. Containerized with Docker. Swagger UI available.

---

## Contents
- Overview
- Features
- Architecture
- API Overview
- Quick Start (Docker)
- Local Development
- Configuration
- Default Admin User
- Usage Examples (curl)
- Testing
- Build & Packaging
- Deployment
- Observability
- Security & Production Checklist
- License

---

## Overview
NOVA Bank Core is a foundational, API‑first backend for digital banking. It implements core banking primitives and supporting compliance features:
- User registration/login with JWTs and role‑based access (ADMIN, CUSTOMER, AUDITOR)
- Accounts per user, real‑time balance updates
- Deposits, withdrawals, and transfers with immutable transaction records
- Audit and fraud logs for monitoring and compliance
- OpenAPI documentation and health/metrics via Actuator

## Features
- Authentication & RBAC: Spring Security + JWT, roles: ADMIN, CUSTOMER, AUDITOR
- Account lifecycle: create, list, activate/deactivate (activate flag), unique account numbers
- Transactions: deposit, withdraw, transfer, validation (ownership, sufficient funds, positive amounts)
- History: per‑user transaction history with references and timestamps
- Audit: every sensitive action recorded in AuditLog
- Fraud: large‑transaction detection with FraudLog entries
- API Docs: Swagger UI (OpenAPI 3)
- Ready to run: Dockerfile and docker‑compose included

## Architecture
Layered architecture:
- Controller (REST API)
- Service (business logic, validation, auditing/fraud hooks)
- Repository (Spring Data JPA)
- Entity (JPA models with auditing)

Key packages: config, controller, dto, exception, model, repository, security, service.

Database: PostgreSQL with JPA/Hibernate. Entities extend BaseEntity with createdAt/updatedAt via JPA auditing.

---

## API Overview
Swagger UI: http://localhost:8080/swagger-ui.html
OpenAPI docs (JSON): http://localhost:8080/v3/api-docs

Highlighted endpoints:
- POST /api/auth/register
- POST /api/auth/login
- GET  /api/accounts
- POST /api/accounts         (create account)
- POST /api/accounts/deposit
- POST /api/accounts/withdraw
- GET  /api/transactions/my
- POST /api/transactions/transfer
- GET  /api/admin/audit?page=0&size=20  (ADMIN or AUDITOR)
- GET  /api/admin/fraud?page=0&size=20  (ADMIN or AUDITOR)

Authentication: attach the JWT from /api/auth/login to Authorization header as: Bearer <token>

---

### Using Swagger UI with JWT
1) Open Swagger UI at http://localhost:8080/swagger-ui.html
2) Click the Authorize button (top-right).
3) In the "bearerAuth" input, paste your token prefixed with "Bearer ":
   Bearer eyJhbGciOiJIUzI1NiIs...
4) Click Authorize, then Close. Protected endpoints will include the token automatically.

Notes:
- Public endpoints: POST /api/auth/register and POST /api/auth/login
- All other endpoints require a valid JWT unless stated otherwise

Error model:
- Errors are returned in a standard envelope: { code, message, details, timestamp }

---

### Transaction History Filters, Pagination, and Sorting
Endpoint: GET /api/transactions/my supports optional query params:
- startDate: ISO date (YYYY-MM-DD), inclusive
- endDate: ISO date (YYYY-MM-DD), inclusive
- minAmount: decimal amount lower bound
- maxAmount: decimal amount upper bound
- page: zero-based page index (optional; defaults to returning full list when page/size omitted)
- size: page size (optional; used only when page or size is provided)
- sort: sorting spec in the form field,direction where field is one of [occurredAt, amount, type] and direction is asc or desc (default asc). Example: occurredAt,desc

Examples:

1) All my transactions in October 2025

   curl -s -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8080/api/transactions/my?startDate=2025-10-01&endDate=2025-10-31"

2) Transactions above 100.00

   curl -s -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8080/api/transactions/my?minAmount=100.00"

3) First page of size 10, most recent first

   curl -s -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8080/api/transactions/my?page=0&size=10&sort=occurredAt,desc"

Notes:
- If you omit page/size, the endpoint returns the full list (backward compatible behavior).
- When both minAmount and maxAmount are provided, minAmount must be <= maxAmount.
- Dates must be in YYYY-MM-DD format; invalid formats return a 400 error.

---

## Quick Start (Docker)
Prerequisites: Docker and Docker Compose

1) Build and run

   docker compose up --build

2) After the db is healthy, open Swagger UI:

   http://localhost:8080/swagger-ui.html

3) Stop

   docker compose down

Data is persisted in a local docker volume (db_data by default).

---

## Local Development
Prerequisites: Java 17+, Maven, and a running PostgreSQL instance.

1) Configure src/main/resources/application.yml or set env vars (see Configuration).
2) Run the app:

   mvn spring-boot:run

The app listens on PORT 8080 by default.

---

## Configuration
Application reads configuration from application.yml and environment variables.

Core database & JWT:
- DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
- JWT_SECRET (base64 encoded), JWT_EXPIRATION_MS (default 86400000 ms)

Admin bootstrap (CommandLineRunner):
- app.bootstrap.admin.username (default: admin)
- app.bootstrap.admin.email    (default: admin@nova.local)
- app.bootstrap.admin.password (default: admin12345)

Historical data seeding (CommandLineRunner):
- app.bootstrap.historical.enabled    (default: false)
- app.bootstrap.historical.months     (default: 3)
- app.bootstrap.historical.users      (default: 3)
- app.bootstrap.historical.tx-per-week (default: 6)

When enabled, the application seeds realistic, interrelated data spanning the past N months:
- Creates up to the configured number of customer users (e.g., alice, bob, charlie, ...)
- Creates 2 accounts per user
- Generates weekly deposits, withdrawals, and transfers between accounts (including cross-user transfers)
- Writes corresponding AuditLog entries and FraudLog entries for large transactions
- Backdates all timestamps (occurredAt, createdAt, updatedAt) into the historical window

Idempotency: A marker AuditLog entry (actor=system, action=SEED_HISTORICAL) prevents reseeding for the same configuration.

To enable locally, set in src/main/resources/application.yml or via env:

app.bootstrap.historical.enabled=true
app.bootstrap.historical.months=3
app.bootstrap.historical.users=3
app.bootstrap.historical.tx-per-week=6

Or environment variables (Spring relaxed binding):

APP_BOOTSTRAP_HISTORICAL_ENABLED=true
APP_BOOTSTRAP_HISTORICAL_MONTHS=3
APP_BOOTSTRAP_HISTORICAL_USERS=3
APP_BOOTSTRAP_HISTORICAL_TX_PER_WEEK=6

Actuator and Swagger:
- Swagger UI path: /swagger-ui.html
- Actuator endpoints exposed: health, info, metrics, loggers, env, threaddump, heapdump

Docker Compose overrides DB_* and JWT_* for the app service.

---

## Default Admin User
On startup, if a user with the configured admin username does not exist, an ADMIN user is created automatically (see BootstrapConfig).
- Default credentials (local/dev): admin / admin12345
- Change these immediately in any shared or production environment using env vars listed above.

---

## Usage Examples (curl)
Register

curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"StrongPass123"}'

Login (get JWT)

curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"StrongPass123"}'

Export token

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"StrongPass123"}' | jq -r .token)

Create account

curl -s -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN"

List accounts

curl -s -X GET http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN"

Deposit

curl -s -X POST http://localhost:8080/api/accounts/deposit \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"accountNumber":"<ACC_NO>","amount":100.50,"note":"initial"}'

Transfer

curl -s -X POST http://localhost:8080/api/transactions/transfer \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"fromAccount":"<FROM>","toAccount":"<TO>","amount":25.00,"note":"split"}'

My transactions

curl -s -X GET http://localhost:8080/api/transactions/my \
  -H "Authorization: Bearer $TOKEN"

Admin: list audit logs (requires ADMIN or AUDITOR)

curl -s -X GET 'http://localhost:8080/api/admin/audit?page=0&size=20' \
  -H "Authorization: Bearer $TOKEN"

---

### Full flow script (automated)
A convenience script is provided to test the whole API flow using curl in one go.

Prerequisites:
- Server running at http://localhost:8080 (or set BASE_URL)
- jq and curl installed

Run:

bash scripts/curl-full-flow.sh

Optional overrides (examples):

BASE_URL=http://localhost:8080 \
CUSTOMER_USERNAME=alice \
CUSTOMER_EMAIL=alice@example.com \
CUSTOMER_PASSWORD=StrongPass123 \
ADMIN_USERNAME=admin \
ADMIN_PASSWORD=admin12345 \
bash scripts/curl-full-flow.sh

## Testing
Unit and integration tests use JUnit 5 and in‑memory H2 where applicable.

Run tests

mvn -q test

You can also run specific test classes from your IDE or via Maven’s -Dtest flag.

---

## Build & Packaging
Build JAR (skipping tests):

mvn -q -DskipTests package

Run the packaged app:

java -jar target/nova-bank-core-0.0.1-SNAPSHOT.jar

Build container image (local):

docker build -t nova-bank-core:local .

docker run --rm -p 8080:8080 \
  -e DB_HOST=localhost -e DB_PORT=5432 -e DB_NAME=nova_bank \
  -e DB_USER=postgres -e DB_PASSWORD=postgres \
  -e JWT_SECRET=ZmFrZXNlY3JldGZvcmtleWJhc2U2NA== \
  nova-bank-core:local

---

## Deployment
- Use docker-compose.yml as a baseline for on‑prem or cloud container platforms.
- Externalize all secrets (DB_PASSWORD, JWT_SECRET) via your orchestrator or a secrets manager.
- Configure persistent storage for PostgreSQL.
- Place the app behind an HTTPS ingress/proxy (NGINX, Traefik, ALB/ELB, etc.).
- Configure logging aggregation and metrics scraping in your platform.

---

## Observability
- Spring Boot Actuator enabled (health, info, metrics, etc.).
- Consider Prometheus/Grafana for metrics and alerting.
- Centralize logs (ELK/EFK, or cloud‑native logging).

---

## Security & Production Checklist
- Change default admin credentials via env vars before exposing publicly.
- Use a strong, random base64‑encoded JWT secret (>=256‑bit).
- Enforce HTTPS end‑to‑end; terminate TLS at a trusted ingress.
- Rotate credentials and tokens regularly; store secrets securely.
- Restrict database network access; use separate DB users per environment.
- Enable structured logging and monitor audit/fraud logs.
- Apply database migrations and backups; set proper retention.

---

## License
MIT License