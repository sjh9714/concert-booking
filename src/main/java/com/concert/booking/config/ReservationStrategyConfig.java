package com.concert.booking.config;

import com.concert.booking.service.reservation.DistributedLockReservationService;
import com.concert.booking.service.reservation.OptimisticLockReservationService;
import com.concert.booking.service.reservation.PessimisticLockReservationService;
import com.concert.booking.service.reservation.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class ReservationStrategyConfig {

    @Bean
    @Primary
    public ReservationService reservationService(
            @Value("${reservation.strategy:pessimistic}") String strategy,
            PessimisticLockReservationService pessimistic,
            OptimisticLockReservationService optimistic,
            DistributedLockReservationService distributed) {
        ReservationService selected = switch (strategy) {
            case "optimistic" -> optimistic;
            case "distributed" -> distributed;
            default -> pessimistic;
        };
        log.info("예매 전략 선택: {} → {}", strategy, selected.getClass().getSimpleName());
        return selected;
    }
}
