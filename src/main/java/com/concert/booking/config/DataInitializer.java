package com.concert.booking.config;

import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.User;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.auth.DemoAccount;
import com.concert.booking.service.stock.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 데모 데이터를 만든다.
 *
 * <p>규모가 화면을 결정한다. 전에는 회차당 50석(VIP 10 · A 20 · B 20)이라
 * 좌석표가 한 줄 10칸으로 끝났고, 실제 예매 화면이 아니라 테스트 픽스처처럼 보였다.
 * 그래서 공연장 하나를 제대로 짓는다 — 구역 4개 436석.
 *
 * <p>그리고 <b>일부는 이미 팔려 있어야 한다.</b> 전 좌석이 비어 있는 예매 화면은
 * 존재하지 않는다. 좋은 자리부터 빠지고, 공연이 가까울수록 많이 빠진다.
 *
 * <p>판매 상태는 회차 id로 시드를 고정해 만든다. 다시 띄워도 같은 좌석표가 나와야
 * 캡처와 시연이 재현된다.
 */
@Slf4j
@Component
@Profile("(demo | e2e | load-test) & !prod")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ConcertRepository concertRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final RedisStockService redisStockService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 구역 하나의 배치와 값 */
    private record Tier(String section, int rows, int seatsPerRow, int price) {
        int total() {
            return rows * seatsPerRow;
        }
    }

    /**
     * 공연장 배치. 무대에서 멀어질수록 넓어지고 싸진다.
     * 합계 436석 — 좌석표가 한 화면에 담기면서도 '진짜 공연장'으로 읽히는 크기다.
     */
    private static final List<Tier> HALL = List.of(
            new Tier("VIP", 3, 12, 150_000),
            new Tier("R", 6, 16, 120_000),
            new Tier("S", 8, 18, 90_000),
            new Tier("A", 8, 20, 60_000));

    private static final int HALL_TOTAL = HALL.stream().mapToInt(Tier::total).sum();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureDemoAccount();
        if (concertRepository.count() > 0) {
            log.info("데모 데이터가 이미 있습니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("데모 데이터 초기화 시작");

        int schedules = 0;
        schedules += createConcert(
                "NOCTURNE — SEOUL",
                "빛과 리듬으로 구성한 야간 라이브 세션",
                "아르코 아레나", "Studio Lune",
                new int[] {7, 8}, new LocalTime[] {LocalTime.of(19, 0), LocalTime.of(18, 0)});
        schedules += createConcert(
                "ORBITAL WEEKEND",
                "전자음악과 라이브 밴드가 교차하는 주말 공연",
                "웨이브 홀", "Northbound",
                new int[] {14, 15}, new LocalTime[] {LocalTime.of(19, 30), LocalTime.of(17, 0)});
        schedules += createConcert(
                "종이비행기",
                "어쿠스틱 편성으로 다시 부르는 열두 곡",
                "마포아트센터 아트홀", "이한결",
                new int[] {21, 22, 23},
                new LocalTime[] {LocalTime.of(20, 0), LocalTime.of(19, 0), LocalTime.of(15, 0)});
        schedules += createConcert(
                "LOW TIDE",
                "앰비언트와 현악 사중주가 만나는 1부 · 2부 구성",
                "플랫폼 창동", "해안선",
                new int[] {28, 29}, new LocalTime[] {LocalTime.of(19, 0), LocalTime.of(16, 0)});
        schedules += createConcert(
                "밤의 라디오",
                "라이브 토크와 신청곡으로 채우는 공개 방송",
                "롤링홀", "김도이 밴드",
                new int[] {35, 36}, new LocalTime[] {LocalTime.of(20, 0), LocalTime.of(19, 0)});
        schedules += createConcert(
                "CROSSFADE 2026",
                "다섯 팀이 이어 붙이는 페스티벌 형식의 라이브",
                "올림픽공원 올림픽홀", "여러 아티스트",
                new int[] {42, 43}, new LocalTime[] {LocalTime.of(18, 0), LocalTime.of(17, 0)});

        log.info("데모 데이터 초기화 완료 — 공연 6개 · 회차 {}개 · 회차당 {}석", schedules, HALL_TOTAL);
    }

    private void ensureDemoAccount() {
        if (userRepository.findByEmail(DemoAccount.EMAIL).isEmpty()) {
            userRepository.save(User.create(
                    DemoAccount.EMAIL,
                    passwordEncoder.encode(UUID.randomUUID().toString()),
                    DemoAccount.NICKNAME));
        }
    }

    private int createConcert(String title, String description, String venue, String artist,
                              int[] daysFromNow, LocalTime[] times) {
        Concert concert = Concert.create(title, description, venue, artist);
        concertRepository.save(concert);
        for (int i = 0; i < daysFromNow.length; i++) {
            createScheduleWithSeats(concert, LocalDate.now().plusDays(daysFromNow[i]), times[i]);
        }
        return daysFromNow.length;
    }

    private void createScheduleWithSeats(Concert concert, LocalDate date, LocalTime time) {
        ConcertSchedule schedule = ConcertSchedule.create(concert, date, time, HALL_TOTAL);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>(HALL_TOTAL);
        for (Tier tier : HALL) {
            for (int row = 1; row <= tier.rows(); row++) {
                for (int number = 1; number <= tier.seatsPerRow(); number++) {
                    seats.add(Seat.create(schedule, tier.section(), row, number, tier.price()));
                }
            }
        }

        int sold = markSold(seats, schedule);
        seatRepository.saveAll(seats);

        // 회차의 잔여 좌석과 Redis 재고를 실제 남은 수로 맞춘다.
        // 이걸 빠뜨리면 화면의 잔여 좌석과 좌석표가 서로 다른 말을 한다.
        schedule.syncAvailableSeats(HALL_TOTAL - sold);
        redisStockService.initialize(schedule.getId(), false);
    }

    /**
     * 이미 팔린 좌석을 만든다.
     *
     * <p>무작위로 흩뿌리지 않는다. 실제 예매는 좋은 자리부터 빠지고, 공연이 가까울수록
     * 많이 빠진다. 그 두 가지를 그대로 규칙으로 쓴다 — VIP가 가장 많이 팔리고
     * 뒤로 갈수록 덜 팔린다.
     *
     * <p>시드를 회차 id로 고정해 다시 띄워도 같은 좌석표가 나오게 한다.
     * 캡처와 시연이 매번 달라지면 무엇을 보고 있는지 말할 수 없다.
     *
     * @return 판매 처리한 좌석 수
     */
    private int markSold(List<Seat> seats, ConcertSchedule schedule) {
        long daysAway = ChronoUnit.DAYS.between(LocalDate.now(), schedule.getScheduleDate());
        // 가까운 공연일수록 많이 팔린다 — 7일 뒤 약 61%, 42일 뒤 약 26%
        double base = Math.max(0.22, 0.68 - daysAway * 0.01);

        Random random = new Random(schedule.getId() == null ? 0 : schedule.getId());
        int sold = 0;
        int index = 0;
        for (Tier tier : HALL) {
            // 앞 구역일수록 더 팔린다. VIP는 base 그대로, 뒤로 갈수록 줄어든다.
            double ratio = base * switch (tier.section()) {
                case "VIP" -> 1.0;
                case "R" -> 0.85;
                case "S" -> 0.62;
                default -> 0.38;
            };
            for (int i = 0; i < tier.total(); i++, index++) {
                if (random.nextDouble() < ratio) {
                    Seat seat = seats.get(index);
                    seat.hold();
                    seat.reserve();
                    sold++;
                }
            }
        }
        return sold;
    }
}
