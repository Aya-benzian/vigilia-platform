-- =====================================================================
-- V2: Linked External Funding Cards (Bank Cards for Wallet Top-Up)
-- =====================================================================

CREATE TABLE IF NOT EXISTS linked_funding_cards (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL,
    card_brand      VARCHAR(32) NOT NULL,
    card_last4      VARCHAR(4) NOT NULL,
    cardholder_name VARCHAR(120),
    expiry_month    VARCHAR(2) NOT NULL,
    expiry_year     VARCHAR(4) NOT NULL,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_funding_card UNIQUE (customer_id, card_brand, card_last4)
);

CREATE INDEX IF NOT EXISTS idx_linked_funding_cards_customer
    ON linked_funding_cards (customer_id);

