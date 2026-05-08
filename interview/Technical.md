# FinSight AI — Technical Interview Guide

> This document covers core Java and Spring Boot technical interview questions
> directly related to what was built in FinSight AI.

---

## Table of Contents

- [Core Java](#core-java)
- [Spring Boot and Spring Framework](#spring-boot-and-spring-framework)
- [Spring Cloud Gateway and WebFlux](#spring-cloud-gateway-and-webflux)
- [Spring Security and JWT](#spring-security-and-jwt)
- [JPA and Hibernate](#jpa-and-hibernate)
- [Database and Flyway](#database-and-flyway)
- [Async Processing](#async-processing)
- [REST API Design](#rest-api-design)
- [Exception Handling](#exception-handling)
- [Docker and Infrastructure](#docker-and-infrastructure)
- [AI Integration](#ai-integration)

---

## Core Java

---

**Q: Why did you use `Optional` in your repository methods?**

`Optional<T>` makes the possible absence of a value explicit at the type level.
This forces the caller to handle the empty case rather than risking a
NullPointerException.

```java
// Without Optional — dangerous
Document doc = documentRepository.findByIdAndUserId(id, userId); // could be null
String filename = doc.getFilename(); // NullPointerException if doc is null

// With Optional — safe and explicit
Document doc = documentRepository
    .findByIdAndUserId(id, userId)
    .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
```

In a financial application, a NullPointerException during document processing
or a chat session is a poor experience. `Optional` forces you to decide what
happens when the resource does not exist — throw a specific exception, return
a default, or handle gracefully. This makes the code more robust and more
readable.

---

**Q: Explain how you used streams in FinSight AI and why.**

Streams provide a declarative way to transform collections. In FinSight AI
they are used extensively for mapping entities to DTOs:

```java
// In ConversationService
public List<MessageResponse> getMessages(UUID conversationId, UUID userId) {
    return messageRepository
        .findByConversationIdOrderByCreatedAtAsc(conversationId)
        .stream()
        .map(this::toMessageResponse)
        .collect(Collectors.toList());
}
```

And for building conversation history for the RAG context:
```java
List<Map<String, String>> history = messageRepository
    .findTop10ByConversationIdOrderByCreatedAtDesc(conversationId)
    .stream()
    .map(m -> {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", m.getRole());
        msg.put("content", m.getContent());
        return msg;
    })
    .collect(Collectors.toList());
```

The stream version is more composable than a for-loop — you can add
filters, sorting, and mappings cleanly without accumulator variables.

---

**Q: Explain the `@RequiredArgsConstructor` annotation and why you used it
instead of `@Autowired`.**

`@RequiredArgsConstructor` is a Lombok annotation that generates a constructor
for all `final` fields:

```java
@Service
@RequiredArgsConstructor
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final ExtractedInvoiceRepository extractedInvoiceRepository;
    private final DocumentProcessingService documentProcessingService;
    private final EmbeddingService embeddingService;
    // Lombok generates this constructor automatically
}
```

**Why constructor injection over `@Autowired` field injection:**

1. **Immutability** — `final` fields cannot be changed after construction.
   This is correct — services should not swap their dependencies at runtime.

2. **Testability** — constructor injection enables easy unit testing:
   ```java
   // Can create DocumentService manually in tests
   DocumentService service = new DocumentService(
       mockDocumentRepository,
       mockExtractedInvoiceRepository,
       mockDocumentProcessingService,
       mockEmbeddingService
   );
   ```
   With `@Autowired` field injection, you need reflection or Spring context
   to inject mocks.

3. **Dependency visibility** — the constructor signature shows exactly what
   a class depends on. A constructor with 10 parameters signals a violation
   of the single responsibility principle — visible immediately.

4. **Spring recommendation** — Spring's official documentation recommends
   constructor injection for mandatory dependencies.

---

**Q: What is the difference between `HashMap` and `LinkedHashMap`? When
did you use each in FinSight AI?**

**`HashMap`:**
- No guaranteed insertion order
- O(1) average for get/put
- Used when order does not matter

**`LinkedHashMap`:**
- Maintains insertion order
- Slightly more memory overhead
- Used when order matters

In FinSight AI, `HashMap` is used for building Groq message objects
where order of keys does not matter:
```java
Map<String, String> message = new HashMap<>();
message.put("role", "user");
message.put("content", userQuestion);
```

In the Groq request body, `LinkedHashMap` would be appropriate if
we wanted to guarantee that `model` appears before `messages` in the
serialized JSON — though in practice JSON parsers are order-agnostic.

The `ObjectMapper` in Jackson serializes both `HashMap` and `LinkedHashMap`
to JSON correctly. The distinction matters when debugging and reading
log output — `LinkedHashMap` prints in insertion order, making logs
more readable.

---

**Q: What is a Java text block and where did you use it in FinSight AI?**

Text blocks (introduced in Java 15, stable in Java 17) allow multi-line
strings without concatenation or escape characters:

```java
// Without text block — hard to read
String sql = "SELECT de.chunk_text,\n" +
             "       ei.vendor_name,\n" +
             "       1 - (de.embedding <=> CAST(? AS vector)) AS similarity\n" +
             "FROM documents.document_embeddings de\n" +
             "WHERE d.user_id = CAST(? AS uuid)";

// With text block — clean and readable
String sql = """
        SELECT de.chunk_text,
               ei.vendor_name,
               1 - (de.embedding <=> CAST(? AS vector)) AS similarity
        FROM documents.document_embeddings de
        WHERE d.user_id = CAST(? AS uuid)
        """;
```

In FinSight AI, text blocks are used extensively in:
- `RagService` — SQL queries and system prompts
- `GroqClient` — extraction prompts
- `JwtAuthenticationFilter` — JSON error response bodies

The indentation is automatically stripped based on the least-indented line.
This makes long SQL queries and LLM prompts significantly more maintainable.

---

## Spring Boot and Spring Framework

---

**Q: What is the difference between `@Component`, `@Service`, `@Repository`,
and `@Controller`? How did you choose which to use?**

All four are specializations of `@Component` — they all register a class
as a Spring bean. The difference is semantic and functional:

| Annotation | Semantic meaning | Special behavior |
|---|---|---|
| `@Component` | Generic Spring bean | None |
| `@Service` | Business logic layer | None — but signals intent |
| `@Repository` | Data access layer | Spring wraps DataAccessExceptions |
| `@Controller` / `@RestController` | Web layer | Handles HTTP requests |

In FinSight AI:
- `@Service` → `DocumentService`, `ConversationService`, `RagService`, `JwtService`
- `@Repository` → `DocumentRepository`, `MessageRepository` (Spring Data JPA interfaces)
- `@RestController` → `DocumentController`, `ConversationController`, `AuthController`
- `@Component` → `GroqClient`, `EmbeddingClient` (infrastructure clients that
  do not fit cleanly in service or repository layers)

The `@Repository` annotation enables Spring's exception translation — database
exceptions from JDBC are wrapped in Spring's `DataAccessException` hierarchy,
giving you consistent exception handling regardless of the underlying database.

---

**Q: What is `@Transactional` and how did you use it in FinSight AI?**

`@Transactional` tells Spring to wrap a method in a database transaction.
If the method completes without exception, the transaction commits.
If any exception is thrown, the transaction rolls back.

In FinSight AI:

```java
@Transactional
public AuthResponse register(RegisterRequest request) {
    // All operations below are in one transaction
    User user = userRepository.save(user);         // INSERT users
    RefreshToken token = refreshTokenRepository.save(token); // INSERT refresh_tokens
    return buildAuthResponse(user);
    // Commit on success, rollback if any step fails
}
```

```java
@Transactional
public MessageResponse sendMessage(UUID conversationId, String content, UUID userId) {
    Message userMessage = messageRepository.save(userMessage);   // INSERT message
    String answer = ragService.answer(content, userId, history); // no DB write
    Message assistantMessage = messageRepository.save(assistantMessage); // INSERT message
    return toMessageResponse(assistantMessage);
    // Both messages saved atomically — either both succeed or neither
}
```

**Important nuance in FinSight AI:**
`DocumentProcessingService.processDocument()` is annotated with both
`@Async` and `@Transactional`. This means the transaction spans the entire
async method — the document status update, invoice save, and line items save
are all in one transaction. If the Groq call fails after saving the invoice,
the rollback undoes the partial save.

**Common mistake:**
Calling a `@Transactional` method from within the same class does not
create a new transaction — Spring's proxy is bypassed. The method must
be called from a different Spring bean to trigger transaction behavior.

---

**Q: Explain Spring profiles and how you used them in FinSight AI.**

Spring profiles allow different configurations for different environments.
FinSight AI uses one profile:

**`docker` profile:**
- Active when `SPRING_PROFILES_ACTIVE=docker` environment variable is set
- Loads `application-docker.yml` in addition to `application.yml`
- Overrides gateway routes to use Docker service names instead of `localhost`

```yaml
# application.yml (local development)
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://localhost:8081  # local

# application-docker.yml (Docker environment)
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://auth-service:8081  # Docker service name
```

**How Spring loads profiles:**
1. `application.yml` is always loaded (base configuration)
2. `application-{profile}.yml` is loaded when that profile is active
3. Profile-specific values override base values for matching keys

**Other common profile patterns:**
```
application-dev.yml → development with debug logging
application-test.yml → test database, mocked external services
application-prod.yml → production configuration
```

In production, sensitive values (API keys, passwords) would come from
environment variables, not YAML files, regardless of profile.

---

## Spring Cloud Gateway and WebFlux

---

**Q: What is WebFlux and how is it different from Spring MVC?**

**Spring MVC (servlet-based):**
- Blocking I/O — one thread per request
- Thread waits while database queries, HTTP calls execute
- Simple programming model (regular methods returning objects)
- Used in auth-service, document-service, conversation-service

**Spring WebFlux (reactive):**
- Non-blocking I/O — threads are never idle waiting
- Uses `Mono<T>` (0 or 1 result) and `Flux<T>` (0 to N results)
- More complex programming model
- Used in api-gateway

```java
// Spring MVC — blocks thread until result available
@GetMapping("/health")
public ResponseEntity<ApiResponse<String>> health() {
    return ResponseEntity.ok(ApiResponse.success("OK"));
}

// Spring WebFlux — non-blocking
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain.filter(exchange); // returns immediately, continues asynchronously
}
```

**Why the gateway uses WebFlux:**
A gateway handles thousands of concurrent connections. If each connection
occupied a thread while waiting for upstream services, you would need
thousands of threads. WebFlux handles the same load with a small thread pool.

The other services use Spring MVC because they do real computation
(database queries, PDF processing, LLM calls) where the simpler programming
model is more valuable than non-blocking I/O.

---

**Q: How does the `JwtAuthenticationFilter` work in the gateway?**

The filter implements `GlobalFilter` — it intercepts every request passing
through the gateway:

```java
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();

        // 1. Check if path is public
        if (isPublicPath(path)) {
            return chain.filter(exchange); // skip JWT check
        }

        // 2. Extract Authorization header
        String authHeader = exchange.getRequest()
            .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }

        // 3. Validate JWT
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            return unauthorizedResponse(exchange, "Invalid or expired token");
        }

        // 4. Extract claims and inject headers
        String userId = jwtUtil.extractUserId(token);
        String email = jwtUtil.extractEmail(token);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
            .header("X-User-Id", userId)
            .header("X-User-Email", email)
            .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -1; // run before all other filters
    }
}
```

**Key points:**
- `Mono<Void>` is the WebFlux return type for operations that produce no value
- `chain.filter(exchange)` passes the request to the next filter in the chain
- `exchange.getRequest().mutate()` creates a new immutable request with
  added headers — the original request is not modified
- `getOrder()` returning -1 ensures JWT validation runs first

---

## Spring Security and JWT

---

**Q: How does JWT validation work in FinSight AI?**

JWT (JSON Web Token) validation in FinSight AI uses the JJWT library.
A JWT has three parts: header, payload, signature — separated by dots.

```
eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZGV3b2xlQGZpbnNpZ2h0LmNvbSIsInVzZXJJZCI6Ii4uLiJ9.signature
         header                              payload                          signature
```

**Token generation (auth-service):**
```java
public String generateAccessToken(String email, UUID userId, String role) {
    return Jwts.builder()
        .subject(email)
        .claims(Map.of("userId", userId.toString(), "role", role))
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + expiryMs))
        .signWith(getSigningKey())    // sign with HMAC-SHA384
        .compact();
}
```

**Token validation (api-gateway):**
```java
public boolean isTokenValid(String token) {
    try {
        Claims claims = Jwts.parser()
            .verifyWith(getSigningKey())   // verify signature
            .build()
            .parseSignedClaims(token)      // parse and verify expiry
            .getPayload();
        return !claims.getExpiration().before(new Date());
    } catch (Exception e) {
        return false;  // invalid signature, expired, malformed — all return false
    }
}
```

**The signing key:**
```java
private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(
        jwtSecret.getBytes(StandardCharsets.UTF_8)
    );
}
```

The same `JWT_SECRET` environment variable is shared across all services.
The gateway validates tokens signed by auth-service using this shared secret.
In a larger system, asymmetric keys (RS256) would be preferred — auth-service
keeps the private key, all other services use the public key for validation.

---

**Q: Why did you use HS384 instead of HS256 for JWT signing?**

Both are HMAC-based signing algorithms. The difference is the hash size:

| Algorithm | Hash | Security level |
|---|---|---|
| HS256 | SHA-256 | 256 bits |
| HS384 | SHA-384 | 384 bits |
| HS512 | SHA-512 | 512 bits |

JJWT 0.12.x automatically selects the strongest algorithm for the key size:
- 256-bit key → HS256
- 384-bit key → HS384
- 512-bit key → HS512

The JWT secret in FinSight AI is:
```
finsight_jwt_secret_key_must_be_at_least_32_bytes_long
```
This is 53 characters = 424 bits, which triggers HS384 automatically.

In practice, HS256 is considered secure for most applications.
The choice of HS384 is automatic based on key length, not a deliberate
security decision. For maximum compatibility, using a 32-byte key
to get HS256 is more predictable.

---

## JPA and Hibernate

---

**Q: What is the N+1 query problem and does FinSight AI have it?**

The N+1 problem occurs when fetching a list of N entities and then
executing 1 additional query per entity to fetch a relationship.

**Example in FinSight AI:**
```java
// Fetching all conversations and their messages
List<Conversation> conversations = conversationRepository.findAll();
// 1 query for conversations

for (Conversation c : conversations) {
    c.getMessages().size(); // 1 query per conversation — N queries total
}
// Total: N+1 queries
```

**Does FinSight AI have this problem?**

The `messages` relationship in `Conversation` is `FetchType.LAZY`:
```java
@OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL,
           fetch = FetchType.LAZY)
private List<Message> messages;
```

Lazy loading means messages are not fetched until accessed. In FinSight AI,
the `getMessages` endpoint fetches messages directly from `MessageRepository`
rather than through the `Conversation` entity — avoiding the N+1 problem:

```java
// Correct — single query
return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

// Would trigger N+1 if conversation list was involved
```

**Fixing N+1 when it occurs:**
```java
// JOIN FETCH in JPQL
@Query("SELECT c FROM Conversation c JOIN FETCH c.messages WHERE c.userId = :userId")
List<Conversation> findByUserIdWithMessages(@Param("userId") UUID userId);
```

---

**Q: Explain the `@GeneratedValue(strategy = GenerationType.UUID)` annotation.**

This tells JPA to generate a UUID primary key automatically using the database's
UUID generation capability.

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```

In PostgreSQL, this uses `gen_random_uuid()` — a cryptographically random UUID.

**Why UUID over auto-increment integers:**

1. **Security** — sequential IDs are predictable. An attacker who knows
   document ID `1001` might try `1002`, `1003`. UUIDs are not guessable.

2. **Distributed safety** — multiple services or instances can generate
   UUIDs without coordination. Integer sequences require a centralised counter.

3. **No enumeration** — `GET /documents/1, 2, 3...` does not work with UUIDs.
   Attackers cannot scan through resources.

4. **Merge-friendly** — importing data from multiple sources does not risk
   ID collisions.

**The tradeoff:**
UUID primary keys are larger (16 bytes vs 4 bytes for int) and index
performance is slightly worse for random UUIDs (fragmentation). For FinSight AI's
scale, this is negligible.

---

**Q: Why is `ddl-auto: none` used in document-service and conversation-service
but not everywhere?**

`ddl-auto` controls what Hibernate does with the database schema on startup:

| Value | Behavior |
|---|---|
| `validate` | Check entities match DB schema, fail if mismatch |
| `none` | Do nothing — trust external schema management |
| `update` | Alter tables to match entities (dangerous in production) |
| `create` | Drop and recreate all tables on startup (loses data) |

**Why `none` specifically in document-service:**
The `document_embeddings` table has a `vector(768)` column — a pgvector
custom type. Hibernate does not natively understand the `vector` type
and throws a validation error when `ddl-auto: validate` is used:

```
Schema-validation: wrong column type encountered in column [embedding]
found ["public"."vector" (Types#OTHER)], but expecting [vector(768) (Types#VARCHAR)]
```

The fix requires either:
a) A custom Hibernate type mapping for pgvector (requires hibernate-types library)
b) Using `ddl-auto: none` and trusting Flyway entirely

FinSight AI chose option b for simplicity. Flyway owns the schema entirely —
every table, column, and index is defined in versioned migration scripts.
Hibernate just reads and writes data.

**Auth-service uses `validate`** because its schema only uses standard
SQL types (UUID, VARCHAR, BOOLEAN, TIMESTAMP) that Hibernate understands.

---

## Database and Flyway

---

**Q: How does Flyway work and why is it better than `ddl-auto: update`?**

Flyway is a database migration tool that tracks and applies versioned SQL
scripts. Each migration has a version number and description:

```
V1__create_auth_tables.sql
V2__add_user_preferences.sql
V3__create_document_tables.sql
```

Flyway maintains a `flyway_schema_history` table:
```sql
| version | description              | installed_on | success |
|---------|--------------------------|--------------|---------|
| 1       | create auth tables       | 2026-05-01   | true    |
| 2       | create document tables   | 2026-05-01   | true    |
```

On each application startup, Flyway:
1. Checks which migrations have been applied
2. Applies any unapplied migrations in order
3. Fails startup if a migration fails

**Why Flyway over `ddl-auto: update`:**

`ddl-auto: update` is dangerous because:
- It can only ADD columns, not DROP or RENAME them
- It cannot change column types safely
- It has no version history — you cannot see what changed or when
- It cannot run custom SQL (data migrations, index creation, constraints)
- It behaves unpredictably with complex schema changes

Flyway gives you:
- Full control — any SQL you write is exactly what runs
- History — every change is recorded with timestamp and success status
- Rollback capability — write down migrations to undo changes
- Team collaboration — everyone's local database stays in sync
- Production safety — tested migrations, no surprises

In FinSight AI, the pgvector index creation and the `SET search_path` fix
required custom SQL that only Flyway could handle cleanly.

---

**Q: Explain the `search_path` issue with pgvector and how you solved it.**

When the document-service connects to PostgreSQL, the connection's default
`search_path` is set to `documents` (via `?currentSchema=documents`).

pgvector's `vector` type is installed in the `public` schema:
```sql
CREATE EXTENSION IF NOT EXISTS vector; -- installs in public schema
```

When Flyway runs `CREATE TABLE documents.document_embeddings (embedding vector(768))`,
PostgreSQL looks for the `vector` type in the search path. If `public` is not
in the search path, it fails:

```
ERROR: type "vector" does not exist
```

**The fix in `V2__create_embeddings_table.sql`:**
```sql
SET search_path TO documents, public;  -- include public so vector type is found

CREATE TABLE IF NOT EXISTS documents.document_embeddings (
    embedding vector(768),
    ...
);
```

**The fix in `application.yml` for runtime queries:**
```yaml
datasource:
  hikari:
    connection-init-sql: "SET search_path TO documents, public"
```

This runs on every new connection, ensuring native SQL queries (like the
vector INSERT) can also find the `vector` type.

The conversation-service also needs `public` in its search path for the
cross-schema RAG queries:
```yaml
connection-init-sql: "SET search_path TO conversations, documents, public"
```

---

## Async Processing

---

**Q: Explain `@Async` and how it works in FinSight AI.**

`@Async` tells Spring to run a method in a separate thread rather than
the caller's thread. The caller continues without waiting for the method
to complete.

```java
// In DocumentService — caller
public DocumentUploadResponse uploadDocument(MultipartFile file, UUID userId) {
    Document document = documentRepository.save(document);

    documentProcessingService.processDocument(document);  // returns immediately
    // Processing runs in background thread pool

    return DocumentUploadResponse.builder()
        .status("UPLOADED")
        .message("Processing has started.")
        .build();
    // HTTP response sent before processing completes
}

// In DocumentProcessingService — async method
@Async
@Transactional
public void processDocument(Document document) {
    // Runs in doc-processing thread pool
    // Takes 2-10 seconds
    pdfExtractorService.extractText(document.getFilePath());
    groqClient.extractInvoiceData(pdfText);
    embeddingService.embedDocument(document, pdfText);
    document.setStatus("COMPLETED");
    documentRepository.save(document);
}
```

**The thread pool configuration:**
```java
@Bean(name = "documentProcessingExecutor")
public Executor documentProcessingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);       // 2 threads always alive
    executor.setMaxPoolSize(5);        // scale up to 5 under load
    executor.setQueueCapacity(100);    // queue 100 requests before rejecting
    executor.setThreadNamePrefix("doc-processing-");
    executor.initialize();
    return executor;
}
```

**Enabling async:**
`@EnableAsync` on the `AsyncConfig` class activates Spring's async support.
Without this, `@Async` methods run synchronously.

**Common gotcha:**
`@Async` only works when called from a different Spring bean. Calling an
`@Async` method from within the same class bypasses Spring's proxy and
runs synchronously.

---

**Q: How does FinSight AI handle a document that fails processing?**

The `@Async` method has a try-catch that handles all failures:

```java
@Async
@Transactional
public void processDocument(Document document) {
    try {
        document.setStatus("PROCESSING");
        documentRepository.save(document);

        String pdfText = pdfExtractorService.extractText(document.getFilePath());
        String groqResponse = groqClient.extractInvoiceData(pdfText);
        saveExtractedInvoice(document, groqResponse);
        embeddingService.embedDocument(document, pdfText);

        document.setStatus("COMPLETED");
        document.setProcessedAt(LocalDateTime.now());
        documentRepository.save(document);

    } catch (Exception e) {
        log.error("Processing failed for document {}: {}", document.getId(), e.getMessage());
        document.setStatus("FAILED");
        document.setErrorMessage(e.getMessage());
        documentRepository.save(document);
        // No re-throw — failure is recorded, not propagated
    }
}
```

The user can then:
1. Poll `GET /documents/{id}/status` → sees `FAILED` + `errorMessage`
2. Understand what went wrong (empty PDF, Groq timeout, etc.)
3. In production: use a retry endpoint to re-process

**What the `@Async` `SimpleAsyncUncaughtExceptionHandler` logs:**
When an `@Async` method throws an uncaught exception, Spring logs it via
`SimpleAsyncUncaughtExceptionHandler`. In FinSight AI the try-catch prevents
this — exceptions are caught and stored as document status.

---

## REST API Design

---

**Q: Why does the upload endpoint return 202 Accepted instead of 201 Created?**

HTTP status codes carry semantic meaning:

- **201 Created** — the resource was created and is fully ready
- **202 Accepted** — the request was accepted but processing is not yet complete

For document upload:
```java
return ResponseEntity
    .status(HttpStatus.ACCEPTED)  // 202
    .body(ApiResponse.success("Document uploaded successfully", response));
```

The document record is created (the file is saved, the DB row exists) but
the extracted invoice data is not yet ready — processing takes 2-10 seconds.
Returning 202 signals to the client: "your request was accepted, poll for
completion status."

This is the correct semantic. 201 would imply the resource is fully available,
which is misleading when the extracted data does not exist yet.

**The polling pattern:**
```
POST /documents/upload → 202 Accepted + documentId
GET /documents/{id}/status → 200 OK + { status: "PROCESSING" }
GET /documents/{id}/status → 200 OK + { status: "COMPLETED" }
GET /documents/{id}/extracted → 200 OK + full invoice data
```

---

**Q: How does the `X-User-Id` header work between the gateway and services?**

The gateway extracts `userId` from the validated JWT and injects it as
a request header before forwarding to downstream services:

```java
// In JwtAuthenticationFilter (gateway)
String userId = jwtUtil.extractUserId(token);

ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
    .header("X-User-Id", userId)
    .header("X-User-Email", email)
    .build();

return chain.filter(exchange.mutate().request(mutatedRequest).build());
```

Downstream services read this header without any JWT processing:
```java
// In DocumentController (document-service)
@PostMapping("/upload")
public ResponseEntity<ApiResponse<DocumentUploadResponse>> uploadDocument(
        @RequestParam("file") MultipartFile file,
        @RequestHeader("X-User-Id") String userId) {

    DocumentUploadResponse response = documentService.uploadDocument(
            file, UUID.fromString(userId));
    ...
}
```

**Security note:**
The downstream services trust `X-User-Id` because it passes through the
gateway which validates the JWT first. In production, downstream service
ports are in a private network — only the gateway is publicly accessible.
A client cannot forge this header because all requests go through the gateway.

---

## Exception Handling

---

**Q: How does `GlobalExceptionHandler` work in FinSight AI?**

`@RestControllerAdvice` + `@ExceptionHandler` intercepts exceptions thrown
anywhere in the application and returns structured error responses:

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Validation errors from @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(400)
            .body(ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("Validation failed")
                .data(errors)
                .build());
    }

    // Business rule violations
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(ApiResponse.error(ex.getMessage()));
    }

    // Catch-all
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(500).body(ApiResponse.error("An unexpected error occurred"));
    }
}
```

**Why this pattern:**
Without `GlobalExceptionHandler`, Spring returns a default error page or
JSON with Spring's own format (`timestamp`, `status`, `error`, `path`).
With it, every error response uses our consistent `ApiResponse` wrapper.
Client code can always expect `{ success, message, data }` regardless of
what went wrong.

---

## Docker and Infrastructure

---

**Q: Explain the multi-stage Dockerfile used in FinSight AI.**

Multi-stage builds keep production images small by separating build tools
from runtime:

```dockerfile
# Stage 1 — Build (large image with Maven and full JDK)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy all POMs first — Docker caches this layer
# If source code changes but POMs don't, dependencies are not re-downloaded
COPY pom.xml .
COPY auth-service/pom.xml auth-service/
COPY document-service/pom.xml document-service/
COPY conversation-service/pom.xml conversation-service/
COPY api-gateway/pom.xml api-gateway/

RUN mvn dependency:go-offline -pl auth-service -am -q  # download deps

COPY auth-service/src auth-service/src  # copy source last
RUN mvn package -pl auth-service -am -DskipTests -q   # build JAR

# Stage 2 — Run (small image with only JRE)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Security: non-root user
RUN groupadd -r finsight && useradd -r -g finsight finsight
USER finsight

COPY --from=builder /app/auth-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Key optimizations:**
1. **Layer caching** — POMs copied before source. If only source changes,
   the `mvn dependency:go-offline` layer is cached — much faster rebuilds.
2. **Small runtime image** — the build stage (Maven + full JDK) is not
   included in the final image. Only the JRE is needed to run a JAR.
3. **Non-root user** — running as a non-root user is a security best practice.
   If the container is compromised, the attacker has limited privileges.

**Why `eclipse-temurin:17-jre-jammy` instead of alpine:**
The `-alpine` variant does not have ARM64 builds for Eclipse Temurin 17.
`-jammy` (Ubuntu 22.04) supports both AMD64 (CI/CD servers) and ARM64
(Apple Silicon MacBooks) correctly.

---

**Q: How does Docker Compose manage service startup order in FinSight AI?**

Services have dependencies using `depends_on` with health checks:

```yaml
auth-service:
  depends_on:
    postgres:
      condition: service_healthy  # wait until postgres health check passes
```

PostgreSQL health check:
```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U finsight -d finsight_db"]
    interval: 5s
    timeout: 5s
    retries: 10
```

**Startup order:**
```
1. postgres starts
2. postgres health check runs every 5s
3. Once postgres is healthy, auth/document/conversation start simultaneously
4. api-gateway starts after auth/document/conversation start
   (note: depends_on for gateway does not use health check — just waits
   for the containers to start, not for Spring Boot to be ready)
```

**The limitation:**
`depends_on` with `service_started` (the default for gateway) only waits
for the container to start, not for Spring Boot to finish initializing.
The gateway might start before the downstream services are ready.

`restart: on-failure` handles this — if the gateway starts before Spring
Boot is ready on the upstream services, it retries automatically.

In production, a proper service mesh or service discovery (Consul, Kubernetes)
handles this more gracefully than `depends_on`.

---

## AI Integration

---

**Q: How does the Groq API integration work at the HTTP level?**

FinSight AI uses Java's built-in `HttpClient` (Java 11+) rather than
a third-party HTTP library:

```java
private final HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build();

public String chat(List<Map<String, String>> messages) {
    // Build request body
    Map<String, Object> requestBody = Map.of(
        "model", "llama-3.3-70b-versatile",
        "temperature", 0.3,
        "max_tokens", 1000,
        "messages", messages
    );

    String requestJson = objectMapper.writeValueAsString(requestBody);

    // Build and send HTTP request
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
        .timeout(Duration.ofSeconds(60))
        .build();

    HttpResponse<String> response = httpClient.send(
        request, HttpResponse.BodyHandlers.ofString());

    // Parse response
    JsonNode responseJson = objectMapper.readTree(response.body());
    return responseJson
        .path("choices").path(0)
        .path("message").path("content")
        .asText();
}
```

**Why 60-second timeout:**
Groq is fast but LLM inference can occasionally be slow under high load.
A 60-second timeout prevents the thread from hanging indefinitely while
still catching genuine failures.

**The OpenAI-compatible format:**
Groq uses the same API format as OpenAI. The `messages` array, `model`,
`temperature`, and `max_tokens` fields are identical. This means switching
from Groq to OpenAI or Anthropic (with minor adjustments) requires changing
only the `baseUrl` and `apiKey` — not the request structure.

---

**Q: How does the chart spec parsing work in the conversation service?**

Groq is instructed to optionally include a chart spec after its text answer:

```
[text answer here]

```json
{
  "type": "pie",
  "title": "Spending by Category",
  "labels": [...],
  "datasets": [...]
}
```
```

The `parseResponse` method extracts both parts:

```java
private GroqResponse parseResponse(String rawResponse) {
    try {
        int jsonStart = rawResponse.indexOf("```json");
        int jsonEnd = rawResponse.lastIndexOf("```");

        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            String textPart = rawResponse.substring(0, jsonStart).trim();
            String jsonPart = rawResponse.substring(jsonStart + 7, jsonEnd).trim();

            // Validate it is actually a chart spec (not some other JSON)
            JsonNode json = objectMapper.readTree(jsonPart);
            if (json.has("type") && json.has("labels") && json.has("datasets")) {
                return GroqResponse.builder()
                    .textAnswer(textPart)
                    .chartSpec(jsonPart)
                    .build();
            }
        }
    } catch (Exception e) {
        log.warn("Could not parse chart spec: {}", e.getMessage());
    }

    // No chart spec — return plain text
    return GroqResponse.builder()
        .textAnswer(rawResponse)
        .chartSpec(null)
        .build();
}
```

**Why validate with `json.has("type")`:**
LLMs sometimes include JSON in their responses for other reasons (explaining
code, showing examples). Checking for the chart-specific fields prevents
accidentally treating non-chart JSON as a chart spec.

**The chartSpec is stored as JSONB:**
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "chart_spec", columnDefinition = "jsonb")
private String chartSpec;
```

JSONB is Postgres's binary JSON type — it validates JSON on insert and
enables efficient JSON querying. Storing as JSONB (not TEXT) means the
database guarantees the stored chart spec is always valid JSON.
