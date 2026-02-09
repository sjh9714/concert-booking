package com.concert.booking.controller;

import com.concert.booking.dto.queue.QueueEnterRequest;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueTokenResponse;
import com.concert.booking.service.auth.CustomUserDetails;
import com.concert.booking.service.queue.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    private static final int ENTRY_THRESHOLD = 100;
    private static final long SSE_TIMEOUT = 5 * 60 * 1000L; // 5분

    // POST /api/queue/enter — 대기열 진입
    @PostMapping("/enter")
    public ResponseEntity<QueuePositionResponse> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody QueueEnterRequest request) {
        QueuePositionResponse response = queueService.enter(userDetails.getUserId(), request.scheduleId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/queue/position — 순위 조회
    @GetMapping("/position")
    public ResponseEntity<QueuePositionResponse> getPosition(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam Long scheduleId) {
        QueuePositionResponse response = queueService.getPosition(userDetails.getUserId(), scheduleId);
        return ResponseEntity.ok(response);
    }

    // GET /api/queue/events — SSE 실시간 순번 스트림
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPosition(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam Long scheduleId) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long userId = userDetails.getUserId();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // 매 1초마다 순위 전송
        scheduler.scheduleAtFixedRate(() -> {
            try {
                QueuePositionResponse position = queueService.getPosition(userId, scheduleId);

                if (position.position() == 0) {
                    // 대기열에서 이미 제거됨 (토큰 발급 완료)
                    emitter.send(SseEmitter.event()
                            .name("COMPLETED")
                            .data("대기열에서 제거되었습니다."));
                    emitter.complete();
                    scheduler.shutdown();
                    return;
                }

                emitter.send(SseEmitter.event()
                        .name("POSITION")
                        .data(position));

                // 순위 ≤ threshold → READY 이벤트 전송
                if (position.position() <= ENTRY_THRESHOLD) {
                    emitter.send(SseEmitter.event()
                            .name("READY")
                            .data("입장 가능합니다. 토큰을 발급받아주세요."));
                    emitter.complete();
                    scheduler.shutdown();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                scheduler.shutdown();
            }
        }, 0, 1, TimeUnit.SECONDS);

        emitter.onCompletion(scheduler::shutdown);
        emitter.onTimeout(scheduler::shutdown);
        emitter.onError(e -> scheduler.shutdown());

        return emitter;
    }

    // GET /api/queue/token — 입장 토큰 발급
    @GetMapping("/token")
    public ResponseEntity<QueueTokenResponse> issueToken(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam Long scheduleId) {
        QueueTokenResponse response = queueService.issueToken(userDetails.getUserId(), scheduleId);
        return ResponseEntity.ok(response);
    }
}
