-- =============================================================================
-- V1__init_customer_schema.sql
-- Vigilia: Resilient FinTech Fraud and Risk Detection Platform
-- Schema: customer | Owner: Customer Service
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- -----------------------------------------------------------------------------
-- Table: customers
-- Logical reference to auth.users (user_id)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID UNIQUE NOT NULL,
    full_name           VARCHAR(200) NOT NULL,
    date_of_birth       DATE NOT NULL,
    home_city           VARCHAR(100) NOT NULL,
    home_country        VARCHAR(2) NOT NULL,
    home_lat            NUMERIC(9,6) NOT NULL,
    home_lon            NUMERIC(9,6) NOT NULL,
    city_population     INTEGER NOT NULL,
    account_number      VARCHAR(34) UNIQUE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customers_user_id ON customers(user_id);
CREATE INDEX IF NOT EXISTS idx_customers_account_number ON customers(account_number);

-- -----------------------------------------------------------------------------
-- Table: customer_preferences
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_preferences (
    customer_id             UUID PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    email_notifications     BOOLEAN NOT NULL DEFAULT true,
    in_app_notifications    BOOLEAN NOT NULL DEFAULT true,
    language                VARCHAR(10) NOT NULL DEFAULT 'en',
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Table: beneficiaries
-- Saved recipients for peer-to-peer transfers
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS beneficiaries (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id                 UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    recipient_customer_id       UUID NOT NULL,
    recipient_account_number    VARCHAR(34) NOT NULL,
    nickname                    VARCHAR(100),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_beneficiary UNIQUE (customer_id, recipient_account_number)
);

CREATE INDEX IF NOT EXISTS idx_beneficiaries_customer_id ON beneficiaries(customer_id);

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

CREATE INDEX IF NOT EXISTS idx_cust_outbox_status_next ON outbox_events(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_cust_outbox_created_at ON outbox_events(created_at);

-- -----------------------------------------------------------------------------
-- Table: processed_events (Idempotent Consumer Deduplication - Section 16.9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_events (
    event_id            UUID PRIMARY KEY,
    event_type          VARCHAR(100) NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
