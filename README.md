# LazyDrop Server

LazyDrop is a real-time file sharing and collaboration platform.  
This repository contains the backend server built with Spring Boot, providing RESTful APIs and WebSocket support for session management, file orchestration, billing, and real-time notifications.

> **Note:** This project was originally built as a SaaS product. It's now open-sourced as a portfolio/learning resource.
---

## 🚀 Overview

The LazyDrop Server acts as the central backend service responsible for:

- Managing **Drop Sessions** (temporary or persistent collaboration rooms)
- Secure **File Orchestration** using signed URLs via Supabase Storage
- Real-time collaboration using WebSockets (STOMP)
- Subscription & billing management via Stripe
- Identity resolution and security enforcement via Supabase JWT validation
- Plan enforcement and operational safeguards

The backend is designed with reliability, idempotency, and security in mind.

---

## 🏗️ Architecture Overview

LazyDrop follows a layered backend architecture:

```
Client (Web / Mobile)
        ↓
REST Controllers
        ↓
Service Layer (Business Logic)
        ↓
Repository Layer (JPA/Hibernate)
        ↓
PostgreSQL
```

Real-time communication is handled via STOMP WebSockets.

### External Integrations

- Supabase (Auth + Storage)
- Stripe (Billing & Webhooks)

### Separation of Concerns

- **Controllers** → Request/response handling  
- **Services** → Business logic & validation  
- **Repositories** → Data access  
- **Modules** → Domain-driven boundaries  

---

## 🛠️ Technology Stack

- **Language:** Java 21  
- **Framework:** Spring Boot 4.x  
- **Build Tool:** Maven  
- **Database:** PostgreSQL (JPA/Hibernate)  
- **Security:** Spring Security + OAuth2 Resource Server (Supabase JWT validation)  
- **Real-time:** Spring WebSocket (STOMP)  
- **Storage:** Supabase Storage (Signed URLs)  
- **Payments:** Stripe  
- **Containerization:** Docker  
- **CI/CD:** GitHub Actions  

---

## 📋 Requirements

- Java 21+
- Maven 3.9+
- PostgreSQL
- Supabase account (Auth + Storage)
- Stripe account (Test mode supported)

---

## ⚙️ Setup & Run

### 1️⃣ Clone Repository

```bash
git clone <repository-url>
cd lazydrop_backend
```

### 2️⃣ Configure Environment Variables

Create a `.env` file or configure environment variables:

| Variable                    | Description                          |
|----------------------------|--------------------------------------|
| DB_URL                      | PostgreSQL connection URL           |
| DB_USER                     | Database username                   |
| DB_PASSWORD                 | Database password                   |
| SUPABASE_URL                | Supabase project URL                |
| SUPABASE_ANON_KEY           | Supabase public key                 |
| SUPABASE_JWT_SECRET         | JWT secret for validation           |
| SUPABASE_SERVICE_KEY        | Service role key                    |
| SUPABASE_BUCKET_NAME        | Storage bucket name                 |
| CORS_ALLOWED_ORIGINS        | Allowed origins                     |
| APP_FRONTEND_URL            | Frontend URL                        |
| STRIPE_TEST_SECRET_KEY      | Stripe secret key                   |
| STRIPE_WEBHOOK_SECRET       | Stripe webhook secret               |
| STRIPE_PRICE_PRO            | Stripe Pro price ID                 |
| STRIPE_PRICE_PLUS           | Stripe Plus price ID                |

### 3️⃣ Build Application

```bash
./mvnw clean install
```

### 4️⃣ Run Application

```bash
./mvnw spring-boot:run
```

Server starts at:

```
http://localhost:8080/api/v1
```

---

## 🧪 Testing Strategy

The project follows a layered testing approach:

### Unit Testing

- Service-layer logic tested using JUnit  
- Mockito used for dependency mocking  
- Plan limits, permission checks, and validation logic explicitly tested  

### Controller Testing

- MockMvc used for endpoint validation  
- Tests verify HTTP status codes and response contracts  

### Edge Case Testing

- Plan limit enforcement  
- Unauthorized access attempts  
- Expired / malformed JWTs  
- Duplicate join or confirm attempts (idempotency validation)  

Tests run automatically during CI builds to prevent regressions.

### Future Enhancements

- Testcontainers for integration testing  
- Coverage reporting badge  
- Load testing for high-participant sessions  

---

## 🛡️ Reliability & Idempotency

### Two-Step File Upload Protection

The upload process uses:

- Signed URL generation  
- Metadata confirmation  

To prevent orphaned files:

- Confirm endpoint is idempotent  
- Background cleanup job removes unconfirmed uploads  

### Idempotent Operations

Endpoints like `/confirm` and `/join` are designed to:

- Handle retries safely  
- Avoid duplicate participant creation  
- Return consistent responses  

---

## 📊 Observability & Logging

- Structured logging via SLF4J  
- Centralized exception handling using global exception handlers  
- Security-sensitive fields (JWTs, secrets) are never logged  
- Plan limit violations logged at WARN level  
- Stripe webhooks verified before processing  

### Future Improvements

- Correlation IDs for request tracing  
- Metrics export via Micrometer  
- Health checks and readiness probes  

---

## 📘 API Resources

### Sessions

- `POST /sessions`
- `GET /sessions/{id}`
- `GET /sessions/code/{code}`
- `DELETE /sessions/{id}`
- `GET /sessions/{id}/qr`
- `GET /sessions/active`

### Participants

- `POST /sessions/{id}/participants`
- `DELETE /sessions/{id}/participants`
- `GET /sessions/{id}/participants`
- `PATCH /sessions/{id}/participants/me/settings`

### Files

- `POST /sessions/{id}/files/upload-url`
- `POST /sessions/{id}/files/confirm`
- `GET /sessions/{id}/files/{fileId}/download`
- `GET /sessions/{id}/files`

### Notes

- `POST /sessions/{id}/notes`
- `GET /sessions/{id}/notes`

### Subscriptions

- `GET /subscriptions`
- `POST /subscriptions/checkout`
- `POST /subscriptions/cancel`
- `POST /subscriptions/reactivate`
- `POST /subscriptions/portal`

---

## 🔐 Security Principles

- JWT-based authentication via Supabase  
- Role-based access control  
- Stripe webhook signature verification  
- CORS restrictions enforced  
- Plan enforcement server-side (never client-trusted)  
- Defensive validation on all inputs  

---

## 📦 Deployment

- Dockerized for consistent environments  
- CI/CD pipeline for automated builds  
- Designed for cloud-native deployment  
