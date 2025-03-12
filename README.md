# NOVA Bank Core

API-first digital banking backend built with Spring Boot 3, PostgreSQL, JWT, and OpenAPI.

This project is designed as a portfolio-ready core banking MVP: secure auth, account lifecycle, transaction engine, operational controls, analytics, and auditability.

## Why this project stands out

- Secure API with role-based access (`ADMIN`, `CUSTOMER`, `AUDITOR`) and JWT authentication.
- Core money flows: deposit, withdraw, transfer, history, and balance management.
- Product-grade extras: transfer idempotency, CSV statements, account freeze controls, webhook alerts.
- Compliance visibility: audit and fraud endpoints for oversight roles.
- Fully documented API via Swagger/OpenAPI and covered by automated tests.

## Tech Stack

- Java 17
- Spring Boot 3.3
- Spring Security + JWT
- Spring Data JPA (Hibernate)
- PostgreSQL
- OpenAPI/Swagger (`springdoc`)
- Maven + JUnit 5 + MockMvc
- Docker + Docker Compose

## Architecture Snapshot

The system follows a clean layered architecture:

- `controller`: REST API contracts
- `service`: business rules and transaction logic
- `repository`: persistence access
- `model` + `dto`: domain and request/response schemas
- `config` + `security`: auth, OpenAPI, bootstrap, observability

## Main API Capabilities

### Authentication

- `POST /api/auth/register`
- `POST /api/auth/login`

### Accounts

- `GET /api/accounts`
- `POST /api/accounts`
- `POST /api/accounts/deposit`
- `POST /api/accounts/withdraw`

### Transactions

- `GET /api/transactions/my`
- `GET /api/transactions/summary`
- `GET /api/transactions/statement` (CSV export)
- `POST /api/transactions/transfer`

`POST /api/transactions/transfer` supports optional header:

- `Idempotency-Key: <unique-key>`

This prevents duplicate transfers during safe retries.

### Admin and Oversight

- `GET /api/admin/accounts` (admin account listing/filtering)
- `PATCH /api/admin/accounts/{accountNumber}/status` (freeze/reactivate)
- `GET /api/admin/audit`
- `GET /api/admin/fraud`

## Swagger / API Docs

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

To call protected endpoints in Swagger:

1. Login via `POST /api/auth/login`.
2. Copy the JWT token.
3. Click **Authorize** and use `Bearer <token>`.

## Quick Start (Docker)

Prerequisites: Docker and Docker Compose.

```bash
docker compose up --build
```

Service mapping with current compose setup:

- API container listens on `8080`
- Host port exposed as `8081`

So access docs at:

- `http://localhost:8081/swagger-ui.html`

Stop services:

```bash
docker compose down
```

## Run Locally (without Docker)

Prerequisites:

- Java 17+
- Maven
- PostgreSQL running locally

Run:

```bash
mvn spring-boot:run
```

Default local app URL:

- `http://localhost:8080`

## Default Development Admin

On startup, a default admin user is bootstrapped if missing:

- Username: `admin`
- Password: `admin12345`

Use only for local/dev; replace in real environments.

## Testing and CI

Run tests:

```bash
mvn test
```

Run CI-equivalent build:

```bash
mvn -B -DskipITs verify
```

Current suite covers authentication, account and transfer flows, edge cases, idempotency behavior, admin controls, webhook triggers, and statement export.

## Deployment Notes

- Recommended for full backend hosting: Render, Railway, Fly.io, AWS, or similar Java-friendly platforms.
- Vercel is suitable for hosting a static Swagger docs frontend, not the full Spring Boot runtime.

### Render Quick Deploy

This repository includes `render.yaml` for a Docker web service and managed Postgres database.

1. Push to GitHub.
2. Create a Render **Blueprint** from this repository.
3. Render auto-provisions:
   - `nova-bank-api` (web service)
   - `nova-bank-db` (Postgres)
4. Set manual secrets in Render:
   - `SECURITY_JWT_SECRET` (or let Render keep your existing secure value)

Runtime environment variables are documented in `.env.example`.

## Portfolio Positioning

This repo demonstrates end-to-end backend product thinking:

- Domain modeling for financial operations
- Security-first API design
- Reliability features (idempotency + audit trail)
- Operational/admin tooling
- Automated verification and containerized delivery

If you are evaluating this project as a portfolio artifact, start with Swagger and run the transaction and admin flows to see the product behavior quickly.
