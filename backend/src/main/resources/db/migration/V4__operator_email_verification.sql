CREATE TABLE shop_operator_email_verification_tokens (
    operator_id UUID PRIMARY KEY REFERENCES shop_operators(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX shop_operator_verification_expiry_idx
    ON shop_operator_email_verification_tokens(expires_at);
