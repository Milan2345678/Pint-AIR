CREATE TABLE submissions (
    id UUID PRIMARY KEY,
    shop_id UUID NOT NULL REFERENCES shops(id),
    customer_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX submissions_shop_created_idx ON submissions(shop_id, created_at DESC);

CREATE TABLE upload_intents (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id),
    shop_id UUID NOT NULL REFERENCES shops(id),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    declared_size BIGINT NOT NULL CHECK (declared_size > 0),
    object_key VARCHAR(512) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX upload_intents_expiry_idx ON upload_intents(expires_at);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id),
    upload_intent_id UUID NOT NULL UNIQUE REFERENCES upload_intents(id),
    shop_id UUID NOT NULL REFERENCES shops(id),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    source_object_key VARCHAR(512) NOT NULL UNIQUE,
    pdf_object_key VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX documents_shop_uploaded_idx ON documents(shop_id, uploaded_at DESC);
CREATE INDEX documents_expiry_idx ON documents(status, expires_at);

CREATE TABLE conversion_jobs (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL UNIQUE REFERENCES documents(id),
    state VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    locked_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX conversion_jobs_pending_idx ON conversion_jobs(state, created_at);
