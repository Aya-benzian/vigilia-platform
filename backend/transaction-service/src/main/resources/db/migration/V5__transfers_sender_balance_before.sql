-- =============================================================================
-- V5__transfers_sender_balance_before.sql
-- Step 3: add sender_balance_before to captures the sender's available_balance
-- at the moment the wallet row was locked in TX-1 (BEFORE the HOLD entry is
-- applied). Used by the reconciler to rebuild the RiskEvaluationRequest from
-- stored data, without touching the live wallet.
--
-- Made NULLABLE because:
--   a) rows written before this migration have no stored value (backfill impossible
--      without the original live wallet snapshot), and
--   b) PURCHASE transactions do not use this column.
-- The reconciler tolerates NULL by falling back to null sender_balance in the
-- rebuilt RiskEvaluationRequest, matching pre-migration behaviour.
-- =============================================================================

ALTER TABLE transaction.transfers
    ADD COLUMN IF NOT EXISTS sender_balance_before NUMERIC(18,2);

COMMENT ON COLUMN transaction.transfers.sender_balance_before IS
    'Sender available_balance captured from the locked wallet row in TX-1, '
    'before the HOLD entry is applied. Used by the reconciler to replay the '
    'RiskEvaluationRequest without re-reading a potentially mutated wallet. '
    'NULL for rows created before V5 and for non-transfer transactions.';

