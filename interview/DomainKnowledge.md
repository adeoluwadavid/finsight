# FinSight AI — Domain Knowledge Interview Guide

> This document covers business and domain-level interview questions
> related to FinSight AI, the Nigerian SME market, AI/LLM concepts,
> and the financial document intelligence industry.

---

## Table of Contents

- [Nigerian SME Context](#nigerian-sme-context)
- [Financial Documents and Invoices](#financial-documents-and-invoices)
- [AI and LLM Concepts](#ai-and-llm-concepts)
- [RAG Pattern](#rag-pattern)
- [Vector Embeddings](#vector-embeddings)
- [Document Intelligence](#document-intelligence)
- [Security and Data Privacy](#security-and-data-privacy)
- [Architecture Decisions](#architecture-decisions)

---

## Nigerian SME Context

---

**Q: What problem does FinSight AI solve for Nigerian SMEs specifically?**

Nigerian SMEs face a bookkeeping crisis. Most small businesses — trading
companies, logistics firms, professional services, retail shops — receive
invoices and receipts as paper documents or PDF scans. Their workflow is:

1. Receive invoice
2. Manually type data into Excel or a notebook
3. Lose track of spending categories
4. Discover cash flow issues only at month end

This manual process is slow, error-prone, and gives no real-time visibility
into spending. An accountant visit is expensive and infrequent.

FinSight AI automates the entire extraction pipeline:
- Upload a PDF → AI extracts all fields in seconds
- Spending is categorized automatically
- Any question about finances is answered instantly
- Charts visualize trends without any manual work

The target customer is a business owner who uploads their invoices and
receipts and wants to understand their spending without hiring a full-time
accountant.

---

**Q: Why did you focus on invoices and receipts rather than bank statements?**

Invoices and receipts represent the source of truth for business spending.
Bank statements show money movement but lack context — a debit of NGN 220,375
tells you nothing about what was purchased, from whom, or for what category.

Invoices contain rich structured data:
- Vendor name and details
- Individual line items with descriptions
- Quantity, unit price, amounts
- Tax breakdown
- Payment terms and due dates
- Expense category (Technology, Logistics, Office Supplies, etc.)

This richness is what enables meaningful insights. A bank statement integration
can be added later to reconcile payments against invoices — the two complement
each other.

---

**Q: How does FinSight AI handle the fact that many Nigerian SMEs receive
scanned invoices rather than digital PDFs?**

This is a real limitation of the current implementation. FinSight AI uses
Apache PDFBox which excels at text-based PDFs — documents where the text
is digitally embedded. For scanned PDFs (photographs of paper documents),
PDFBox extracts very little or no text.

The solution for scanned documents is OCR (Optical Character Recognition).
The roadmap includes:

1. **Tesseract OCR** — free, open source, runs locally. When PDFBox
   extracts less than 50 characters, fall back to Tesseract. Works well
   for clean scans.

2. **Vision-capable LLM** — convert PDF pages to images and send directly
   to a multimodal model. More accurate for complex layouts but higher API cost.

In interviews, acknowledging this limitation and explaining the solution
demonstrates mature engineering thinking — knowing what your system does well
and what it does not.

---

**Q: What categories does FinSight AI use for expense classification?**

The Groq extraction prompt instructs the model to classify each invoice into
one of eight categories:

- **Technology** — software licenses, hardware, IT services
- **Marketing** — advertising, design, promotional materials
- **Logistics** — transportation, delivery, courier services
- **Office Supplies** — stationery, furniture, equipment
- **Utilities** — electricity, water, internet, phone
- **Professional Services** — legal, accounting, consulting
- **Food & Beverage** — meals, entertainment, catering
- **Other** — anything that does not fit the above

This classification happens automatically — the LLM reads the invoice
content and assigns the most appropriate category. The business owner
never needs to manually categorize anything.

In production, these categories would be configurable per user — a
construction company has different category needs than a restaurant.

---

**Q: How does FinSight AI help with cash flow management?**

Cash flow problems kill Nigerian SMEs. The common scenario is:
a business has NGN 2M in outstanding invoices but only NGN 150,000
in the bank because payments are delayed.

FinSight AI addresses this by:

1. **Due date tracking** — every invoice's due date is extracted.
   The system can warn when payments are approaching or overdue.

2. **Spending trends** — asking "show me my spending by month" reveals
   whether costs are growing faster than expected.

3. **Vendor concentration** — "who are my top 5 vendors by spend?" helps
   identify where negotiation leverage exists.

4. **Category budgets** — "how much have I spent on logistics this quarter?"
   enables comparison against budget.

5. **Tax visibility** — total tax paid across all invoices is instantly
   queryable — useful for FIRS reporting.

The AI answer is always grounded in the user's actual uploaded invoices —
it cannot hallucinate numbers because the context is retrieved from real data.

---

## Financial Documents and Invoices

---

**Q: What fields does FinSight AI extract from an invoice and why each one?**

| Field | Why it matters |
|---|---|
| vendorName | Who you paid — for vendor analysis and reconciliation |
| invoiceNumber | Unique identifier — prevents duplicate processing |
| issueDate | When the invoice was created — for period analysis |
| dueDate | When payment is due — for cash flow forecasting |
| subtotal | Amount before tax — for net spending analysis |
| tax | Tax component — for FIRS reporting and tax reclaim |
| total | Final amount — the actual cash outflow |
| currency | NGN vs USD vs GBP — for forex tracking |
| category | Expense type — for budget tracking by category |
| lineItems | Individual items — enables granular spend analysis |

The `lineItems` array is the most valuable field for detailed analysis.
It tells you not just that you spent NGN 205,000 with a vendor, but that
NGN 75,000 was office supplies, NGN 50,000 was a laptop stand, and
NGN 80,000 was printer cartridges.

---

**Q: What is the difference between an invoice and a receipt?**

**Invoice:**
- Issued by the seller to the buyer before or at time of delivery
- Requests payment — it is a billing document
- May have payment terms (e.g., "due in 30 days")
- Includes quantities, unit prices, tax breakdown
- Used for accounts payable tracking

**Receipt:**
- Issued after payment has been made
- Confirms payment — it is proof of purchase
- Usually simpler format — total paid, date, vendor
- Used for expense reimbursement and tax records

FinSight AI handles both. The extraction prompt is generic enough to work
on both document types. For receipts, fields like `invoiceNumber` and
`dueDate` are typically null — the system handles this gracefully.

---

**Q: How does FinSight AI handle invoices in different currencies?**

The extraction prompt instructs Groq to identify the currency from the
invoice content. Supported currencies: NGN, USD, GBP, EUR.

The `currency` field is stored on each extracted invoice. When the
conversation service queries invoice data for RAG context, the currency
is included in the context:

```
Vendor: Dangote Supplies Ltd | Date: 2024-03-01 | Total: 220375 NGN
Vendor: Amazon Web Services | Date: 2024-03-15 | Total: 450 USD
```

Groq then answers correctly: "You paid NGN 220,375 to Dangote Supplies
and USD 450 to AWS in March."

Cross-currency total calculations are not performed automatically —
the system presents each currency separately. An exchange rate service
would be required for accurate NGN equivalents, which is a planned
enhancement.

---

## AI and LLM Concepts

---

**Q: What is a Large Language Model and how does FinSight AI use one?**

A Large Language Model (LLM) is a neural network trained on vast amounts
of text that can understand and generate human language. It learns statistical
patterns across text, enabling it to answer questions, summarize documents,
extract structured data, and reason about information.

FinSight AI uses Groq (running Llama 3.3 70B) in two distinct ways:

**1. Structured extraction (document-service):**
```
Input: raw text extracted from PDF
Task: parse this into a specific JSON schema
Output: { vendorName, invoiceNumber, total, lineItems... }
```
Temperature is set to 0.1 — low temperature means deterministic,
consistent extraction. We do not want creativity here.

**2. RAG chat (conversation-service):**
```
Input: user question + retrieved invoice context
Task: answer the question accurately using the provided data
Output: natural language answer + optional chart specification
```
Temperature is 0.3 — slightly higher to allow natural-sounding answers
while still staying grounded in the facts.

---

**Q: What is prompt engineering and how did you apply it in FinSight AI?**

Prompt engineering is the practice of designing the text instructions
sent to an LLM to reliably produce the desired output format and quality.

In FinSight AI, two key prompt engineering techniques are used:

**1. JSON schema prompting (extraction):**
```
"Extract invoice data and return ONLY a valid JSON object
with no additional text, explanation, or markdown."
```
The prompt specifies the exact JSON structure expected, including
field names, types, and allowed values. This prevents the model
from adding preambles, explanations, or wrapping the JSON in markdown.

The response is then cleaned with:
```java
cleanJson = groqResponse
    .replaceAll("```json", "")
    .replaceAll("```", "")
    .trim();
```
This handles the common case where the model adds markdown code fences
despite instructions not to.

**2. Conditional chart generation (conversation):**
```
"When the user asks for spending breakdowns, comparisons, or
visualizations, include a chart specification after your text answer."
```
The prompt teaches the model when to include a chart and exactly what
format to use. The system then parses the response to separate the text
answer from the JSON chart spec.

---

**Q: What is hallucination in LLMs and how does FinSight AI prevent it?**

Hallucination is when an LLM generates text that sounds plausible but
is factually incorrect or completely fabricated. For example, if asked
"what did I spend on logistics?" without any relevant data, a hallucinating
model might invent a plausible-sounding number.

This is catastrophic in a financial application — users make real business
decisions based on this data.

FinSight AI prevents hallucination through the RAG pattern:

1. The user's question is never sent to the LLM alone
2. Relevant invoice data is always retrieved first (vector search + SQL)
3. The system prompt explicitly states:
   "Answer questions based ONLY on the provided financial data context.
   If the data doesn't contain enough information to answer, say so clearly."
4. The context includes actual vendor names, dates, and amounts from the database

The LLM becomes an interpreter of the user's actual data, not a generator
of invented answers. If no relevant data exists, it says so honestly.

---

**Q: What is the difference between Groq and GPT-4?**

| Aspect | Groq | OpenAI GPT-4 |
|---|---|---|
| What it is | Inference provider | Model creator + provider |
| Models available | Llama 3.3, Mixtral, Gemma (open source) | GPT-4, GPT-4o (proprietary) |
| Speed | Extremely fast (custom LPU hardware) | Standard GPU inference |
| Cost | Free tier generous | Pay per token from day one |
| Privacy | Dependent on Groq's terms | Dependent on OpenAI's terms |
| API format | OpenAI-compatible | OpenAI native |

**Key insight:** Groq does not create models. They run open-source models
on custom hardware called LPUs (Language Processing Units) that are
optimized for LLM inference. The speed is genuinely impressive — responses
stream almost instantly.

FinSight AI uses Groq because:
- The free tier is sufficient for development and demos
- The OpenAI-compatible API format means switching providers is a one-line change
- Latency makes the demo feel like ChatGPT

In production, the LLM provider would be chosen based on cost, compliance,
and data residency requirements. Nigerian data regulations may require
on-premise or regional hosting.

---

**Q: What is a token in the context of LLMs?**

A token is the basic unit that LLMs process — roughly 3-4 characters or
about 0.75 words. "Hello world" is approximately 3 tokens.

Tokens matter in FinSight AI for two reasons:

**1. Context window limits:**
The conversation service sends up to 5 retrieved chunks plus recent
conversation history plus the user's question. If a user has hundreds of
invoices, the assembled context could exceed the model's context window.
The current implementation limits context to 5 chunks and 6 history messages
as a practical constraint.

**2. API costs:**
Groq and other providers charge per token (input + output). The extraction
prompt for a typical invoice uses approximately 800-1200 input tokens.
At scale, prompt efficiency becomes a cost optimization target.

---

## RAG Pattern

---

**Q: What is RAG and why did you use it instead of fine-tuning?**

RAG (Retrieval-Augmented Generation) is a pattern where relevant documents
are retrieved from a database and provided as context to an LLM before it
generates its answer. The LLM reasons over the retrieved documents rather
than relying solely on its training data.

**Fine-tuning** trains the model on your specific data. The model learns
facts about your business and can answer questions from memory.

**Why RAG over fine-tuning for FinSight AI:**

| Aspect | RAG | Fine-tuning |
|---|---|---|
| Data freshness | Retrieves up-to-date data on every query | Requires retraining when data changes |
| Cost | Cheap — just API calls | Expensive — GPU training costs |
| Accuracy | Grounded in retrieved facts | Can hallucinate memorized facts |
| Transparency | Can show which documents informed the answer | Answer source is opaque |
| Setup time | Hours | Days to weeks |
| User data | Each user's data is separate | All users' data mixed in training |

For FinSight AI, invoices change daily — a user uploads new documents
constantly. Fine-tuning would need to run every time new invoices arrive.
RAG retrieves the latest data on every query automatically.

---

**Q: Walk me through exactly how the RAG pipeline works in FinSight AI.**

When a user sends a message, the conversation service executes this pipeline:

**Step 1 — Embed the question:**
```
User: "What did I spend on office supplies?"
→ Jina AI converts this to a 768-dimension vector
→ [0.23, -0.11, 0.87, 0.04, ...] (768 numbers)
```

**Step 2 — Vector similarity search:**
```sql
SELECT de.chunk_text, ei.vendor_name, ei.total, ei.category,
       1 - (de.embedding <=> CAST(? AS vector)) AS similarity
FROM documents.document_embeddings de
JOIN documents.documents d ON d.id = de.document_id
JOIN documents.extracted_invoices ei ON ei.document_id = d.id
WHERE d.user_id = CAST(? AS uuid)
ORDER BY de.embedding <=> CAST(? AS vector)
LIMIT 5
```
The `<=>` operator computes cosine distance. The 5 most semantically
similar invoice chunks are returned with their similarity scores.

**Step 3 — Structured SQL query:**
```sql
SELECT ei.vendor_name, ei.issue_date, ei.total, ei.currency, ei.category
FROM documents.extracted_invoices ei
JOIN documents.documents d ON d.id = ei.document_id
WHERE d.user_id = CAST(? AS uuid)
ORDER BY ei.issue_date DESC LIMIT 20
```
The 20 most recent invoices are retrieved for structured context.

**Step 4 — Context assembly:**
```
=== INVOICE SUMMARY ===
Vendor: Dangote Supplies Ltd | Date: 2024-03-01 | Total: 220375 NGN | Category: Office Supplies

=== RELEVANT DOCUMENT EXCERPTS ===
Office Supplies 5 NGN 15,000 NGN 75,000 Laptop Stand 2 NGN 25,000...
```

**Step 5 — Groq call:**
System prompt = financial assistant instructions + context block
User message = "What did I spend on office supplies?"

**Step 6 — Parse response:**
Extract text answer and optional chart specification.

---

**Q: What is the difference between vector search and SQL search in FinSight AI?**

Both are used together in the RAG pipeline — they complement each other.

**Vector search (pgvector):**
```
Query: "what did I spend on office equipment?"
Finds: chunks about "Laptop Stand", "Printer Cartridges", "Office Supplies"
       even though "equipment" does not appear in those chunks
```
Vector search finds semantic matches — documents with similar meaning.
It is fuzzy by nature and works at the text chunk level.

**SQL search:**
```sql
SELECT vendor_name, total, category
WHERE user_id = ? ORDER BY issue_date DESC
```
SQL search finds exact structured matches. It is precise and works at
the structured field level (vendor, amount, date, category).

**Why both together:**
- Vector search might find "the invoice talks about printing supplies"
- SQL gives you "the exact total was NGN 220,375 on 2024-03-01"

Vector search provides relevance, SQL provides precision. The combination
is called hybrid retrieval — a production RAG technique more powerful than
either alone.

---

**Q: Why did you choose Jina AI for embeddings instead of OpenAI embeddings?**

| Aspect | Jina AI | OpenAI text-embedding-3-small |
|---|---|---|
| Free tier | Generous — suitable for development | Limited free usage |
| Dimensions | 768 | 1536 |
| API format | Simple REST | OpenAI SDK |
| Performance | Good for English and multilingual | Excellent |
| Cost at scale | Competitive | Per-token pricing |

The primary reason is practical: Jina AI has a free tier that works well
for a portfolio project. The API is a simple REST call — easy to integrate
from Java without an SDK dependency.

The embedding model (`jina-embeddings-v2-base-en`) produces 768-dimension
vectors which are stored in the pgvector `vector(768)` column. This
dimension was chosen to match the model's native output.

In production, the embedding provider and model would be evaluated based on
retrieval quality benchmarks for financial text. The architecture makes
switching providers a one-class change — only `EmbeddingClient.java` needs
updating.

---

## Vector Embeddings

---

**Q: What is a vector embedding and why does it enable semantic search?**

A vector embedding is a list of numbers (a vector) that represents the
semantic meaning of a piece of text. The key property is:

**Similar meanings → similar vectors (close in vector space)**
**Different meanings → different vectors (far in vector space)**

For example:
```
"office supplies"    → [0.23, -0.11, 0.87, ...]
"stationery items"   → [0.24, -0.10, 0.86, ...]  ← very close
"truck delivery"     → [0.51,  0.33, 0.12, ...]  ← far away
```

When a user asks "what did I spend on office equipment?", we embed that
question and find the invoice chunks whose vectors are closest to the
question vector. This finds relevant content even when the exact words
do not match — semantic search.

Traditional keyword search would miss "Laptop Stand" if you search for
"equipment". Vector search finds it because the embedding model has learned
that laptop stands are office equipment.

---

**Q: What is cosine similarity and why is it used for text search?**

Cosine similarity measures the angle between two vectors in high-dimensional
space. It ranges from -1 (opposite) to 1 (identical).

```
cosine_similarity(A, B) = (A · B) / (|A| × |B|)
```

For text embeddings, cosine similarity is preferred over Euclidean distance
because:

1. **Length invariance** — a short chunk about "office supplies" and a long
   invoice about "office supplies" should be considered similar even if their
   vector magnitudes differ. Cosine similarity normalises for length.

2. **Better semantic discrimination** — in practice, cosine similarity
   outperforms Euclidean distance for text retrieval tasks.

In pgvector, the `<=>` operator computes cosine distance (1 - cosine similarity):
```sql
ORDER BY de.embedding <=> CAST(? AS vector)
LIMIT 5
```
Lower distance = higher similarity. We order ascending to get the most
similar chunks first.

---

**Q: What is an IVFFlat index and why did you use it for pgvector?**

IVFFlat (Inverted File with Flat compression) is an approximate nearest
neighbour index. It trades a small amount of recall accuracy for much
faster search.

Without an index, finding the closest vector requires comparing the query
vector against every stored vector — O(n) brute force.

With IVFFlat:
```sql
CREATE INDEX idx_document_embeddings_embedding
ON documents.document_embeddings
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
```

The index divides vectors into 100 clusters (lists) during build time.
At query time, only the most likely clusters are searched — much faster
than checking all vectors.

**The tradeoff:** IVFFlat might miss the absolute closest vector because
it skips some clusters. For invoice retrieval, this is acceptable — if
the result is semantically very close it is good enough. We do not need
mathematically perfect nearest neighbours.

For a small number of documents (< 10,000), the index provides minimal
benefit — exact search is fast enough. At larger scale, the index becomes
essential.

---

## Document Intelligence

---

**Q: What is Apache PDFBox and what are its limitations?**

Apache PDFBox is an open source Java library for working with PDF documents.
FinSight AI uses it to extract raw text from uploaded invoices:

```java
try (PDDocument document = Loader.loadPDF(pdfFile)) {
    PDFTextStripper stripper = new PDFTextStripper();
    String text = stripper.getText(document);
}
```

**What PDFBox does well:**
- Extracts text from digitally created PDFs
- Handles multi-page documents
- Fast — extracts text in milliseconds
- No API cost

**PDFBox limitations:**
- Cannot handle scanned PDFs (images) — needs OCR for those
- Layout-dependent text ordering — table columns may be jumbled
- No understanding of document structure — just raw text string
- Complex layouts (multi-column) may produce confusing output

**Why this still works for FinSight AI:**
Even with jumbled table formatting, Groq can extract the correct values.
The LLM is good at finding patterns in unstructured text. A jumbled
extraction like "Office Supplies 5 NGN 15,000 NGN 75,000 Laptop Stand
2 NGN 25,000" is still interpretable by Groq.

---

**Q: Why does FinSight AI process documents asynchronously?**

PDF processing takes 2-10 seconds:
- PDFBox extraction: ~100ms
- Groq API call: ~1-3 seconds
- Jina AI embeddings: ~1-2 seconds
- Database writes: ~100ms

If this ran synchronously, the HTTP request would hang for 5-10 seconds.
This is a poor user experience and risks HTTP timeouts on the client side.

The async approach:
```java
@Async
@Transactional
public void processDocument(Document document) {
    // runs in a separate thread pool
}
```

The upload endpoint returns immediately with status `UPLOADED`.
The client polls `GET /documents/{id}/status` until `COMPLETED`.

This pattern also enables:
- **Resilience** — if Groq is temporarily slow, the upload still succeeds
- **Scalability** — the thread pool size controls concurrency independently
- **Observability** — status tracking makes it easy to debug failures

The thread pool is named `doc-processing-*` and is configurable:
```java
executor.setCorePoolSize(2);
executor.setMaxPoolSize(5);
executor.setQueueCapacity(100);
```

---

## Security and Data Privacy

---

**Q: How does FinSight AI ensure one user cannot access another user's documents?**

Every data access path is filtered by `userId`. This happens at three levels:

**1. Gateway level:**
The JWT contains the `userId` claim. The gateway validates the token and
injects `X-User-Id` header. Clients cannot forge this header — it is set
by the gateway from a cryptographically signed token.

**2. Service level:**
Every query includes `userId` as a filter:
```java
// DocumentService
documentRepository.findByIdAndUserId(documentId, userId)
    .orElseThrow(() -> new IllegalArgumentException("Document not found"));
```
If the document exists but belongs to another user, the query returns empty
and throws a 400. The attacker learns only that the document does not exist
(they cannot distinguish "not found" from "found but not yours").

**3. Database level:**
The vector similarity search and invoice queries both filter by `user_id`:
```sql
WHERE d.user_id = CAST(? AS uuid)
```
Even if a bug existed in the service layer, the SQL itself would not return
another user's data.

---

**Q: What are the data privacy implications of sending invoice data to an LLM?**

This is an important question for any AI-powered application.

**What FinSight AI sends to Groq:**
- Raw text extracted from uploaded PDFs (for extraction)
- Invoice summaries as context (for RAG chat)
- This may include: vendor names, amounts, dates, business names

**The privacy risk:**
The LLM provider (Groq) receives your users' financial data. Their terms
of service and data processing agreements determine what they do with it.

**Current FinSight AI approach:**
This is acceptable for a development/portfolio project. For production:

1. **Data Processing Agreement** — sign a DPA with the LLM provider
2. **Data minimisation** — send only the fields needed for the task,
   not the full raw text
3. **On-premise LLMs** — run Llama 3 locally via Ollama for maximum
   privacy (no data leaves your infrastructure)
4. **Nigerian data residency** — Nigerian businesses may have obligations
   under NDPR (Nigeria Data Protection Regulation) to keep data in Nigeria

The architecture supports switching to a local LLM by changing only
the `GroqClient` class — the rest of the system is LLM-provider agnostic.

---

## Architecture Decisions

---

**Q: Why did you build FinSight AI as microservices when NairaCore was a monolith?**

The microservices choice was deliberate and based on the nature of the services:

**Document-service has very different scaling needs:**
PDF processing is CPU-intensive and slow (2-10 seconds per document).
As upload volume grows, you want to scale document processing independently
without scaling auth or conversation services.

**Conversation-service has different latency needs:**
Chat responses need to feel fast. If conversation-service shared resources
with a CPU-intensive PDF processing operation, response times would suffer.

**Auth-service has different availability needs:**
Auth must be highly available — if it goes down, nothing works. Running it
independently means it can be deployed and updated without affecting the
document or conversation services.

**The practical benefit for the portfolio:**
Building microservices demonstrates skills that a modular monolith cannot:
- Spring Cloud Gateway (routing, JWT validation at the edge)
- Service-to-service isolation (each service owns its schema)
- Docker Compose orchestration of multiple services
- Profile-based configuration for different environments

The combination of NairaCore (monolith) and FinSight AI (microservices)
shows you understand both approaches and can make the right tradeoff.

---

**Q: Why did you use a single Postgres database for all four services instead
of a separate database per service?**

The strict microservices doctrine says each service should have its own
database. FinSight AI uses a pragmatic middle ground: one database with
separate schemas per service.

**Why not separate databases:**
1. **Cost** — four separate PostgreSQL instances are significantly more expensive
2. **Complexity** — four connection strings, four backup jobs, four migration pipelines
3. **Portfolio context** — demonstrating the schema separation pattern is sufficient
   to show you understand the concept without the operational overhead
4. **pgvector** — the extension only needs to be installed once, not four times

**How isolation is maintained:**
- Each service connects with `?currentSchema=<schema>` — it cannot accidentally
  query another service's tables via normal JPA queries
- The conversation service is the only exception — it uses `JdbcTemplate` with
  fully qualified schema names (`documents.extracted_invoices`) to perform
  cross-schema RAG retrieval. This is a deliberate architectural decision
  (the conversation service needs document data for RAG)
- Flyway migrations run independently per service against their own schema

**The path to full isolation:**
Moving to separate databases requires only changing connection strings and
ensuring pgvector is installed on the document service's database. No code changes.

---

**Q: Why did you not use Spring AI for the LLM integration?**

Spring AI is the official Spring framework library for AI integration.
It provides abstractions over LLM providers, vector stores, and embedding
models — including pgvector support with proper Hibernate type mapping.

FinSight AI uses direct HTTP calls (Java's `HttpClient`) instead.

**Why direct HTTP was chosen:**
1. **Learning value** — building the clients manually teaches exactly what
   Spring AI abstracts away. In interviews, you can explain every line of the
   integration code.
2. **Control** — the exact JSON structure sent to Groq is visible and
   configurable without framework magic.
3. **Portfolio clarity** — interviewers can see you understand REST API
   integration, not just Spring AI annotations.

**What Spring AI would have solved better:**
1. **pgvector Hibernate mapping** — Spring AI's `VectorStore` abstraction
   handles the `vector` type natively, avoiding the custom converter and
   native SQL workaround we had to implement.
2. **Embedding model abstraction** — switching from Jina to OpenAI embeddings
   is a one-line config change with Spring AI.
3. **Less boilerplate** — the `GroqClient` and `EmbeddingClient` classes
   would be replaced by Spring AI auto-configuration.

In production, Spring AI would be the right choice. For learning and
demonstrating technical understanding, direct integration is more instructive.
