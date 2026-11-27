-- =============================================================================
-- V4__wallet_ledger_append_only.sql
-- D1 fix: Enforce append-only semantics on the wallet_ledger table.
--
-- A PostgreSQL trigger rejects any UPDATE or DELETE on wallet_ledger rows,
-- regardless of the caller's role (including the table owner).  This provides
-- a database-level guarantee that the financial audit trail cannot be silently
-- tampered with by application bugs or a compromised service account.
--
-- Additionally, a dedicated application role (vigilia_app) is granted only
-- INSERT and SELECT on wallet_ledger so that the application never holds the
-- DELETE/UPDATE privilege in production.  The migration role (vigilia) retains
-- DDL privileges needed for future schema migrations.
-- =============================================================================

-- 1. Trigger function: raise an exception on any attempt to mutate an existing row
CREATE OR REPLACE FUNCTION transaction.wallet_ledger_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'wallet_ledger is append-only: UPDATE and DELETE are prohibited (entry id=%, wallet_id=%)',
        OLD.id, OLD.wallet_id
    USING ERRCODE = 'restrict_violation';
    -- RETURN OLD is never reached; here only to satisfy PL/pgSQL syntax requirements.
    RETURN OLD;
END;
$$;

-- 2. Attach the trigger so it fires BEFORE any UPDATE or DELETE on wallet_ledger
DROP TRIGGER IF EXISTS trg_wallet_ledger_immutable ON transaction.wallet_ledger;

CREATE TRIGGER trg_wallet_ledger_immutable
    BEFORE UPDATE OR DELETE
    ON transaction.wallet_ledger
    FOR EACH ROW
    EXECUTE FUNCTION transaction.wallet_ledger_immutable();

-- 3. Application-role privilege restriction
--    Create the restricted application role if it does not already exist.
--    In production the application datasource should use vigilia_app credentials.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vigilia_app') THEN
        CREATE ROLE vigilia_app LOGIN;
    END IF;
END $$;

-- Grant minimum necessary privileges on wallet_ledger to the application role
GRANT SELECT, INSERT ON transaction.wallet_ledger TO vigilia_app;

-- Explicitly revoke UPDATE and DELETE from the application role (belt-and-suspenders)
REVOKE UPDATE, DELETE ON transaction.wallet_ledger FROM vigilia_app;

-- 4. Comment documenting the invariant
COMMENT ON TABLE transaction.wallet_ledger IS
    'Append-only double-entry money ledger. Mutation is prohibited by trg_wallet_ledger_immutable. '
    'The application role (vigilia_app) holds only SELECT and INSERT.';

