import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Navigate, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { Poster } from "../components/Poster";
import { useQueueStream } from "../hooks/useQueueStream";
import { apiFetch } from "../lib/api";
import { concertDate } from "../lib/format";
import {
  concertSchema,
  queuePositionSchema,
  queueTokenSchema,
  scheduleListSchema,
  type QueuePosition,
} from "../lib/contracts";
import { writeQueueToken } from "../lib/session";

/**
 * 대기실.
 *
 * 이 화면만 배경이 검정이다(DESIGN.md). 기다리는 시간은 다른 시간이라는 인상을 주고,
 * 그 위에서 액센트 하나가 가장 밝게 선다.
 *
 * SSE 재연결과 폴링 강등은 `useQueueStream`이 맡는다. 여기 있을 때는 브라우저를 띄워야만
 * 확인할 수 있었다.
 */
export function QueuePage() {
  const { scheduleId = "" } = useParams();
  const schedule = Number(scheduleId);
  const [search] = useSearchParams();
  const concertId = search.get("concert");
  const { session } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  /*
   * 무엇을 기다리는지 화면에 적는다. 전에는 순번만 있어서 어느 공연의 대기열인지
   * 알 수 없었다 — 탭을 두고 자리를 비웠다 돌아오면 더 그렇다.
   * 대기열 자체와는 무관하므로 실패해도 화면은 그대로 돈다.
   */
  const concert = useQuery({
    queryKey: ["concert", Number(concertId)],
    queryFn: () => apiFetch(`/api/concerts/${concertId}`, { schema: concertSchema }),
    enabled: Boolean(concertId),
  });
  const schedules = useQuery({
    queryKey: ["concert", Number(concertId), "schedules"],
    queryFn: () => apiFetch(`/api/concerts/${concertId}/schedules`, { schema: scheduleListSchema }),
    enabled: Boolean(concertId),
  });
  const showing = schedules.data?.find((item) => item.id === schedule);

  const [entered, setEntered] = useState<QueuePosition | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [reentering, setReentering] = useState(false);
  const enterCalled = useRef(false);

  useEffect(() => {
    if (!session || !Number.isFinite(schedule) || enterCalled.current) return;
    enterCalled.current = true;
    void apiFetch(`/api/queue/enter`, {
      method: "POST",
      token: session.token,
      body: { scheduleId: schedule },
      schema: queuePositionSchema,
    })
      .then(setEntered)
      .catch((caught: unknown) => {
        setError(caught instanceof Error ? caught.message : "대기열에 진입하지 못했습니다.");
      });
  }, [schedule, session]);

  // 진입·재진입 응답을 스트림에 넘긴다. 스트림이 그걸 현재 값으로 받아들이므로
  // 여기서 둘 중 하나를 고르지 않는다 — 고르게 했더니 폴링이 채운 오래된 값이
  // 재진입 결과를 덮었다.
  const stream = useQueueStream(schedule, session?.token ?? null, entered !== null, entered);
  const position = stream.position;
  const transport = stream.transport;

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
      setEntered(next);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "대기열에 다시 진입하지 못했습니다.");
    } finally {
      setReentering(false);
    }
  };

  return (
    <main id="main-content" className="queue-page">
      <div className="queue-meta">
        <p className="label">Waiting Room</p>
        {/* 연결 상태를 색만으로 말하지 않는다 — 글자로도 적는다 */}
        <span className={`live-state ${transport}`}>
          <i aria-hidden="true" />
          {transport === "live"
            ? "실시간 연결"
            : transport === "polling"
              ? "자동 새로고침"
              : "연결 중"}
        </span>
      </div>

      {concert.data && (
        <div className="queue-show">
          <div className="queue-show-thumb">
            <Poster title={concert.data.title} />
          </div>
          <div>
            <strong>{concert.data.title}</strong>
            <p>
              {concert.data.venue}
              {showing ? ` · ${concertDate(showing.scheduleDate, showing.startTime)}` : ""}
            </p>
          </div>
        </div>
      )}

      <section className="queue-focus" aria-live="polite">
        <span className="label">현재 내 순서</span>
        <strong className="display">
          {position?.position && position.position > 0
            ? position.position.toLocaleString("ko-KR")
            : ready
              ? "입장"
              : "—"}
        </strong>
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

      <div className="queue-line" aria-hidden="true">
        <span style={{ width: ready ? "100%" : "38%" }} />
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

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
