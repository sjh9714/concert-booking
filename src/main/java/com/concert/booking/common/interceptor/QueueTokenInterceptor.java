package com.concert.booking.common.interceptor;

import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.service.auth.CustomUserDetails;
import com.concert.booking.service.queue.QueueService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueTokenInterceptor implements HandlerInterceptor {

    private final QueueService queueService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // POST /api/reservations 요청에만 적용
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1. X-Queue-Token 헤더에서 토큰 추출 (없으면 대기열 미사용 → 통과)
        String token = request.getHeader("X-Queue-Token");
        if (token == null || token.isBlank()) {
            return true;
        }

        // 2. SecurityContext에서 userId 추출
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            throw new InvalidQueueTokenException("인증 정보를 확인할 수 없습니다.");
        }
        Long userId = userDetails.getUserId();

        // 3. 요청 body에서 scheduleId 추출
        Long scheduleId = extractScheduleId(request);
        if (scheduleId == null) {
            throw new InvalidQueueTokenException("스케줄 정보를 확인할 수 없습니다.");
        }

        // 4. 토큰 검증
        if (!queueService.validateToken(userId, scheduleId, token)) {
            throw new InvalidQueueTokenException("유효하지 않은 대기열 토큰입니다.");
        }

        return true;
    }

    private Long extractScheduleId(HttpServletRequest request) {
        try {
            // CachedBodyFilter에 의해 body가 캐싱되어 있으므로 InputStream을 여러 번 읽을 수 있음
            JsonNode node = objectMapper.readTree(request.getInputStream());
            return node.has("scheduleId") ? node.get("scheduleId").asLong() : null;
        } catch (Exception e) {
            log.warn("Failed to extract scheduleId from request body", e);
            return null;
        }
    }
}
