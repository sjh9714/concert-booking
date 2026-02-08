package com.concert.booking.dto.concert;

import com.concert.booking.domain.Concert;

public record ConcertResponse(
        Long id,
        String title,
        String description,
        String venue,
        String artist
) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getDescription(),
                concert.getVenue(),
                concert.getArtist()
        );
    }
}
