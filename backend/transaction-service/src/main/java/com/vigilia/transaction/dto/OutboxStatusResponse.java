package com.vigilia.transaction.dto;

/**
 * Live snapshot of the transactional outbox relay health.
 * Returned by GET /api/v1/transactions/outbox/status
 */
public class OutboxStatusResponse {

    private long pending;    // events waiting to be relayed (PENDING status)
    private long published;  // events successfully delivered (PUBLISHED status)
    private long failed;     // events that exhausted all retry attempts (FAILED status)
    private boolean healthy; // true when pending == 0 and failed == 0

    public long getPending()    { return pending; }
    public void setPending(long pending) { this.pending = pending; }

    public long getPublished()  { return published; }
    public void setPublished(long published) { this.published = published; }

    public long getFailed()     { return failed; }
    public void setFailed(long failed) { this.failed = failed; }

    public boolean isHealthy()  { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
}

