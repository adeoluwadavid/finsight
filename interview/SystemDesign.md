# FinSight AI — System Design Interview Guide

> This document covers system design interview questions related to
> FinSight AI's architecture and how to scale it for production.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Database Design](#database-design)
- [AI Pipeline Design](#ai-pipeline-design)
- [Scalability](#scalability)
- [Reliability and Fault Tolerance](#reliability-and-fault-tolerance)
- [Security Architecture](#security-architecture)
- [Caching Strategy](#caching-strategy)
- [Monitoring and Observability](#monitoring-and-observability)
- [Real-World Scenarios](#real-world-scenarios)

---

## Architecture Overview

---

**Q: Walk me through the high-level architecture of FinSight AI.**

FinSight AI is a four-service microservices system behind a single API Gateway.

```
┌─────────────────────────────────────────────────────────────┐
│                        Client                               │
└─────────────────────────────┬───────────────────────────────┘
                              │ All requests: port 8080
┌─────────────────────────────▼───────────────────────────────┐
│                     API Gateway                             │
│           Spring Cloud Gateway (WebFlux)                    │
│    • JWT validation          • userId extraction            │
│    • Route to services       • X-User-Id header injection   │
└──────────┬──────────────────┬──────────────────┬────────────┘
           │                  │                  │
    ┌──────▼──────┐   ┌───────▼──────┐  ┌────────▼──────┐
    │   Auth      │   │   Document   │  │ Conversation  │
    │  Service    │   │   Service    │  │   Service     │
    │  :8081      │   │   :8082      │  │   :8083       │
    └──────┬──────┘   └───────┬──────┘  └────────┬──────┘
           │                  │                  │
           └──────────────────┼──────────────────┘
                              │
              ┌───────────────▼────────────────┐
              │        PostgreSQL 17            │
              │   + pgvector extension          │
              │                                 │
              │  auth schema                    │
              │  documents schema               │
              │  conversations schema           │
              └─────────────────────────────────┘
```

**Request flow for a document upload:**
```
1. Client sends POST /api/documents/upload with JWT
2. API Gateway validates JWT, extracts userId, injects X-User-Id header
3. Request routed to document-service on port 8082
4. DocumentService saves PDF to disk, creates Document record (UPLOADED)
5. @Async method triggered — upload returns immediately to client
6. Background thread:
   a. PDFBox extracts raw text from PDF
   b. Groq API called — returns structured JSON (vendor, amounts, etc.)
   c. ExtractedInvoice + InvoiceLineItems saved to Postgres
   d. Jina AI called — returns 768-dim embedding vector
   e. Vector saved to document_embeddings table (pgvector)
   f. Document status updated to COMPLETED
7. Client polls GET /documents/{id}/status until COMPLETED
8. Client calls GET /documents/{id}/extracted for structured data
```

**Request flow for a chat message:**
```
1. Client sends POST /api/conversations/{id}/messages with JWT
2. API Gateway validates JWT, routes to conversation-service
3. ConversationService saves user message
4. RagService executes:
   a. Embed question → Jina AI → 768-dim query vector
   b. pgvector cosine search → 5 most similar invoice chunks
   c. SQL query → 20 most recent invoices (structured data)
   d. Assemble context string from both results
   e. Build Groq messages array (system prompt + context + history + question)
   f. Call Groq API → text answer + optional chart spec JSON
5. Parse response → separate text from chart spec
6. Save assistant message with chartSpec
7. Return MessageResponse to client
```

---

**Q: Why did you choose a reactive gateway (WebFlux) instead of a regular
servlet-based gateway?**

A gateway is a proxy — its core job is to receive a request, forward it
to another service, and stream the response back. During the forwarding
and waiting, the gateway does not need to compute anything.

**Servlet-based gateway problem:**
```
Request arrives → thread allocated → thread waits for upstream service
→ thread sits idle during entire upstream processing time
→ at high concurrency, all threads are waiting, no new requests can be accepted
```

**WebFlux (reactive) gateway advantage:**
```
Request arrives → handler registered → thread freed immediately
→ when upstream responds, a thread is allocated just to forward the response
→ same thread pool handles many concurrent requests
→ non-blocking I/O — threads are never idle waiting
```

For a gateway handling 1000 concurrent requests, a servlet gateway needs
1000 threads. A reactive gateway can handle 1000 requests with ~50 threads.

This is why Spring Cloud Gateway is built on WebFlux — it is the correct
technical choice for a proxy/routing layer.

**The important tradeoff:**
WebFlux code looks different (`Mono<Void>`, `ServerWebExchange`). The other
three services use normal servlet Spring Boot because they do actual
computation (database queries, PDF processing) where blocking is acceptable
and the simpler programming model is valuable.

---

**Q: How would you design FinSight AI to support 100,000 users uploading
10 documents per day?**

At 1 million document uploads per day (~12 per second), the current
architecture needs enhancements:

**Document processing bottleneck:**
```
Current: in-process async thread pool (max 5 threads)
→ 5 documents processing simultaneously
→ Queue fills up under high load

Solution: extract to a dedicated processing queue
→ Document uploaded → message published to queue
→ Dedicated document processors consume from queue
→ Scale processors independently of upload API
→ Use Kafka or RabbitMQ as the queue
```

**Database layer:**
```
Current: single PostgreSQL instance
Problem: pgvector index performance degrades at very large scale

Solutions:
→ Read replicas: conversation-service reads from replica,
  document-service writes to primary
→ Partition document_embeddings by user_id or created_at
→ Dedicated vector database (Qdrant, Weaviate) for embeddings
  at tens of millions of vectors
```

**Storage layer:**
```
Current: files saved to local disk (/app/uploads)
Problem: local disk does not scale across multiple instances

Solution:
→ AWS S3 or Cloudflare R2 for PDF storage
→ Documents service uploads to S3, stores S3 key in database
→ Multiple document-service instances all access same S3 bucket
```

**API layer:**
```
Current: single instance per service
Solution:
→ Multiple instances behind a load balancer
→ Stateless design (JWT) allows any instance to handle any request
→ Auto-scaling based on queue depth (document-service scales up
  when upload queue grows)
```

---

## Database Design

---

**Q: Explain the schema-per-service design and its tradeoffs.**

FinSight AI uses one PostgreSQL database with three schemas:
`auth`, `documents`, `conversations`.

**What each service can access:**

| Service | Primary schema | Cross-schema access |
|---|---|---|
| auth-service | auth | None |
| document-service | documents | None |
| conversation-service | conversations | documents (RAG retrieval via JdbcTemplate) |

The conversation service crosses schema boundaries deliberately — RAG requires
querying document embeddings and invoice data. This is an intentional architectural
decision documented in the code. All cross-schema queries use fully qualified
table names (`documents.extracted_invoices`).

**Tradeoffs of shared database:**

| Aspect | Shared DB (current) | Separate DBs |
|---|---|---|
| Operations | Simple — one DB to manage | Complex — four DBs |
| Cost | Low | Higher |
| Isolation | Logical (schema) | Physical |
| Cross-service queries | Possible (used for RAG) | Requires API calls |
| Future migration | Change connection strings | Already separated |
| pgvector setup | Once | Per DB that needs it |

For a portfolio project and early-stage product, the shared database is
the right pragmatic choice. The schema isolation provides 90% of the
benefit of separate databases at 10% of the operational cost.

---

**Q: How does the pgvector IVFFlat index work and when would you switch
to a dedicated vector database?**

**IVFFlat index mechanics:**
```
Build phase:
→ All vectors clustered into 100 groups (lists)
→ Each cluster has a centroid vector

Query phase:
→ Compare query vector against all 100 centroids
→ Select the N most similar centroids
→ Only search vectors in those N clusters
→ Return top-K results from searched clusters
```

With `lists = 100`, the index divides the vector space into 100 regions.
A query with `probes = 10` (default) searches 10 of those 100 regions.
This finds approximately 95% of the true nearest neighbours at 10x speed.

**When to stay with pgvector:**
- Less than 1 million vectors
- Your team already uses PostgreSQL
- You need ACID transactions involving both vectors and relational data
- Simplicity is a priority

**When to switch to dedicated vector database (Qdrant, Weaviate):**
- More than 10 million vectors
- Sub-10ms query latency requirements at scale
- Advanced filtering on metadata alongside vector search
- Multiple index types needed (HNSW for higher recall)

For FinSight AI at current scale (thousands of documents per user),
pgvector is the correct choice. Switching to Qdrant would require
replacing only `DocumentEmbeddingRepository` — the rest of the
codebase is unaffected.

---

**Q: How would you redesign the database if FinSight AI needed to support
multi-tenancy (many businesses, each with their own isolated data)?**

FinSight AI already has single-tenant multi-user support — each user sees
only their own documents. True multi-tenancy for business accounts requires
a different model.

**Current model:**
```
user_id → individual user
Each document owned by one user
```

**Multi-tenant model:**
```
tenant_id → business/organisation
user_id → individual within a tenant
Documents owned by tenant, accessible to users within tenant
```

**Schema changes needed:**

```sql
-- Add tenant to users
ALTER TABLE auth.users ADD COLUMN tenant_id UUID NOT NULL;

-- Add tenant to documents
ALTER TABLE documents.documents ADD COLUMN tenant_id UUID NOT NULL;

-- Add tenant to conversations
ALTER TABLE conversations.conversations ADD COLUMN tenant_id UUID NOT NULL;

-- All queries filter by tenant_id AND user_id (or just tenant_id for shared access)
```

**JWT changes:**
```json
{
  "sub": "user@company.com",
  "userId": "uuid",
  "tenantId": "uuid",
  "role": "OWNER"
}
```

**Access control within a tenant:**
```
OWNER → full access to all tenant documents
MEMBER → access to own uploads + shared documents
VIEWER → read-only access to tenant documents
```

Row-level security (RLS) in PostgreSQL is the cleanest way to enforce
tenant isolation at the database level — every query automatically
filtered by the current tenant ID from the session context.

---

## AI Pipeline Design

---

**Q: How would you design the extraction pipeline to handle 10,000 invoice
uploads per hour?**

At ~3 invoices per second, the current in-process async approach hits limits:

**Current architecture:**
```
Upload API → @Async thread pool (max 5) → process inline
Bottleneck: 5 concurrent Groq API calls maximum
```

**Redesigned pipeline:**
```
Upload API → save file → publish event → return immediately
                              ↓
                    Message Queue (Kafka/RabbitMQ)
                              ↓
              Document Processing Service (horizontal scale)
              → Consume message
              → PDFBox extraction
              → Groq API call (rate limited — 30 req/min free tier)
              → Jina AI call
              → Save to Postgres
              → Publish completion event
                              ↓
              Conversation Service consumes completion event
              → Update user's spending summary cache
```

**Groq rate limit consideration:**
The free tier allows approximately 30 requests per minute. At 10,000
uploads per hour (~167 per minute), the free tier is insufficient.
Production would require:
- Groq paid plan (higher rate limits)
- Or self-hosted Llama 3 via Ollama
- Or batch processing with a queue and rate-limited consumer

**Jina AI embedding at scale:**
Jina supports batch embedding — multiple texts in one API call:
```json
{
  "model": "jina-embeddings-v2-base-en",
  "input": ["text1", "text2", "text3"]
}
```
Processing chunks in batches reduces API call overhead significantly.

---

**Q: How would you add streaming responses to FinSight AI to make it
feel more like ChatGPT?**

Streaming means the LLM sends tokens as they are generated rather than
waiting for the complete response. This makes the UI feel responsive even
for long answers.

**Groq supports Server-Sent Events (SSE) streaming:**
```json
{
  "model": "llama-3.3-70b-versatile",
  "stream": true,
  "messages": [...]
}
```

The response comes as a stream of events:
```
data: {"choices":[{"delta":{"content":"You"},...}]}
data: {"choices":[{"delta":{"content":" spent"},...}]}
data: {"choices":[{"delta":{"content":" NGN"},...}]}
data: [DONE]
```

**Spring Boot streaming endpoint:**
```java
@PostMapping(value = "/{id}/messages/stream",
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamMessage(@PathVariable UUID id,
                                   @RequestBody SendMessageRequest request) {
    return ragService.streamAnswer(request.getContent(), userId);
}
```

**Tradeoff:**
Streaming complicates chart spec extraction — you cannot parse the chart
JSON until the stream is complete. The solution is a two-phase approach:
stream the text answer, then emit the chart spec as a final event.

---

## Scalability

---

**Q: What are the bottlenecks in FinSight AI and how would you address each one?**

**Bottleneck 1: Groq API rate limits**
```
Problem: Free tier is 30 requests/minute
Impact: Document processing queue builds up under high load
Solution:
→ Paid Groq plan for higher limits
→ Local Llama 3 via Ollama (no rate limits, no API cost)
→ Request deduplication — same PDF should not be processed twice
→ Async queue with rate-limited consumer (token bucket algorithm)
```

**Bottleneck 2: pgvector search performance**
```
Problem: cosine search scales as O(n) without good index, O(log n) with IVFFlat
Impact: search slows as users upload more documents
Solution:
→ IVFFlat index (already implemented)
→ HNSW index for better recall at scale
→ Dedicated vector DB at >10M vectors
→ Filter by user_id before vector search (already implemented — reduces search space)
```

**Bottleneck 3: File storage**
```
Problem: local disk does not scale across instances
Impact: cannot run multiple document-service instances
Solution:
→ S3/Cloudflare R2 for PDF storage
→ Service stores S3 key, not file path
→ Any instance can access any file via S3 key
```

**Bottleneck 4: Single database writer**
```
Problem: all services write to one PostgreSQL instance
Impact: write throughput limited by single instance
Solution:
→ Read replicas for query-heavy operations
→ Connection pooling via PgBouncer
→ Separate database per service at true microservices scale
```

---

**Q: How would you implement caching in FinSight AI?**

Two caching opportunities exist:

**1. User spending summary cache:**
```
Problem: every "what did I spend this month?" query runs SQL aggregation
Solution:
→ After each document is processed, update a pre-computed summary
→ Store in Redis: "user:{userId}:summary:{month}" → JSON summary
→ Conversation service reads from cache, falls back to SQL
→ Cache invalidated when new document processed for that user
```

**2. Embedding cache:**
```
Problem: identical questions embed the same text repeatedly
Solution:
→ Cache embeddings by text hash: "embed:{hash(text)}" → vector
→ Jina AI only called for new/unseen text
→ TTL: 24 hours (embeddings don't change for same text)
→ Reduces Jina API costs significantly
```

**Redis integration in Spring Boot:**
```java
@Cacheable(value = "embeddings", key = "#text.hashCode()")
public float[] embed(String text) {
    return jinaApiCall(text);
}
```

**Cache invalidation strategy:**
Embeddings are deterministic — same text always produces the same vector.
No invalidation needed. Spending summaries need invalidation on new uploads.
Event-driven invalidation is cleaner than TTL for this use case.

---

## Reliability and Fault Tolerance

---

**Q: What happens if the Groq API is down when a user uploads a document?**

**Current behavior:**
```
Document saved → UPLOADED
→ PDFBox extracts text → OK
→ Groq API call → throws RuntimeException
→ Catch block: document.setStatus("FAILED"), save error message
→ User polls status → sees FAILED with error message
```

The failure is handled gracefully — the upload does not fail, the document
just stays in FAILED state.

**Production improvements:**

**1. Retry with exponential backoff:**
```java
@Retryable(maxAttempts = 3,
           backoff = @Backoff(delay = 2000, multiplier = 2))
public String extractInvoiceData(String pdfText) {
    return groqApiCall(pdfText);
}
```
Retries after 2s, 4s, 8s before marking as FAILED.

**2. Circuit breaker (Resilience4j):**
```java
@CircuitBreaker(name = "groq", fallbackMethod = "extractFallback")
public String extractInvoiceData(String pdfText) {
    return groqApiCall(pdfText);
}
```
If Groq is consistently failing, stop trying immediately and fail fast.
Prevents thread pool exhaustion.

**3. Manual retry endpoint:**
```
POST /documents/{id}/retry
→ Re-triggers processing for a FAILED document
→ User can retry after Groq recovers
```

**4. Alternative LLM fallback:**
```
Primary: Groq (fast, free tier)
Fallback: Ollama (local, always available)
→ If Groq circuit breaks → route to local Ollama
→ Slower but always available
```

---

**Q: How would you handle a situation where the Jina embedding API returns
a different vector dimension than expected?**

This is a real production concern — embedding models can be updated,
changing their output dimensions.

**Current FinSight AI:**
The column is defined as `vector(768)`. If Jina returns 512 or 1024
dimensions, the INSERT fails with a Postgres error.

**Detection:**
```java
float[] embedding = embeddingClient.embed(chunk);
if (embedding.length != 768) {
    log.error("Unexpected embedding dimension: expected 768, got {}",
               embedding.length);
    throw new RuntimeException("Embedding dimension mismatch");
}
```

**Migration strategy if model dimension changes:**
```
1. Deploy new model generating 1024-dim vectors alongside old model
2. Add new column: embedding_v2 vector(1024)
3. Re-embed all existing documents in background job
4. Switch search queries to use embedding_v2
5. Drop embedding column in later migration
```

This requires a Flyway migration and a backfill job — expensive but
necessary for a breaking model change.

---

## Security Architecture

---

**Q: Explain the edge-auth pattern used in FinSight AI.**

Edge-auth means JWT validation happens exclusively at the edge of the
system (the API gateway), not inside each downstream service.

```
Traditional approach (validation in each service):
Client → Service A validates JWT
Client → Service B validates JWT
Client → Service C validates JWT
→ JWT validation code duplicated in 3 places
→ Any service can be called directly bypassing the gateway

Edge-auth approach (FinSight AI):
Client → Gateway validates JWT, injects X-User-Id
        → Service A trusts X-User-Id (no JWT code)
        → Service B trusts X-User-Id (no JWT code)
        → Service C trusts X-User-Id (no JWT code)
→ JWT logic in one place
→ Services cannot be called directly — no JWT means no X-User-Id
```

**The trust model:**
The downstream services trust `X-User-Id` because:
1. In production, their ports are not exposed publicly — only port 8080
2. The header is set by the gateway after JWT validation
3. A client cannot fake the header because it goes through the gateway

**What if someone calls service port 8082 directly in development?**
In development, all ports are exposed for Swagger UI testing. The services
accept `X-User-Id` from anyone in this environment. This is acceptable
for development but in production the service ports would be in a private
network subnet unreachable from the internet.

---

**Q: How would you implement rate limiting in FinSight AI?**

Rate limiting prevents abuse — a single user making thousands of requests
per second to the Groq-backed endpoints would incur significant API costs.

**At the gateway level:**

Spring Cloud Gateway supports rate limiting via Redis:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: conversation-service
          uri: http://conversation-service:8083
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
                key-resolver: "#{@userKeyResolver}"
```

```java
@Bean
public KeyResolver userKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getHeaders()
            .getFirst("X-User-Id")
    );
}
```

This limits each user to 10 requests/second (burst to 20) on the
conversation service — preventing runaway LLM costs.

**Different limits per endpoint:**
```
/api/documents/upload → 5 uploads/minute (expensive processing)
/api/conversations/*/messages → 20 messages/minute (LLM calls)
/api/auth/login → 10 attempts/minute (brute force prevention)
```

---

## Monitoring and Observability

---

**Q: How would you monitor FinSight AI in production?**

Three pillars: metrics, logs, traces.

**Metrics (Spring Boot Actuator + Prometheus + Grafana):**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus
```

Key metrics to track:
```
Business metrics:
→ documents_uploaded_total (counter)
→ documents_processed_total (counter, labelled by status)
→ document_processing_duration_seconds (histogram)
→ rag_query_duration_seconds (histogram)
→ groq_api_calls_total (counter, labelled by service)
→ jina_api_calls_total (counter)

Technical metrics:
→ jvm_memory_used_bytes
→ http_server_requests_seconds (P50, P95, P99 per endpoint)
→ hikaricp_connections_active
→ vector_search_duration_seconds
```

**Structured logging:**
```java
log.info("Document processed: documentId={}, userId={}, durationMs={}, status={}",
    document.getId(), document.getUserId(), duration, document.getStatus());
```
JSON-structured logs enable querying in tools like Datadog or AWS CloudWatch.

**Alerting rules:**
```
Alert: document processing failure rate > 5% for 10 minutes
Alert: Groq API error rate > 10% for 5 minutes
Alert: average RAG query duration > 5 seconds
Alert: vector search duration P99 > 1 second
Alert: disk usage for uploads > 80%
```

**Grafana dashboards:**
```
Dashboard 1: Business Health
→ Documents uploaded per hour
→ Processing success/failure ratio
→ Average extraction time
→ Active conversations per hour

Dashboard 2: AI Pipeline Health
→ Groq API latency (P50, P95, P99)
→ Jina AI latency
→ Vector search latency
→ Token usage per service

Dashboard 3: Infrastructure
→ JVM heap usage
→ Database connections
→ HTTP request rates by endpoint
→ Error rates by service
```

---

## Real-World Scenarios

---

**Q: A user says "the AI extracted the wrong total from my invoice." How would
you investigate?**

**Step 1 — Retrieve the raw extraction data:**
```sql
SELECT ei.raw_json, d.file_path, d.status
FROM documents.extracted_invoices ei
JOIN documents.documents d ON d.id = ei.document_id
WHERE ei.document_id = 'uuid-here';
```
The `raw_json` column stores exactly what Groq returned. This is the
definitive record of what the AI extracted.

**Step 2 — Check the PDF text:**
Re-run PDFBox on the PDF file manually to see what text was extracted.
If the text itself is garbled (common with scanned or complex-layout PDFs),
the extraction error is at the PDFBox level, not the Groq level.

**Step 3 — Test the Groq extraction directly:**
Take the extracted text, send it to Groq manually with the same prompt,
and see if the error is reproducible. If Groq consistently misreads the
layout, prompt engineering can fix it.

**Step 4 — Manual correction:**
In production, provide an API endpoint for users to correct extracted fields:
```
PATCH /documents/{id}/extracted
{ "total": 220375.00 }
```
Corrections are saved but the original `raw_json` is preserved for audit purposes.

**Root cause patterns:**
- Scanned PDF → PDFBox extracts minimal text → Groq cannot find the total → use OCR
- Complex table layout → text extraction is jumbled → improve chunking
- Multi-currency invoice → Groq picks wrong currency → improve currency detection prompt
- Handwritten notes on invoice → PDFBox misses them → OCR required

---

**Q: How would you design FinSight AI to add a feature where users
receive a weekly spending summary email?**

This is a scheduled batch job with notification delivery.

**Architecture:**

```
Scheduler (weekly, Monday 8am WAT)
→ Query all users who have uploaded documents in the past 7 days
→ For each user:
   a. Query extracted_invoices for past 7 days WHERE user_id = ?
   b. Aggregate: total spend, top categories, top vendors
   c. Call Groq with aggregated data → generate email narrative
   d. Send email via SendGrid/Mailgun
   e. Log delivery status
```

**Spring implementation:**
```java
@Scheduled(cron = "0 0 8 * * MON", zone = "Africa/Lagos")
public void sendWeeklySpendingSummaries() {
    List<UUID> activeUsers = getActiveUsersForPastWeek();
    activeUsers.forEach(this::processUserSummary);
}
```

**Fault tolerance:**
- Use a distributed lock (Redis) to prevent duplicate runs if multiple
  instances are running
- Persist which users have received their summary (idempotency)
- Dead letter queue for failed email deliveries
- Retry failed summaries for up to 24 hours

**Personalisation with Groq:**
The weekly summary prompt:
```
"Generate a friendly 3-paragraph weekly spending summary for a Nigerian SME.
Total spent: NGN 450,000
Top category: Office Supplies (NGN 205,000)
Top vendor: Dangote Supplies Ltd (NGN 220,375)
New invoices: 3

Include one actionable insight. Keep it under 150 words."
```

This produces a human-sounding narrative rather than a raw data dump —
making the email genuinely useful to a busy SME owner.

---

**Q: How would you design multi-user access for a business account?**

Currently, each FinSight AI account is individual. A business might
want multiple employees (accountant, CEO, operations manager) to access
the same invoice data.

**Team model:**

```sql
-- New tables needed
CREATE TABLE auth.organisations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    owner_id UUID NOT NULL REFERENCES auth.users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE auth.organisation_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organisation_id UUID NOT NULL REFERENCES auth.organisations(id),
    user_id UUID NOT NULL REFERENCES auth.users(id),
    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER', -- OWNER, ADMIN, MEMBER, VIEWER
    invited_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(organisation_id, user_id)
);
```

**Document ownership changes:**
```sql
-- Documents owned by organisation, not individual user
ALTER TABLE documents.documents ADD COLUMN organisation_id UUID;
ALTER TABLE documents.documents ADD COLUMN uploaded_by UUID NOT NULL;
```

**Access control:**
```
OWNER → full access, billing, invite members, delete org
ADMIN → upload, extract, chat, manage members
MEMBER → upload, extract, chat
VIEWER → read-only — can view extractions and chat but not upload
```

**JWT changes:**
When a user with multiple organisations logs in, they select which
organisation context they are working in:
```json
{
  "sub": "cfo@company.com",
  "userId": "uuid",
  "organisationId": "uuid",
  "orgRole": "ADMIN"
}
```

This is a significant architectural change but the foundation (userId-based
filtering throughout) makes the migration straightforward — replace `userId`
filters with `organisationId` filters where shared access is needed.
