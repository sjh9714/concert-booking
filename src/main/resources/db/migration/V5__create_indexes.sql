CREATE INDEX idx_seats_schedule_status ON seats(schedule_id, status);
CREATE INDEX idx_reservations_user_id ON reservations(user_id);
CREATE INDEX idx_reservations_status_expires ON reservations(status, expires_at);

CREATE UNIQUE INDEX uk_reservation_idempotency_scope
    ON reservation_idempotency_keys(user_id, schedule_id, idempotency_key);
CREATE INDEX idx_reservation_idempotency_reservation
    ON reservation_idempotency_keys(reservation_id);

CREATE INDEX idx_reservation_seats_reservation ON reservation_seats(reservation_id);
CREATE INDEX idx_reservation_seats_seat ON reservation_seats(seat_id);

CREATE UNIQUE INDEX uk_payments_reservation_idempotency
    ON payments(reservation_id, idempotency_key);
CREATE UNIQUE INDEX uk_payments_reservation
    ON payments(reservation_id);

CREATE INDEX idx_outbox_events_publishable
    ON outbox_events(status, locked_at, created_at);
CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events(aggregate_type, aggregate_id);
