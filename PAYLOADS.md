# FinSight AI API Payloads

> 🏠 **Back to project documentation** → [README.md](./README.md)

> Base URL: `http://localhost:8080`
> All protected endpoints require: `Authorization: Bearer {accessToken}`
> Access tokens expire in **15 minutes** — login again if you get a 401.

---

## Table of Contents

- [Auth Endpoints](#auth-endpoints)
- [Document Endpoints](#document-endpoints)
- [Conversation Endpoints](#conversation-endpoints)
- [End-to-End Test Sequence](#end-to-end-test-sequence)
- [Common Error Responses](#common-error-responses)
- [Developer Tools](#developer-tools)

---

## Auth Endpoints

### Register
```
POST /api/auth/register
Access: Public
```
```json
{
  "email": "david@finsight.com",
  "password": "password123",
  "businessName": "Finsight Nigeria Ltd",
  "firstName": "David",
  "lastName": "Adewole"
}
```
**Response: 201 Created**
```json
{
  "success": true,
  "message": "Registration successful",
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "76482a6e-...",
    "tokenType": "Bearer",
    "email": "david@finsight.com",
    "businessName": "Finsight Nigeria Ltd",
    "role": "USER"
  }
}
```

---

### Login
```
POST /api/auth/login
Access: Public
```
```json
{
  "email": "david@finsight.com",
  "password": "password123"
}
```
**Response: 200 OK** — same structure as register response above.

---

### Health Check
```
GET /api/auth/health
Access: Public
```
**Response: 200 OK**
```json
{
  "success": true,
  "message": "Auth service is running",
  "data": "OK"
}
```

---

## Document Endpoints

### Upload Document
```
POST /api/documents/upload
Access: Authenticated
Content-Type: multipart/form-data
```

| Field | Type | Description |
|---|---|---|
| file | PDF file | Invoice or receipt PDF (max 20MB) |

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer {accessToken}" \
  -F "file=@invoice.pdf"
```

**Response: 202 Accepted**
```json
{
  "success": true,
  "message": "Document uploaded successfully",
  "data": {
    "documentId": "e4e94a0f-6555-4a03-bd8c-905314236763",
    "filename": "invoice.pdf",
    "status": "UPLOADED",
    "uploadedAt": "2026-05-01T20:17:15.159934",
    "message": "Document uploaded successfully. Processing has started."
  }
}
```

> Processing is asynchronous. Poll the status endpoint until `COMPLETED`.

---

### Get Document Status
```
GET /api/documents/{documentId}/status
Access: Authenticated
```

```bash
curl -X GET http://localhost:8080/api/documents/{documentId}/status \
  -H "Authorization: Bearer {accessToken}"
```

**Response: 200 OK**
```json
{
  "success": true,
  "message": "Document status retrieved",
  "data": {
    "documentId": "e4e94a0f-6555-4a03-bd8c-905314236763",
    "filename": "invoice.pdf",
    "status": "COMPLETED",
    "errorMessage": null,
    "uploadedAt": "2026-05-01T20:17:15.159934",
    "processedAt": "2026-05-01T20:17:19.153821"
  }
}
```

**Status values:**

| Status | Meaning |
|---|---|
| UPLOADED | File saved, processing queued |
| PROCESSING | PDFBox extraction + Groq AI running |
| COMPLETED | All data extracted and embedded |
| FAILED | Processing error — check errorMessage |

---

### Get Extracted Invoice
```
GET /api/documents/{documentId}/extracted
Access: Authenticated
```

```bash
curl -X GET http://localhost:8080/api/documents/{documentId}/extracted \
  -H "Authorization: Bearer {accessToken}"
```

**Response: 200 OK**
```json
{
  "success": true,
  "message": "Extracted invoice retrieved",
  "data": {
    "documentId": "e4e94a0f-6555-4a03-bd8c-905314236763",
    "vendorName": "Dangote Supplies Ltd",
    "invoiceNumber": "INV-2024-001",
    "issueDate": "2024-03-01",
    "dueDate": "2024-03-31",
    "subtotal": 205000.00,
    "tax": 15375.00,
    "total": 220375.00,
    "currency": "NGN",
    "category": "Office Supplies",
    "lineItems": [
      {
        "description": "Office Supplies",
        "quantity": 5.00,
        "unitPrice": 15000.00,
        "amount": 75000.00
      },
      {
        "description": "Laptop Stand",
        "quantity": 2.00,
        "unitPrice": 25000.00,
        "amount": 50000.00
      },
      {
        "description": "Printer Cartridges",
        "quantity": 10.00,
        "unitPrice": 8000.00,
        "amount": 80000.00
      }
    ]
  }
}
```

> Only available when status is `COMPLETED`. Returns 409 if still processing.

---

### Semantic Search
```
POST /api/documents/search
Access: Authenticated
```
```json
{
  "query": "office equipment and supplies",
  "limit": 5
}
```

**Response: 200 OK**
```json
{
  "success": true,
  "message": "Search completed",
  "data": {
    "query": "office equipment and supplies",
    "results": [
      {
        "documentId": "e4e94a0f-6555-4a03-bd8c-905314236763",
        "chunkText": "Office Supplies 5 NGN 15,000 NGN 75,000...",
        "similarity": 0.92
      }
    ]
  }
}
```

> Uses pgvector cosine similarity. Returns chunks semantically similar to the query even if exact keywords don't match.

---

## Conversation Endpoints

### Create Conversation
```
POST /api/conversations
Access: Authenticated
```
```json
{
  "title": "Q1 Spending Analysis"
}
```

**Response: 201 Created**
```json
{
  "success": true,
  "message": "Conversation created",
  "data": {
    "id": "50f625a5-0517-4860-83ea-2a6766cdc9c4",
    "title": "Q1 Spending Analysis",
    "createdAt": "2026-05-01T23:30:22.901",
    "updatedAt": "2026-05-01T23:30:22.901"
  }
}
```

---

### Send Message
```
POST /api/conversations/{conversationId}/messages
Access: Authenticated
```
```json
{
  "content": "What did I spend on office supplies?"
}
```

**Response: 200 OK — Plain text answer**
```json
{
  "success": true,
  "message": "Message sent",
  "data": {
    "id": "7cff4359-f606-42a9-b46f-836209a5b436",
    "role": "assistant",
    "content": "You spent NGN 205,000 on office supplies from Dangote Supplies Ltd on 2024-03-01, with tax of NGN 15,375 bringing the total to NGN 220,375.",
    "chartSpec": null,
    "createdAt": "2026-05-01T23:31:05.056845"
  }
}
```

**Response: 200 OK — Answer with chart specification**

When you ask for breakdowns, trends, or visualizations:
```json
{
  "content": "Show me a breakdown of my spending by category"
}
```

```json
{
  "success": true,
  "message": "Message sent",
  "data": {
    "id": "9fadda09-d001-4bb1-ac1e-8bc91d3c5dca",
    "role": "assistant",
    "content": "Based on your invoices, here is your spending breakdown by category:",
    "chartSpec": "{\"type\":\"pie\",\"title\":\"Spending Breakdown by Category\",\"labels\":[\"Office Supplies\",\"Laptop Stand\",\"Printer Cartridges\"],\"datasets\":[{\"label\":\"Amount (NGN)\",\"data\":[75000,50000,80000]}]}",
    "createdAt": "2026-05-08T10:38:30.056845"
  }
}
```

> `chartSpec` is a Chart.js compatible JSON string. Parse it and pass directly to Chart.js or Recharts to render a chart.

**Example questions that trigger charts:**
- "Show me my spending by category"
- "Give me a breakdown of expenses this month"
- "Compare my spending across vendors"
- "Show me a monthly trend of my invoices"

**Example questions that return plain text:**
- "What did I spend on logistics?"
- "Who is my top vendor?"
- "What is my total tax paid?"
- "When is my next invoice due?"

---

### Get Conversation Messages
```
GET /api/conversations/{conversationId}/messages
Access: Authenticated
```

```bash
curl -X GET http://localhost:8080/api/conversations/{conversationId}/messages \
  -H "Authorization: Bearer {accessToken}"
```

**Response: 200 OK**
```json
{
  "success": true,
  "message": "Messages retrieved",
  "data": [
    {
      "id": "uuid",
      "role": "user",
      "content": "What did I spend on office supplies?",
      "chartSpec": null,
      "createdAt": "2026-05-01T23:31:00.000000"
    },
    {
      "id": "uuid",
      "role": "assistant",
      "content": "You spent NGN 205,000 on office supplies...",
      "chartSpec": null,
      "createdAt": "2026-05-01T23:31:05.056845"
    }
  ]
}
```

---

### List All Conversations
```
GET /api/conversations
Access: Authenticated
```

```bash
curl -X GET http://localhost:8080/api/conversations \
  -H "Authorization: Bearer {accessToken}"
```

**Response: 200 OK**
```json
{
  "success": true,
  "message": "Conversations retrieved",
  "data": [
    {
      "id": "50f625a5-0517-4860-83ea-2a6766cdc9c4",
      "title": "Q1 Spending Analysis",
      "createdAt": "2026-05-01T23:30:22.901",
      "updatedAt": "2026-05-01T23:31:05.056"
    }
  ]
}
```

---

## End-to-End Test Sequence

Follow this order for a complete system test:

| Step | Action | Auth | Expected |
|---|---|---|---|
| 1 | Register business account | None | 201 — copy accessToken |
| 2 | Login | None | 200 — copy accessToken |
| 3 | Upload invoice PDF | accessToken | 202 — copy documentId |
| 4 | Poll document status | accessToken | 200 — wait for COMPLETED |
| 5 | Get extracted invoice | accessToken | 200 — vendor, totals, line items |
| 6 | Semantic search | accessToken | 200 — relevant chunks with similarity scores |
| 7 | Create conversation | accessToken | 201 — copy conversationId |
| 8 | Ask factual question | accessToken | 200 — text answer, chartSpec null |
| 9 | Ask for chart breakdown | accessToken | 200 — text answer + chartSpec JSON |
| 10 | Ask follow-up question | accessToken | 200 — answer uses conversation history |
| 11 | List all conversations | accessToken | 200 — list of conversations |
| 12 | Get message history | accessToken | 200 — full chat history |
| 13 | Upload second invoice | accessToken | 202 — different vendor |
| 14 | Ask cross-invoice question | accessToken | 200 — answer references both invoices |

---

## Common Error Responses

### 400 Bad Request — Validation Failed
```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "email": "Email must be valid",
    "password": "Password must be at least 8 characters"
  }
}
```

### 400 Bad Request — Business Rule
```json
{
  "success": false,
  "message": "Email already registered: david@finsight.com"
}
```

### 400 Bad Request — Wrong File Type
```json
{
  "success": false,
  "message": "Only PDF files are supported"
}
```

### 401 Unauthorized — Missing Token
```json
{
  "success": false,
  "message": "Missing or invalid Authorization header"
}
```

### 401 Unauthorized — Expired Token
```json
{
  "success": false,
  "message": "Invalid or expired token"
}
```

### 404 Not Found
```json
{
  "success": false,
  "message": "Document not found: e4e94a0f-6555-4a03-bd8c-905314236763"
}
```

### 409 Conflict — Not Yet Processed
```json
{
  "success": false,
  "message": "Invoice not yet extracted for document: e4e94a0f-..."
}
```

### 413 Payload Too Large
```json
{
  "success": false,
  "message": "File size exceeds the maximum allowed size of 20MB"
}
```

### 500 Internal Server Error
```json
{
  "success": false,
  "message": "An unexpected error occurred"
}
```

---

## Sample Questions for the Chatbot

**Factual queries (return text only):**
```
What did I spend on office supplies?
Who is my top vendor by total amount?
What is the total tax I have paid across all invoices?
What was my most recent invoice?
How much did I spend with Dangote Supplies Ltd?
What categories have I spent money on?
```

**Visualization queries (return text + chartSpec):**
```
Show me a breakdown of my spending by category
Give me a chart of my expenses by vendor
Show my spending trend over time
Compare my spending across different categories
Visualize my invoice totals by month
Show me a pie chart of my category breakdown
```

---

## Developer Tools

| Tool | URL | Notes |
|---|---|---|
| Auth Swagger | http://localhost:8081/swagger-ui.html | Test auth endpoints directly |
| Document Swagger | http://localhost:8082/swagger-ui.html | Test upload and extraction |
| Conversation Swagger | http://localhost:8083/swagger-ui.html | Test RAG chat |

> 🏠 **Back to project documentation** → [README.md](./README.md)
