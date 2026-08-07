package com.concert.booking.repository;

import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * 좌석표를 그리는 조회.
     *
     * <p>순서를 지정하지 않으면 DB가 돌려주는 순서가 그대로 화면에 나온다.
     * 실제로 1열이 8·9·10·5·6·1·2·3·4·7 순으로 그려지고 있었다 —
     * 번호가 뒤섞인 좌석표는 그것만으로 고장 나 보인다.
     *
     * <p>구역 정렬은 이름순이라 A·R·S·VIP가 된다. 무대에서 가까운 순서(VIP·R·S·A)는
     * 화면이 정해야 할 값이므로 여기서 강제하지 않는다.
     */
    List<Seat> findByScheduleIdOrderBySectionAscRowNumberAscSeatNumberAsc(Long scheduleId);

    /** 순서가 상관없는 관리·부하 테스트 경로용 */
    List<Seat> findByScheduleId(Long scheduleId);

    List<Seat> findByScheduleIdAndStatus(Long scheduleId, SeatStatus status);

    long countByScheduleIdAndStatus(Long scheduleId, SeatStatus status);

    // 비관적 락: 좌석 ID 목록으로 AVAILABLE 좌석 조회 + FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.schedule.id = :scheduleId
              AND s.id IN :seatIds
              AND s.status = 'AVAILABLE'
            ORDER BY s.id
            """)
    List<Seat> findAllByScheduleIdAndIdInAndAvailableForUpdate(
            @Param("scheduleId") Long scheduleId,
            @Param("seatIds") List<Long> seatIds);

    // 낙관적 락: 락 없이 AVAILABLE 좌석 조회 (커밋 시 @Version으로 충돌 감지)
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.schedule.id = :scheduleId
              AND s.id IN :seatIds
              AND s.status = 'AVAILABLE'
            ORDER BY s.id
            """)
    List<Seat> findAllByScheduleIdAndIdInAndAvailable(
            @Param("scheduleId") Long scheduleId,
            @Param("seatIds") List<Long> seatIds);

    @Modifying
    @Query(nativeQuery = true,
            value = "UPDATE seats SET status = 'AVAILABLE', version = 0 WHERE schedule_id = :scheduleId")
    void resetSeatsByScheduleId(@Param("scheduleId") Long scheduleId);
}
