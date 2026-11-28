-- =====================================================================
-- V3: Virtual Card Freeze / Unfreeze state on Customer Wallets
-- =====================================================================

ALTER TABLE wallets
    ADD COLUMN IF NOT EXISTS card_frozen BOOLEAN NOT NULL DEFAULT FALSE;

