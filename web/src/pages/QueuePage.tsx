import { fetchEventSource, type EventSourceMessage } from "@microsoft/fetch-event-source";
import { useEffect, useRef, useState } from "react";
import {
  Navigate,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { apiFetch, apiUrl, handleUnauthorized } from "../lib/api";
import {
  queuePositionSchema,
  queueTokenSchema,
  type QueuePosition,
} from "../lib/contracts";
import { writeQueueToken } from "../lib/session";

const MAX_SSE_RECONNECTS = 3;
const SSE_RECONNECT_BASE_DELAY_MS = 500;

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

function waitForReconnect(delayMs: number, signal: AbortSignal): Promise<void> {
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

export function QueuePage() {
  const { scheduleId = "" } = useParams();
  const schedule = Number(scheduleId);
  const [search] = useSearchParams();
  const concertId = search.get("concert");
  const { session } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [position, setPosition] = useState<QueuePosition | null>(null);
  const [transport, setTransport] = useState<"connecting" | "live" | "polling">("connecting");
  const [error, setError] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [reentering, setReentering] = useState(false);
  const entered = useRef(false);

  useEffect(() => {
    if (!session || !Number.isFinite(schedule) || entered.current) return;
    entered.current = true;
    void apiFetch(`/api/queue/enter`, {
      method: "POST",
      token: session.token,
      body: { scheduleId: schedule },
      schema: queuePositionSchema,
    })
      .then(setPosition)
      .catch((caught: unknown) => {
        setError(caught instanceof Error ? caught.message : "대기열에 진입하지 못했습니다.");
      });
  }, [schedule, session]);

  useEffect(() => {
    if (!session || !Number.isFinite(schedule) || !entered.current) return;
    const controller = new AbortController();
    setTransport("connecting");

    const connect = async () => {
      let reconnects = 0;
      while (!controller.signal.aborted) {
        let streamCompleted = false;
        try {
          await fetchEventSource(
            apiUrl(`/api/queue/events?scheduleId=${schedule}`),
            {
              headers: { Authorization: `Bearer ${session.token}` },
              signal: controller.signal,
              openWhenHidden: true,
              onopen: async (response) => {
                if (response.status === 401) {
                  streamCompleted = true;
                  handleUnauthorized();
                  throw new Error("SSE 401");
                }
                const contentType = response.headers.get("content-type");
                if (
                  !response.ok ||
                  !contentType?.startsWith("text/event-stream")
                ) {
                  throw new Error(`SSE ${response.status}`);
                }
                setTransport("live");
              },
              onmessage: (message) => {
                const next = parseEvent(message);
                if (next) setPosition(next);
                if (message.event === "READY") {
                  setPosition((current) =>
                    current ? { ...current, status: "READY" } : current,
                  );
                }
                if (message.event === "COMPLETED") streamCompleted = true;
              },
              // A normal EOF resolves fetchEventSource. The outer loop applies
              // the same finite reconnect budget as terminal fetch errors.
              onclose: () => undefined,
              // Throwing stops this library invocation; otherwise it retries
              // forever internally and the component cannot enforce a budget.
              onerror: (caught) => {
                throw caught;
              },
            },
          );
        } catch {
          // Normal close and network failure share the bounded retry path.
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
  }, [schedule, session]);

  useEffect(() => {
    if (transport !== "polling" || !session) return;
    const poll = () =>
      void apiFetch(`/api/queue/position?scheduleId=${schedule}`, {
        token: session.token,
        schema: queuePositionSchema,
      })
        .then(setPosition)
        .catch(() => undefined);
    poll();
    const interval = window.setInterval(poll, 1500);
    return () => window.clearInterval(interval);
  }, [schedule, session, transport]);

  if (!session) {
    return (
      <Navigate
        to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    );
  }

  const ready = position?.status === "READY" || position?.status === "ADMITTED";
  const expired = position?.status === "EXPIRED";
  const notJoined = position?.status === "NOT_JOINED";
  const needsReentry = expired || notJoined;
  const issueToken = async () => {
    setIssuing(true);
    setError(null);
    try {
      const token = await apiFetch(`/api/queue/token?scheduleId=${schedule}`, {
        token: session.token,
        schema: queueTokenSchema,
      });
      writeQueueToken(token);
      navigate(`/seats/${schedule}${concertId ? `?concert=${concertId}` : ""}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "입장 토큰을 받지 못했습니다.");
    } finally {
      setIssuing(false);
    }
  };

  const reenterQueue = async () => {
    setReentering(true);
    setError(null);
    try {
      const next = await apiFetch(`/api/queue/enter`, {
        method: "POST",
        token: session.token,
        body: { scheduleId: schedule },
        schema: queuePositionSchema,
      });
      setPosition(next);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "대기열에 다시 진입하지 못했습니다.");
    } finally {
      setReentering(false);
    }
  };

  return (
    <main id="main-content" className="queue-page">
      <div className="queue-meta">
        <p className="eyebrow">WAITING ROOM</p>
        <span className={`live-state ${transport}`}>
          <i aria-hidden="true" />
          {transport === "live" ? "실시간 연결" : transport === "polling" ? "자동 새로고침" : "연결 중"}
        </span>
      </div>
      <section className="queue-focus" aria-live="polite">
        <span>현재 내 순서</span>
        <strong>{position?.position && position.position > 0 ? position.position.toLocaleString("ko-KR") : ready ? "입장" : "—"}</strong>
        <p>
          {ready
            ? "좌석을 선택할 차례입니다. 입장 후 5분 동안 토큰이 유효합니다."
            : expired
              ? "입장 시간이 만료되었습니다. 다시 참여하면 새 순서를 받습니다."
              : notJoined
                ? "현재 대기열에 참여하지 않았습니다. 다시 참여해 순서를 받으세요."
            : position?.status === "WAITING"
              ? `전체 ${position.totalWaiting.toLocaleString("ko-KR")}명이 기다리고 있습니다.`
              : "순서를 확인하고 있습니다."}
        </p>
      </section>
      <div className="queue-line" aria-hidden="true"><span style={{ width: ready ? "100%" : "38%" }} /></div>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="queue-actions">
        <button
          className="primary-button"
          type="button"
          disabled={(!ready && !needsReentry) || issuing || reentering}
          onClick={() => void (needsReentry ? reenterQueue() : issueToken())}
        >
          {reentering
            ? "대기열 재진입 중"
            : issuing
              ? "입장 준비 중"
              : needsReentry
                ? "대기열 다시 참여"
                : ready
                  ? "좌석 선택으로 입장"
                  : "순서를 기다리는 중"}
        </button>
        <p>페이지를 벗어나도 다시 접속하면 현재 순서를 확인합니다.</p>
      </div>
    </main>
  );
}
