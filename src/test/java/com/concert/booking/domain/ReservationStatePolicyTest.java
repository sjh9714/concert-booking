package com.concert.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStatePolicyTest {

    @Test
    @DisplayName("PENDING reservation은 결제, 취소, 만료 가능 여부를 명확히 판단한다")
    void pending_reservation_exposes_transition_guards() {
        Reservation reservation = pendingReservation(LocalDateTime.now().minusMinutes(1));

        assertThat(reservation.canConfirm()).isTrue();
        assertThat(reservation.canCancel()).isTrue();
        assertThat(reservation.canExpire(LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("CONFIRMED reservation은 취소나 만료로 바뀔 수 없다")
    void confirmed_reservation_cannot_be_cancelled_or_expired() {
        Reservation reservation = pendingReservation(LocalDateTime.now().plusMinutes(5));

        reservation.confirm();

        assertThat(reservation.canConfirm()).isFalse();
        assertThat(reservation.canCancel()).isFalse();
        assertThat(reservation.canExpire(LocalDateTime.now().plusMinutes(10))).isFalse();
        assertThatThrownBy(reservation::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");
        assertThatThrownBy(() -> reservation.expire(LocalDateTime.now().plusMinutes(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    @DisplayName("만료 시간이 지나지 않은 PENDING reservation은 EXPIRED로 전이할 수 없다")
    void pending_reservation_cannot_expire_before_expires_at() {
        Reservation reservation = pendingReservation(LocalDateTime.now().plusMinutes(5));

        assertThat(reservation.canExpire(LocalDateTime.now())).isFalse();
        assertThatThrownBy(() -> reservation.expire(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiresAt");
    }

    private Reservation pendingReservation(LocalDateTime expiresAt) {
        User user = User.create("state-policy@test.com", "password", "상태테스터");
        Concert concert = Concert.create("상태 테스트 콘서트", "설명", "장소", "아티스트");
        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(1), LocalTime.of(20, 0), 1);
        return Reservation.create(user, schedule, 100000, expiresAt);
    }
}
