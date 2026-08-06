import { fetchEventSource, type EventSourceMessage } from "@microsoft/fetch-event-source";
import { useEffect, useState } from "react";
import { apiFetch, apiUrl, handleUnauthorized } from "../lib/api";
import { queuePositionSchema, type QueuePosition } from "../lib/contracts";

/**
 * 대기열 순번을 받는다. SSE로 듣고, 안 되면 폴링으로 내려앉는다.
 *
 * 페이지 안에 있던 로직을 훅으로 뽑았다. 페이지에 있을 때는 브라우저를 띄워야만 확인할 수
 * 있었다 — "세 번 실패하면 폴링으로 바뀐다"를 검증하려면 실제 서버를 죽여야 했다.
 * 훅이 되면 그걸 단위 테스트로 고정할 수 있다.
 *
 * 재연결 예산이 있는 이유: `fetchEventSource`는 그냥 두면 무한히 다시 붙는다.
 * 서버가 정말 죽었을 때 화면이 영원히 "연결 중"으로 남으므로, 세 번까지만 시도하고
 * 폴링으로 바꾼다 — 느려도 순번은 보이는 쪽이 낫다.
 */

export const MAX_SSE_RECONNECTS = 3;
export const SSE_RECONNECT_BASE_DELAY_MS = 500;

/** connecting = 붙는 중 · live = SSE로 받는 중 · polling = SSE 포기하고 주기 조회 */
export type Transport = "connecting" | "live" | "polling";

export interface QueueStream {
  position: QueuePosition | null;
  transport: Transport;
}

function parseEvent(message: EventSourceMessage): QueuePosition | null {
  if (!message.data || message.data === "heartbeat") return null;
  try {
    const parsed: unknown = JSON.parse(message.data);
    const result = queuePositionSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/** 중단 신호를 존중하는 대기. 언마운트됐는데 계속 자고 있으면 안 된다. */
export function waitForReconnect(delayMs: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => {
    const finish = () => {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const timer = window.setTimeout(finish, delayMs);
    signal.addEventListener("abort", finish, { once: true });
  });
}

/**
 * @param seed 대기열 진입·재진입 응답. 이게 바뀌면 스트림이 들고 있던 값을 버린다.
 *
 * 처음엔 페이지가 `stream.position ?? entered`로 골랐는데, 그러면 폴링이 이미 값을 채운 뒤에는
 * 재진입 응답이 무시된다 — 다시 참여했는데 화면이 계속 "참여하지 않았습니다"로 남았다.
 * e2e가 잡았다. 진입 응답은 그 시점의 진실이므로 스트림이 그걸 받아들여야 한다.
 */
export function useQueueStream(
  scheduleId: number,
  token: string | null,
  enabled: boolean,
  seed: QueuePosition | null,
): QueueStream {
  const [position, setPosition] = useState<QueuePosition | null>(null);
  const [transport, setTransport] = useState<Transport>("connecting");

  /*
   * 바깥에서 들어온 값이 바뀌면 렌더 중에 맞춘다 (React의 "props가 바뀔 때 state 조정" 패턴).
   *
   * 처음엔 effect 안에서 setState를 했다가 `react-hooks/set-state-in-effect`에 걸렸다.
   * 규칙을 피하려고 옮긴 게 아니라, effect는 "그린 뒤에" 도는 것이라 한 번 틀린 화면을
   * 그리고 나서 고치게 된다. 렌더 중에 맞추면 틀린 화면 자체가 나오지 않는다.
   */
  const [seenSeed, setSeenSeed] = useState(seed);
  if (seed !== seenSeed) {
    setSeenSeed(seed);
    if (seed) setPosition(seed);
  }

  // 연결 대상이 바뀌면 상태를 "연결 중"으로 되돌린다.
  // 이전 연결의 live/polling 표시가 새 연결에 남아 있으면 화면이 거짓말을 한다.
  const connectionKey = enabled && token && Number.isFinite(scheduleId) ? `${scheduleId}` : null;
  const [seenConnection, setSeenConnection] = useState(connectionKey);
  if (connectionKey !== seenConnection) {
    setSeenConnection(connectionKey);
    setTransport("connecting");
  }

  useEffect(() => {
    if (!token || !Number.isFinite(scheduleId) || !enabled) return;
    const controller = new AbortController();

    const connect = async () => {
      let reconnects = 0;
      while (!controller.signal.aborted) {
        let streamCompleted = false;
        try {
          await fetchEventSource(apiUrl(`/api/queue/events?scheduleId=${scheduleId}`), {
            headers: { Authorization: `Bearer ${token}` },
            signal: controller.signal,
            openWhenHidden: true,
            onopen: async (response) => {
              if (response.status === 401) {
                streamCompleted = true;
                handleUnauthorized();
                throw new Error("SSE 401");
              }
              const contentType = response.headers.get("content-type");
              if (!response.ok || !contentType?.startsWith("text/event-stream")) {
                throw new Error(`SSE ${response.status}`);
              }
              setTransport("live");
            },
            onmessage: (message) => {
              const next = parseEvent(message);
              if (next) setPosition(next);
              if (message.event === "READY") {
                setPosition((current) => (current ? { ...current, status: "READY" } : current));
              }
              if (message.event === "COMPLETED") streamCompleted = true;
            },
            // 정상 종료도 fetchEventSource를 resolve시킨다. 바깥 루프가
            // 네트워크 실패와 같은 재연결 예산을 적용한다.
            onclose: () => undefined,
            // 던져야 이 호출이 끝난다. 안 던지면 라이브러리가 안에서 무한히 재시도해
            // 컴포넌트가 예산을 강제할 수 없다.
            onerror: (caught) => {
              throw caught;
            },
          });
        } catch {
          // 정상 종료와 네트워크 실패가 같은 재시도 경로를 쓴다.
        }

        if (controller.signal.aborted || streamCompleted) return;
        if (reconnects >= MAX_SSE_RECONNECTS) {
          setTransport("polling");
          return;
        }

        const delay = SSE_RECONNECT_BASE_DELAY_MS * 2 ** reconnects;
        reconnects += 1;
        setTransport("connecting");
        await waitForReconnect(delay, controller.signal);
      }
    };

    void connect();
    return () => controller.abort();
  }, [scheduleId, token, enabled]);

  // 폴링으로 내려앉았을 때만 돈다. SSE가 살아 있는데 같이 돌면 두 배로 부른다.
  useEffect(() => {
    if (transport !== "polling" || !token) return;
    const poll = () =>
      void apiFetch(`/api/queue/position?scheduleId=${scheduleId}`, {
        token,
        schema: queuePositionSchema,
      })
        .then(setPosition)
        .catch(() => undefined);
    poll();
    const interval = window.setInterval(poll, 1500);
    return () => window.clearInterval(interval);
  }, [scheduleId, token, transport]);

  return { position, transport };
}
