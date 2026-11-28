-- =============================================================================
-- V6__outbox_events_lease.sql
-- Step 6: Add lease column (locked_until) to outbox_events so the relay can
-- claim batches with FOR UPDATE SKIP LOCKED in a short transaction, mark them
-- IN_PROGRESS with locked_until, deliver HTTP outside the DB transaction, and
-- reclaim expired leases if a relay instance crashes mid-delivery.
-- =============================================================================

ALTER TABLE transaction.outbox_events
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_txn_outbox_status_lease
    ON transaction.outbox_events(status, locked_until);

