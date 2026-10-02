package com.concert.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 중복 만료 실행은 예약 행의 DB 잠금과 상태 검사로 조정한다. */
@Configuration
@Profile("service")
@EnableScheduling
public class ServiceSchedulingConfig {}
