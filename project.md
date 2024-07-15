🏦 NOVA Bank Core System — AI Development Specification
📌 Project Overview

Develop a secure, high-performance Core Banking System using Spring Boot as the backend framework.
The system should handle digital banking operations such as:

User authentication (multi-layer, role-based)

Account management

Transaction processing (deposits, withdrawals, transfers)

Fraud detection and monitoring

Compliance auditing and reporting

Real-time balance updates and transaction tracking

The final solution must be containerized, API-driven, secure, and ready for deployment (preferably via Docker and optionally CI/CD).

🧠 Core Requirements
1. Technology Stack

Backend Framework: Spring Boot (Java 17+)

Database: PostgreSQL

ORM: Spring Data JPA / Hibernate

Authentication: Spring Security + JWT + optional 2FA (OTP/Email)

Logging & Monitoring: Logback + Actuator + Prometheus (optional)

API Documentation: Swagger/OpenAPI 3

Build Tool: Maven or Gradle

Testing: JUnit 5 + Mockito

Deployment: Docker (compose optional)

IDE: CLion

AI Assistant: Junie AI

🏗️ System Architecture

Use a Layered Architecture pattern:

Controller (API Layer)
↓
Service (Business Logic Layer)
↓
Repository (Data Access Layer)
↓
Entity (Model Layer)


Optionally include:

dto package for API input/output data transfer

exception package for global exception handling

config package for Spring configuration and security settings

🔐 Core Modules & Features
1. User Management

Register, verify, and authenticate users.

Role-based access control (Admin, Customer, Auditor).

Multi-layer authentication: Password + OTP/Email confirmation.

Password encryption using BCrypt.

2. Account Management

Create and manage multiple bank accounts per user.

Track balances, interest, and status (active/frozen).

Real-time balance updates using transactional consistency.

3. Transaction Processing

Deposit, withdrawal, and inter-account transfers.

Transaction validation (e.g., sufficient funds, limits).

Transaction receipts with timestamps and unique IDs.

4. Fraud Detection

Detect suspicious activities (e.g., multiple failed logins, large withdrawals).

Automatic alerts or account flagging.

Maintain fraud logs for auditing.

5. Transaction Monitoring & Audit Logs

Maintain immutable logs for all user and system actions.

Admin dashboard APIs for viewing logs and transactions.

Optional integration with Prometheus for monitoring.

⚙️ API Endpoints (Examples)
Endpoint	Method	Description
/api/auth/register	POST	Create a new user
/api/auth/login	POST	Authenticate and return JWT
/api/accounts	GET	List all accounts of current user
/api/transactions/transfer	POST	Transfer money between accounts
/api/admin/audit	GET	View transaction and fraud logs
🧩 Security & Compliance

Use HTTPS and JWT authentication.

Validate all incoming requests.

Protect against SQL injection, CSRF, and XSS.

Enforce strong password and session policies.

Log sensitive events (login attempts, transfers).

Comply with standard banking-grade security protocols.

🧪 Testing & QA

Unit tests for all services and controllers (JUnit 5).

Integration tests for database and API layers.

Mock authentication and external services using Mockito.

Test coverage target: 80%+

🐳 Deployment

Create Dockerfile for the Spring Boot app.

Create docker-compose.yml linking app and PostgreSQL.

Expose API via port 8080.

Optionally add GitHub Actions or Jenkins CI/CD script for auto-build and deploy.

📘 Deliverables

Complete Spring Boot source code with all layers implemented.

application.yml configured for both dev and prod.

SQL migrations (schema.sql, data.sql).

API documentation (Swagger UI).

Docker setup for deployment.

Unit and integration tests.

README.md with setup instructions.

🚀 Instructions for Junie AI

Initialize a new Spring Boot project with the given structure and dependencies.

Implement all modules and endpoints with clean, modular code.

Ensure the system compiles and runs without errors in CLion.

Generate test data and validate full flow (register → deposit → transfer → fraud detection).

Containerize using Docker.

Deploy locally (Docker or CLion integrated server).

Verify Swagger UI endpoints work correctly.