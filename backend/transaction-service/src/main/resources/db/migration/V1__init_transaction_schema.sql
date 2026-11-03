-- =============================================================================
-- V1__init_transaction_schema.sql
-- Vigilia: Resilient FinTech Fraud and Risk Detection Platform
-- Schema: transaction | Owner: Transaction Service
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- -----------------------------------------------------------------------------
-- Table: merchants
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS merchants (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_code           VARCHAR(50) UNIQUE NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    category                VARCHAR(50) NOT NULL,
    lat                     NUMERIC(9,6),
    lon                     NUMERIC(9,6),
    country                 VARCHAR(2),
    webhook_url             TEXT,
    webhook_secret_hash     TEXT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_merchants_code ON merchants(merchant_code);
CREATE INDEX IF NOT EXISTS idx_merchants_status ON merchants(status);

-- -----------------------------------------------------------------------------
-- Table: merchant_api_keys
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS merchant_api_keys (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    key_prefix          VARCHAR(12) NOT NULL,
    key_hash            TEXT NOT NULL UNIQUE,
    scopes              TEXT[],
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_merchant_api_keys_hash ON merchant_api_keys(key_hash);
CREATE INDEX IF NOT EXISTS idx_merchant_api_keys_merchant ON merchant_api_keys(merchant_id);

-- -----------------------------------------------------------------------------
-- Table: payment_sessions (Short-lived checkout session)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_sessions (
    id                      VARCHAR(30) PRIMARY KEY,
    merchant_id             UUID NOT NULL REFERENCES merchants(id),
    merchant_customer_ref   VARCHAR(100),
    customer_id             UUID,
    product_id              VARCHAR(100) NOT NULL,
    product_name            VARCHAR(200) NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    success_url             TEXT NOT NULL,
    cancel_url              TEXT NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_sessions_merchant ON payment_sessions(merchant_id);
CREATE INDEX IF NOT EXISTS idx_payment_sessions_customer ON payment_sessions(customer_id);
CREATE INDEX IF NOT EXISTS idx_payment_sessions_status ON payment_sessions(status);
CREATE INDEX IF NOT EXISTS idx_payment_sessions_expires ON payment_sessions(expires_at);

-- -----------------------------------------------------------------------------
-- Table: wallets
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wallets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID UNIQUE NOT NULL,
    account_number      VARCHAR(34) UNIQUE NOT NULL,
    currency            CHAR(3) NOT NULL,
    available_balance   NUMERIC(18,2) NOT NULL DEFAULT 0.00 CHECK (available_balance >= 0),
    held_balance        NUMERIC(18,2) NOT NULL DEFAULT 0.00 CHECK (held_balance >= 0),
    version             BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wallets_customer_id ON wallets(customer_id);
CREATE INDEX IF NOT EXISTS idx_wallets_account_number ON wallets(account_number);

-- -----------------------------------------------------------------------------
-- Table: transactions (Common parent record)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type    VARCHAR(20) NOT NULL,
    customer_id         UUID NOT NULL,
    amount              NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency            CHAR(3) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    idempotency_key     VARCHAR(100),
    ip_address          VARCHAR(45),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at        TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_txn_customer_idempotency UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_transactions_customer_created ON transactions(customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(transaction_type);

-- -----------------------------------------------------------------------------
-- Table: payments (PURCHASE detail)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    transaction_id          UUID PRIMARY KEY REFERENCES transactions(id) ON DELETE CASCADE,
    payment_session_id      VARCHAR(30) REFERENCES payment_sessions(id),
    merchant_id             UUID NOT NULL REFERENCES merchants(id),
    product_id              VARCHAR(100),
    product_name            VARCHAR(200),
    merchant_category       VARCHAR(50),
    card_brand              VARCHAR(20),
    card_last4              VARCHAR(4),
    customer_lat            NUMERIC(9,6),
    customer_lon            NUMERIC(9,6)
);

CREATE INDEX IF NOT EXISTS idx_payments_session ON payments(payment_session_id);
CREATE INDEX IF NOT EXISTS idx_payments_merchant ON payments(merchant_id);

-- -----------------------------------------------------------------------------
-- Table: transfers (TRANSFER detail)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transfers (
    transaction_id          UUID PRIMARY KEY REFERENCES transactions(id) ON DELETE CASCADE,
    sender_wallet_id        UUID NOT NULL REFERENCES wallets(id),
    recipient_wallet_id     UUID NOT NULL REFERENCES wallets(id),
    recipient_customer_id   UUID NOT NULL,
    memo                    VARCHAR(140)
);

CREATE INDEX IF NOT EXISTS idx_transfers_sender_wallet ON transfers(sender_wallet_id);
CREATE INDEX IF NOT EXISTS idx_transfers_recipient_wallet ON transfers(recipient_wallet_id);
CREATE INDEX IF NOT EXISTS idx_transfers_recipient_cust ON transfers(recipient_customer_id);

-- -----------------------------------------------------------------------------
-- Table: wallet_ledger (Append-only money ledger)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wallet_ledger (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id           UUID NOT NULL REFERENCES wallets(id),
    transaction_id      UUID REFERENCES transactions(id),
    entry_type          VARCHAR(30) NOT NULL,
    amount              NUMERIC(18,2) NOT NULL,
    balance_after       NUMERIC(18,2) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wallet_ledger_wallet ON wallet_ledger(wallet_id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_transaction ON wallet_ledger(transaction_id);

-- -----------------------------------------------------------------------------
-- Table: transaction_status_history (Append-only state machine audit)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_status_history (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    from_status         VARCHAR(30),
    to_status           VARCHAR(30) NOT NULL,
    reason              TEXT,
    actor_type          VARCHAR(20) NOT NULL,
    actor_id            VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_txn_status_history_txn ON transaction_status_history(transaction_id);

-- -----------------------------------------------------------------------------
-- Table: idempotency_keys
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency_keys (
    scope               VARCHAR(100) NOT NULL,
    key                 VARCHAR(100) NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,
    response_status     INTEGER NOT NULL,
    response_body       JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_keys(expires_at);

-- -----------------------------------------------------------------------------
-- Table: webhook_deliveries
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL REFERENCES merchants(id),
    payment_session_id  VARCHAR(30) REFERENCES payment_sessions(id),
    event_type          VARCHAR(50) NOT NULL,
    payload             JSONB NOT NULL,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_http_status    INTEGER,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_status_next ON webhook_deliveries(status, next_attempt_at);

-- -----------------------------------------------------------------------------
-- Table: outbox_events (Transactional Outbox Pattern - Section 16.9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type      VARCHAR(50) NOT NULL,
    aggregate_id        VARCHAR(100) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    event_version       INTEGER NOT NULL DEFAULT 1,
    payload             JSONB NOT NULL,
    trace_id            VARCHAR(100),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_txn_outbox_status_next ON outbox_events(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_txn_outbox_created_at ON outbox_events(created_at);

-- -----------------------------------------------------------------------------
-- Table: processed_events (Idempotent Consumer Deduplication - Section 16.9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_events (
    event_id            UUID PRIMARY KEY,
    event_type          VARCHAR(100) NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
