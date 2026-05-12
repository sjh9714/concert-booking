CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(100) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS concerts (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    venue       VARCHAR(255) NOT NULL,
    artist      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS concert_schedules (
    id              BIGSERIAL PRIMARY KEY,
    concert_id      BIGINT NOT NULL REFERENCES concerts(id),
    schedule_date   DATE NOT NULL,
    start_time      TIME NOT NULL,
    total_seats     INT NOT NULL,
    available_seats INT NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS seats (
    id            BIGSERIAL PRIMARY KEY,
    schedule_id   BIGINT NOT NULL REFERENCES concert_schedules(id),
    section       VARCHAR(10) NOT NULL,
    row_number    INT NOT NULL,
    seat_number   INT NOT NULL,
    price         INT NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(schedule_id, section, row_number, seat_number)
);

CREATE TABLE IF NOT EXISTS reservations (
    id              BIGSERIAL PRIMARY KEY,
    reservation_key UUID NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    schedule_id     BIGINT NOT NULL REFERENCES concert_schedules(id),
    status          VARCHAR(20) NOT NULL,
    total_amount    INT NOT NULL,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS reservation_idempotency_keys (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL REFERENCES users(id),
    schedule_id       BIGINT NOT NULL REFERENCES concert_schedules(id),
    idempotency_key   VARCHAR(120) NOT NULL,
    request_hash      VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    reservation_id    BIGINT REFERENCES reservations(id) ON DELETE SET NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, schedule_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS reservation_seats (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id),
    seat_id         BIGINT NOT NULL REFERENCES seats(id),
    UNIQUE(reservation_id, seat_id)
);

CREATE TABLE IF NOT EXISTS payments (
    id              BIGSERIAL PRIMARY KEY,
    payment_key     UUID NOT NULL UNIQUE,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id),
    idempotency_key VARCHAR(120),
    amount          INT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS outbox_events (
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
    next_attempt_at TIMESTAMP,
    dead_at         TIMESTAMP,
    last_error      TEXT
);

ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS dead_at TIMESTAMP;

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_seats_schedule_status ON seats(schedule_id, status);
CREATE INDEX IF NOT EXISTS idx_reservations_user_id ON reservations(user_id);
CREATE INDEX IF NOT EXISTS idx_reservations_status_expires ON reservations(status, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reservation_idempotency_scope
    ON reservation_idempotency_keys(user_id, schedule_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_reservation_idempotency_reservation
    ON reservation_idempotency_keys(reservation_id);
CREATE INDEX IF NOT EXISTS idx_reservation_seats_reservation ON reservation_seats(reservation_id);
CREATE INDEX IF NOT EXISTS idx_reservation_seats_seat ON reservation_seats(seat_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payments_reservation_idempotency
    ON payments(reservation_id, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payments_reservation
    ON payments(reservation_id);
CREATE INDEX IF NOT EXISTS idx_outbox_events_publishable
    ON outbox_events(status, locked_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_events_publishable_retry
    ON outbox_events(status, next_attempt_at, locked_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate
    ON outbox_events(aggregate_type, aggregate_id);
