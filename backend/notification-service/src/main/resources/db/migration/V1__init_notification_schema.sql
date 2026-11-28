CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE IF NOT EXISTS notifications (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    event_id        UUID UNIQUE,
    event_type      VARCHAR(64) NOT NULL,
    category        VARCHAR(32) NOT NULL,
    severity        VARCHAR(20) NOT NULL,
    channel         VARCHAR(20) NOT NULL DEFAULT 'IN_APP',
    title           VARCHAR(255) NOT NULL,
    message         TEXT NOT NULL,
    transaction_id  UUID,
    metadata        TEXT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications (user_id, is_read);

CREATE TABLE IF NOT EXISTS notification_preferences (
    user_id                 UUID PRIMARY KEY,
    transaction_updates     BOOLEAN NOT NULL DEFAULT TRUE,
    security_alerts         BOOLEAN NOT NULL DEFAULT TRUE,
    transfer_reviews        BOOLEAN NOT NULL DEFAULT TRUE,
    promotional_updates     BOOLEAN NOT NULL DEFAULT FALSE,
    email_notifications     BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_notifications    BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

