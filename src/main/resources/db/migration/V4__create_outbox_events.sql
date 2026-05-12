CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(80) NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    event_type      VARCHAR(80) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    retry_count     INT NOT NULL DEFAULT 0,
    locked_at       TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP,
    last_error      TEXT
);
