
-- =============================================================================
-- V1__init_risk_schema.sql
-- Vigilia: Resilient FinTech Fraud and Risk Detection Platform
-- Schema: risk | Owner: Risk Management Service (owns risk & review per ADR-09)
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- -----------------------------------------------------------------------------
-- Table: decision_policies (Configurable risk thresholds per transaction type)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS decision_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type        VARCHAR(20) NOT NULL,
    version                 INTEGER NOT NULL,
    reject_threshold        NUMERIC(6,5) NOT NULL,
    approve_threshold       NUMERIC(6,5) NOT NULL,
    uncertainty_threshold   NUMERIC(6,5) NOT NULL,
    ml_timeout_ms           INTEGER NOT NULL DEFAULT 1500,
    max_amount_hard_limit   NUMERIC(18,2) NOT NULL DEFAULT 5000.00,
    velocity_hard_cap_1h    INTEGER NOT NULL DEFAULT 15,
    cost_assumptions        JSONB,
    is_active               BOOLEAN NOT NULL DEFAULT false,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_threshold_order CHECK (approve_threshold < reject_threshold),
    CONSTRAINT uq_policy_type_version UNIQUE (transaction_type, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_policy_per_type
    ON decision_policies(transaction_type)
    WHERE is_active = true;

-- -----------------------------------------------------------------------------
-- Table: risk_evaluations (Append-only risk evaluation record per transaction)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_evaluations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id          UUID UNIQUE NOT NULL,
    transaction_type        VARCHAR(20) NOT NULL,
    customer_id             UUID,
    model_name              VARCHAR(100),
    model_version           VARCHAR(50),
    feature_spec_version    VARCHAR(50),
    features                JSONB,
    prediction_label        VARCHAR(50),
    fraud_probability       NUMERIC(6,5),
    uncertainty             NUMERIC(6,5),
    shap_values             JSONB,
    inference_ms            INTEGER,
    ml_status               VARCHAR(20) NOT NULL,
    policy_id               UUID REFERENCES decision_policies(id),
    decision                VARCHAR(30) NOT NULL,
    decision_reason         VARCHAR(50) NOT NULL,
    trace_id                VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_risk_eval_cust_created
    ON risk_evaluations(customer_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_risk_eval_model_version
    ON risk_evaluations(model_name, model_version);

CREATE INDEX IF NOT EXISTS idx_risk_eval_decision
    ON risk_evaluations(decision);

-- -----------------------------------------------------------------------------
-- Table: customer_activity (Projection for rolling behavioural features)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_activity (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             UUID NOT NULL,
    transaction_id          UUID UNIQUE NOT NULL,
    transaction_type        VARCHAR(20) NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL,
    counterparty_id         VARCHAR(100),
    category                VARCHAR(50),
    lat                     NUMERIC(9,6),
    lon                     NUMERIC(9,6),
    final_status            VARCHAR(30) NOT NULL,
    occurred_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cust_act_customer_time
    ON customer_activity(customer_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_cust_act_counterparty_time
    ON customer_activity(counterparty_id, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- Table: review_cases (Human review work items for PENDING_REVIEW transactions)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_cases (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id          UUID UNIQUE NOT NULL,
    risk_evaluation_id      UUID NOT NULL REFERENCES risk_evaluations(id),
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority                VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    assigned_analyst_id     UUID,
    sla_due_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    opened_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at              TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_review_cases_status_prio
    ON review_cases(status, priority, sla_due_at);

CREATE INDEX IF NOT EXISTS idx_review_cases_assigned
    ON review_cases(assigned_analyst_id);

-- -----------------------------------------------------------------------------
-- Table: review_decisions (Append-only final human decisions)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_decisions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id                 UUID UNIQUE NOT NULL REFERENCES review_cases(id) ON DELETE RESTRICT,
    analyst_id              UUID NOT NULL,
    decision                VARCHAR(20) NOT NULL,
    reason_code             VARCHAR(50) NOT NULL,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_decisions_analyst
    ON review_decisions(analyst_id);

-- -----------------------------------------------------------------------------
-- Table: review_notes (Investigation notes added by analysts)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_notes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id                 UUID NOT NULL REFERENCES review_cases(id) ON DELETE CASCADE,
    analyst_id              UUID NOT NULL,
    note                    TEXT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_notes_case
    ON review_notes(case_id);

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

CREATE INDEX IF NOT EXISTS idx_risk_outbox_status_next
    ON outbox_events(status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_risk_outbox_created_at
    ON outbox_events(created_at);

-- -----------------------------------------------------------------------------
-- Table: processed_events (Idempotent Consumer Deduplication - Section 16.9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_events (
    event_id            UUID PRIMARY KEY,
    event_type          VARCHAR(100) NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
