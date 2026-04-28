CREATE TABLE IF NOT EXISTS documents.documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT,
    doc_type VARCHAR(50) NOT NULL DEFAULT 'INVOICE',
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    error_message TEXT,
    uploaded_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS documents.extracted_invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents.documents(id) ON DELETE CASCADE,
    vendor_name VARCHAR(255),
    invoice_number VARCHAR(100),
    issue_date DATE,
    due_date DATE,
    subtotal DECIMAL(15,2),
    tax DECIMAL(15,2),
    total DECIMAL(15,2),
    currency VARCHAR(10) DEFAULT 'NGN',
    category VARCHAR(100),
    raw_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS documents.invoice_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES documents.extracted_invoices(id) ON DELETE CASCADE,
    description TEXT,
    quantity DECIMAL(10,2),
    unit_price DECIMAL(15,2),
    amount DECIMAL(15,2),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_documents_user_id ON documents.documents(user_id);
CREATE INDEX idx_documents_status ON documents.documents(status);
CREATE INDEX idx_extracted_invoices_document_id ON documents.extracted_invoices(document_id);
CREATE INDEX idx_line_items_invoice_id ON documents.invoice_line_items(invoice_id);