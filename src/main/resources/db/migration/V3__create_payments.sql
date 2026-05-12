CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    payment_key     UUID NOT NULL UNIQUE,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id),
    idempotency_key VARCHAR(120),
    amount          INT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
