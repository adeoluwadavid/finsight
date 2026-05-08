# FinSight AI

An AI-powered financial intelligence platform for Nigerian SMEs. Upload invoices and receipts, get structured financial data extracted automatically, ask questions about your business finances in natural language, and visualize spending trends with AI-generated charts.

> 📋 **API Payloads & Test Guide** → [PAYLOADS.md](./PAYLOADS.md)

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Services](#services)
- [Database Design](#database-design)
- [AI Pipeline](#ai-pipeline)
- [Security](#security)
- [Getting Started](#getting-started)
- [Running With Docker](#running-with-docker)
- [Running Locally](#running-locally)
- [Environment Configuration](#environment-configuration)
- [API Documentation](#api-documentation)
- [API Reference](#api-reference)
- [Developer Tools](#developer-tools)

---

## Overview

FinSight AI solves a real problem for Nigerian SMEs: invoices and receipts pile up as PDFs, manual entry into spreadsheets is slow and error-prone, and there is no real-time picture of business spending.

FinSight AI provides:
- **Automatic invoice extraction** — upload a PDF and AI extracts vendor, dates, line items, totals, and category
- **Semantic search** — find invoices by meaning, not just keywords
- **RAG-powered chat** — ask "what did I spend on logistics last month?" and get a grounded answer from your actual data
- **Chart generation** — ask for a spending breakdown and receive a chart specification alongside the insight

---

## Architecture

FinSight AI is built as **four independent microservices** behind a single API Gateway. Each service owns its own database schema and is independently deployable.

```
finsight-ai/
├── api-gateway/           → Spring Cloud Gateway — JWT validation, routing (port 8080)
├── auth-service/          → User registration, login, JWT issuance (port 8081)
├── document-service/      → PDF upload, AI extraction, embeddings (port 8082)
└── conversation-service/  → RAG chatbot, spending insights, chart generation (port 8083)
```

### Request Flow

```
Client
  ↓
API Gateway (port 8080)
  → validates JWT
  → extracts userId from token
  → forwards X-User-Id header
  ↓
Auth Service    /api/auth/**
Document Service   /api/documents/**
Conversation Service  /api/conversations/**
```

### Document Processing Pipeline

```
PDF Upload
  ↓
PDFBox — extract raw text
  ↓
Groq (Llama 3.3 70B) — structured JSON extraction
  ↓
Postgres — save vendor, dates, line items, totals
  ↓
Jina AI — generate 768-dim vector embedding
  ↓
pgvector — store embedding for semantic search
```

### RAG Query Pipeline

```
User question
  ↓
Jina AI — embed question → query vector
  ↓
pgvector — cosine similarity search → relevant invoice chunks
  ↓
SQL — retrieve structured invoice data
  ↓
Groq — answer grounded in retrieved context
  ↓
Response — text answer + optional chart specification (JSON)
```

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 |
| API Gateway | Spring Cloud Gateway (WebFlux) |
| Security | Spring Security + JWT (JJWT 0.12.6) |
| Database | PostgreSQL 17 + pgvector extension |
| Migrations | Flyway |
| ORM | Spring Data JPA / Hibernate |
| PDF Parsing | Apache PDFBox 3.x |
| LLM | Groq API (Llama 3.3 70B) |
| Embeddings | Jina AI (jina-embeddings-v2-base-en, 768 dims) |
| Vector Store | pgvector (cosine similarity, IVFFlat index) |
| Build Tool | Maven (monorepo with parent POM) |
| Containerization | Docker + Docker Compose |
| API Docs | SpringDoc OpenAPI 3.x (Swagger UI) |

---

## Services

### API Gateway (port 8080)
The single entry point for all client requests. Built on Spring Cloud Gateway with WebFlux (reactive/non-blocking).

- JWT validation on every non-public request
- Extracts `userId` and `email` from validated token
- Forwards `X-User-Id` and `X-User-Email` headers to downstream services
- Route definitions: `/api/auth/**`, `/api/documents/**`, `/api/conversations/**`
- Public routes: `/api/auth/register`, `/api/auth/login`, `/api/auth/health`

### Auth Service (port 8081)
Handles user identity and token management.

- User registration with BCrypt password hashing
- Login returning JWT access token (15 min) + refresh token (7 days)
- Refresh token stored in database for validation
- Spring Security stateless configuration
- Flyway-managed `auth` schema

### Document Service (port 8082)
The AI document intelligence engine.

- Multipart PDF upload (max 20MB)
- Apache PDFBox text extraction
- Async processing — upload returns immediately, extraction runs in background
- Groq API call with strict JSON schema prompt — extracts vendor, invoice number, dates, subtotal, tax, total, currency, category, line items
- Jina AI embeddings — converts extracted text to 768-dimension vector
- pgvector storage with IVFFlat index for fast cosine similarity search
- Status polling endpoint — UPLOADED → PROCESSING → COMPLETED / FAILED
- Semantic search endpoint — find invoices by natural language query
- Flyway-managed `documents` schema

### Conversation Service (port 8083)
The RAG chatbot and financial insights engine.

- Create and manage chat conversations
- Hybrid retrieval — vector similarity search + SQL filter by userId
- Context assembly from retrieved invoice chunks and structured data
- Groq chat completions with financial assistant system prompt
- Chart specification generation — when user asks for visualizations, Groq returns Chart.js compatible JSON alongside text
- Chart spec stored as JSONB in the messages table
- Multi-turn conversation history (last 6 messages included as context)
- Flyway-managed `conversations` schema

---

## Database Design

Single PostgreSQL 17 database with separate schemas per service:

```
finsight_db
├── auth schema
│   ├── users
│   └── refresh_tokens
├── documents schema
│   ├── documents
│   ├── extracted_invoices
│   ├── invoice_line_items
│   └── document_embeddings  ← vector(768) column
└── conversations schema
    ├── conversations
    └── messages              ← chart_spec JSONB column
```

### Key Design Decisions

**Schema-per-service** — each service runs Flyway migrations against its own schema. Services do not share tables. Cross-service data access uses direct JDBC queries in the conversation service (for RAG retrieval from the documents schema).

**pgvector for semantic search** — the `document_embeddings` table stores 768-dimension float vectors. Cosine similarity search (`<=>` operator) finds semantically related invoice chunks. IVFFlat index with 100 lists provides fast approximate nearest-neighbour search.

**JSONB for chart specs** — the `chart_spec` column on messages stores Chart.js compatible JSON. Nullable — only populated when Groq decides a chart adds value to the answer.

**Async document processing** — `@Async` with a dedicated thread pool (`doc-processing-*`). The upload endpoint returns immediately with `UPLOADED` status. The client polls `/documents/{id}/status` until `COMPLETED`.

**Native SQL for vector operations** — pgvector's `<=>` operator is not understood by JPQL. The embedding insert and similarity search use `@Query(nativeQuery = true)` with explicit `CAST(? AS vector)`.

---

## AI Pipeline

### Invoice Extraction Prompt

The document service sends extracted PDF text to Groq with a strict JSON schema prompt. Temperature is set to 0.1 for consistent, deterministic extraction. The model is instructed to return only a JSON object with no preamble or markdown.

Extracted fields: `vendorName`, `invoiceNumber`, `issueDate`, `dueDate`, `subtotal`, `tax`, `total`, `currency`, `category`, `lineItems[]`

Category classification is automatic — the model picks from: Technology, Marketing, Logistics, Office Supplies, Utilities, Professional Services, Food & Beverage, Other.

### RAG Retrieval

For each user message the conversation service:

1. Embeds the question using Jina AI
2. Runs a cosine similarity search against `document_embeddings` filtered by `user_id`
3. Retrieves up to 5 most similar chunks alongside their invoice metadata
4. Queries the 20 most recent invoices for the user (structured SQL)
5. Assembles both into a context block prepended to the Groq system prompt

### Chart Generation

The system prompt instructs Groq to include a chart specification when the question asks for breakdowns, trends, or comparisons. The chart spec follows Chart.js format:

```json
{
  "type": "pie",
  "title": "Spending by Category",
  "labels": ["Office Supplies", "Logistics"],
  "datasets": [{ "label": "Amount (NGN)", "data": [205000, 150000] }]
}
```

Supported types: `bar`, `line`, `pie`, `doughnut`. The frontend renders this directly with Chart.js or Recharts.

---

## Security

### Authentication Flow

```
POST /api/auth/login
→ Returns: accessToken (15 min) + refreshToken (7 days)

Every protected request:
→ Authorization: Bearer {accessToken}
→ API Gateway JwtAuthenticationFilter validates token
→ Gateway extracts userId, forwards X-User-Id header
→ Downstream services trust X-User-Id (set by gateway only)
```

### Edge-Auth Pattern

JWT validation happens exclusively at the gateway. Downstream services do not validate tokens — they trust the `X-User-Id` header injected by the gateway. This means:

- No token validation code duplicated across services
- If the gateway is bypassed, services reject requests (no X-User-Id header)
- In production, downstream service ports are not exposed — only port 8080

### JWT Claims

| Claim | Value |
|---|---|
| `sub` | user email |
| `userId` | user UUID |
| `role` | USER |
| `iat` | issued at |
| `exp` | expiry (15 min) |

---

## Getting Started

### Prerequisites

**For Docker setup (recommended):**
- Docker Desktop

**For local setup:**
- Java 17+
- Maven 3.8+ (or use IntelliJ's bundled Maven)
- PostgreSQL 17 with pgvector extension
- Groq API key (free at console.groq.com)
- Jina AI API key (free at jina.ai)

### Clone the Repository

```bash
git clone https://github.com/adeoluwadavid/finsight-ai.git
cd finsight-ai
```

### Create Environment File

```bash
cp .env.example .env
```

Edit `.env` and fill in your API keys:

```
GROQ_API_KEY=gsk_your_groq_key_here
JINA_API_KEY=jina_your_jina_key_here
JWT_SECRET=finsight_jwt_secret_key_must_be_at_least_32_bytes_long
POSTGRES_PASSWORD=finsight_dev_password
```

---

## Running With Docker

The easiest way to run FinSight AI. One command starts everything.

```bash
docker compose up --build
```

This starts five containers:

| Container | Port | Description |
|---|---|---|
| finsight-gateway | 8080 | API Gateway — all client requests go here |
| finsight-auth | 8081 | Auth Service |
| finsight-document | 8082 | Document Service |
| finsight-conversation | 8083 | Conversation Service |
| finsight-postgres | 5433 | PostgreSQL 17 with pgvector |

Flyway runs all migrations automatically on startup. All schemas and tables are created fresh on first run.

### Stop Services

```bash
docker compose down
```

### Stop and Remove All Data

```bash
docker compose down -v
```

---

## Running Locally

### 1. Set Up PostgreSQL

Connect to your local PostgreSQL and run:

```sql
CREATE DATABASE finsight;
\c finsight
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS documents;
CREATE SCHEMA IF NOT EXISTS conversations;
```

### 2. Set Environment Variables

```bash
export GROQ_API_KEY=gsk_your_key_here
export JINA_API_KEY=jina_your_key_here
```

### 3. Update application.yml

Update the `username` and `password` in each service's `application.yml` to match your local PostgreSQL credentials.

### 4. Run Each Service

Open four terminal tabs and run each service:

```bash
# Terminal 1
cd auth-service && mvn spring-boot:run

# Terminal 2
cd document-service
export GROQ_API_KEY=your_key && export JINA_API_KEY=your_key
mvn spring-boot:run

# Terminal 3
cd conversation-service
export GROQ_API_KEY=your_key && export JINA_API_KEY=your_key
mvn spring-boot:run

# Terminal 4
cd api-gateway && mvn spring-boot:run
```

All requests go through the gateway on port 8080.

---

## Environment Configuration

### Required Environment Variables

| Variable | Description | Where used |
|---|---|---|
| `GROQ_API_KEY` | Groq API key for LLM inference | document-service, conversation-service |
| `JINA_API_KEY` | Jina AI key for text embeddings | document-service, conversation-service |
| `JWT_SECRET` | Min 32-character secret for JWT signing | all services |
| `POSTGRES_PASSWORD` | PostgreSQL password | Docker only |

### Service Ports

| Service | Local Port | Docker Port |
|---|---|---|
| API Gateway | 8080 | 8080 |
| Auth Service | 8081 | 8081 |
| Document Service | 8082 | 8082 |
| Conversation Service | 8083 | 8083 |
| PostgreSQL | 5432 (local) | 5433 (Docker) |

> **Never commit API keys or secrets to version control.**
> GitHub secret scanning will block your push.

---

## API Documentation

Each service exposes its own Swagger UI:

| Service | Swagger UI |
|---|---|
| Auth Service | http://localhost:8081/swagger-ui.html |
| Document Service | http://localhost:8082/swagger-ui.html |
| Conversation Service | http://localhost:8083/swagger-ui.html |

> All endpoints in production should be accessed through the gateway on port 8080.

---

## API Reference

### Auth Endpoints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | /api/auth/register | Public | Register new business account |
| POST | /api/auth/login | Public | Login and receive JWT tokens |
| GET | /api/auth/health | Public | Auth service health check |

### Document Endpoints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | /api/documents/upload | Authenticated | Upload PDF invoice or receipt |
| GET | /api/documents/{id}/status | Authenticated | Poll processing status |
| GET | /api/documents/{id}/extracted | Authenticated | Get structured invoice data |
| POST | /api/documents/search | Authenticated | Semantic search across documents |
| GET | /api/documents/health | Public | Document service health check |

### Conversation Endpoints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | /api/conversations | Authenticated | Create new chat session |
| GET | /api/conversations | Authenticated | List all conversations |
| POST | /api/conversations/{id}/messages | Authenticated | Send message, get AI answer |
| GET | /api/conversations/{id}/messages | Authenticated | Retrieve chat history |
| GET | /api/conversations/health | Public | Conversation service health check |

> 📋 For full request/response payloads and test sequences → [PAYLOADS.md](./PAYLOADS.md)

---

## Developer Tools

| Tool | URL | Notes |
|---|---|---|
| Auth Swagger | http://localhost:8081/swagger-ui.html | JWT token required for protected routes |
| Document Swagger | http://localhost:8082/swagger-ui.html | JWT token required for protected routes |
| Conversation Swagger | http://localhost:8083/swagger-ui.html | JWT token required for protected routes |

---

## Author

**David Adewole**
Software Engineer
Lagos, Nigeria
adeoluwadavid@gmail.com
