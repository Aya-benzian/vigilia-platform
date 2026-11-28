-- =============================================================================
-- V7__wallet_ledger_truncate_trigger.sql
-- Step 8: Add statement-level BEFORE TRUNCATE trigger on transaction.wallet_ledger.
--
-- V4 (bdb1cb8) already installed the row-level BEFORE UPDATE OR DELETE trigger
-- (trg_wallet_ledger_immutable). PostgreSQL row-level triggers do NOT fire on
-- TRUNCATE, so this migration adds a statement-level BEFORE TRUNCATE trigger to
-- close that gap without modifying the already-committed V4 migration.
-- =============================================================================

CREATE OR REPLACE FUNCTION transaction.wallet_ledger_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'wallet_ledger is append-only: TRUNCATE is prohibited'
    USING ERRCODE = 'restrict_violation';
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_wallet_ledger_no_truncate ON transaction.wallet_ledger;

CREATE TRIGGER trg_wallet_ledger_no_truncate
    BEFORE TRUNCATE
    ON transaction.wallet_ledger
    FOR EACH STATEMENT
    EXECUTE FUNCTION transaction.wallet_ledger_no_truncate();

COMMENT ON TABLE transaction.wallet_ledger IS
    'Append-only double-entry money ledger. UPDATE, DELETE and TRUNCATE are prohibited by triggers.';

