package com.concert.booking;

import com.concert.booking.common.util.ApiTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class ConcertBookingApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(ApiTime.ZONE_ID));
        SpringApplication.run(ConcertBookingApplication.class, args);
    }
}
