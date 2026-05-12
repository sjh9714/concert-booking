CREATE TABLE seats (
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

CREATE TABLE reservations (
    id              BIGSERIAL PRIMARY KEY,
    reservation_key UUID NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    schedule_id     BIGINT NOT NULL REFERENCES concert_schedules(id),
    status          VARCHAR(20) NOT NULL,
    total_amount    INT NOT NULL,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE reservation_idempotency_keys (
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

CREATE TABLE reservation_seats (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id),
    seat_id         BIGINT NOT NULL REFERENCES seats(id),
    UNIQUE(reservation_id, seat_id)
);
