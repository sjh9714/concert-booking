-- 기존 이력을 보존하며 현재 선점/확정 예약만 연결한다.
ALTER TABLE seats ADD COLUMN current_reservation_id BIGINT REFERENCES reservations(id) ON DELETE SET NULL;
UPDATE seats s SET current_reservation_id = current_owner.reservation_id
FROM (
    SELECT DISTINCT ON (rs.seat_id) rs.seat_id, rs.reservation_id
    FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id
    WHERE r.status IN ('PENDING', 'CONFIRMED')
    ORDER BY rs.seat_id, rs.reservation_id DESC
) current_owner
WHERE s.id = current_owner.seat_id AND s.status IN ('HELD', 'RESERVED');
CREATE INDEX idx_seat_owner ON seats(current_reservation_id) WHERE current_reservation_id IS NOT NULL;
