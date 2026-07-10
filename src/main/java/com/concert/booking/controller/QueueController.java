package com.concert.booking.controller;

import com.concert.booking.config.QueueProperties;
import com.concert.booking.dto.queue.QueueEnterRequest;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueStatus;
import com.concert.booking.dto.queue.QueueTokenResponse;
import com.concert.booking.service.auth.CustomUserDetails;
import com.concert.booking.service.queue.QueueService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;
    private final QueueProperties properties;
    private final TaskScheduler queueTaskScheduler;

    public QueueController(
            QueueService queueService,
            QueueProperties properties,
            @Qualifier("queueTaskScheduler") TaskScheduler queueTaskScheduler) {
        this.queueService = queueService;
        this.properties = properties;
        this.queueTaskScheduler = queueTaskScheduler;
    }

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

        SseEmitter emitter = new SseEmitter(properties.getSseTimeout().toMillis());
        Long userId = userDetails.getUserId();
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();
        AtomicBoolean readySent = new AtomicBoolean(false);
        AtomicReference<OffsetDateTime> lastHeartbeat = new AtomicReference<>(OffsetDateTime.now(ZoneOffset.UTC));

        Runnable update = () -> {
            try {
                QueuePositionResponse position = queueService.getPosition(userId, scheduleId);
                emitter.send(SseEmitter.event().name("POSITION").data(position));

                if (position.status() == QueueStatus.ADMITTED) {
                    emitter.send(SseEmitter.event()
                            .name("COMPLETED")
                            .data(position));
                    emitter.complete();
                    cancel(taskRef);
                    return;
                }

                if (position.status() == QueueStatus.READY && readySent.compareAndSet(false, true)) {
                    emitter.send(SseEmitter.event()
                            .name("READY")
                            .data(position));
                }

                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                if (lastHeartbeat.get().plus(properties.getHeartbeatInterval()).isBefore(now)) {
                    emitter.send(SseEmitter.event().name("HEARTBEAT").data("heartbeat"));
                    lastHeartbeat.set(now);
                }
            } catch (AsyncRequestNotUsableException e) {
                log.debug("Queue SSE response is no longer usable: userId={}, scheduleId={}", userId, scheduleId);
                cancel(taskRef);
            } catch (IOException e) {
                log.debug("Queue SSE client disconnected: userId={}, scheduleId={}", userId, scheduleId);
                cancel(taskRef);
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
                cancel(taskRef);
            }
        };

        taskRef.set(queueTaskScheduler.scheduleAtFixedRate(
                update,
                Instant.now().plus(properties.getUpdateInterval()),
                properties.getUpdateInterval()));

        emitter.onCompletion(() -> cancel(taskRef));
        emitter.onTimeout(() -> cancel(taskRef));
        emitter.onError(e -> cancel(taskRef));

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

    private void cancel(AtomicReference<ScheduledFuture<?>> taskRef) {
        ScheduledFuture<?> task = taskRef.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }
}
