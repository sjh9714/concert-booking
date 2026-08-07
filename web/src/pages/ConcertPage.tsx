import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, MapPin } from "lucide-react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { Poster } from "../components/Poster";
import { apiFetch } from "../lib/api";
import { concertDate } from "../lib/format";
import { concertSchema, scheduleListSchema } from "../lib/contracts";

export function ConcertPage() {
  const { concertId = "" } = useParams();
  const id = Number(concertId);
  const navigate = useNavigate();
  const { session } = useAuth();
  const concert = useQuery({
    queryKey: ["concert", id],
    queryFn: () => apiFetch(`/api/concerts/${id}`, { schema: concertSchema }),
    enabled: Number.isFinite(id),
  });
  const schedules = useQuery({
    queryKey: ["concert", id, "schedules"],
    queryFn: () => apiFetch(`/api/concerts/${id}/schedules`, { schema: scheduleListSchema }),
    enabled: Number.isFinite(id),
  });

  if (concert.isLoading || schedules.isLoading) return <LoadingState />;
  if (concert.isError || schedules.isError || !concert.data) {
    return <ErrorState>공연 정보를 불러오지 못했습니다.</ErrorState>;
  }

  const startBooking = (scheduleId: number) => {
    const queuePath = `/queue/${scheduleId}?concert=${id}`;
    navigate(session ? queuePath : `/login?next=${encodeURIComponent(queuePath)}`);
  };

  /*
   * 공연 요약은 이미 받아 둔 회차 목록에서 만든다. concert 응답에도 같은 값이 있지만
   * 그건 목록 화면을 위한 요약이라 여기서는 회차와 어긋날 수 있다.
   */
  const list = schedules.data ?? [];
  const scheduleCount = list.length;
  const dates = list.map((schedule) => schedule.scheduleDate).sort();
  const period =
    dates.length === 0
      ? "일정 준비 중"
      : dates[0] === dates[dates.length - 1]
        ? concertDate(dates[0])
        : `${concertDate(dates[0])} – ${concertDate(dates[dates.length - 1])}`;
  const remaining = list.reduce((sum, schedule) => sum + schedule.availableSeats, 0);
  const capacity = list.reduce((sum, schedule) => sum + schedule.totalSeats, 0);

  return (
    <main id="main-content" className="detail-page">
      <Link className="back-link" to="/"><ArrowLeft aria-hidden="true" /> 공연 목록</Link>
      {/*
       * 포스터 왼쪽, 정보 오른쪽. NOL 티켓과 예스24가 공통으로 쓰는 구조다.
       * 전에는 글자만 있었고 제목이 134px이라 화면 하나를 제목이 다 먹었다.
       */}
      <header className="concert-hero">
        <Poster title={concert.data.title} artist={concert.data.artist} width={480} eager />
        <div className="concert-info">
          <p className="eyebrow">{concert.data.artist}</p>
          <h1>{concert.data.title}</h1>
          {/* 고르기 전에 알아야 하는 것들. 문장으로 흘리면 훑어볼 수 없어 표로 둔다 */}
          <dl className="concert-facts">
            <div>
              <dt>장소</dt>
              <dd><MapPin aria-hidden="true" size={15} />{concert.data.venue}</dd>
            </div>
            <div>
              <dt>기간</dt>
              <dd>{period}</dd>
            </div>
            <div>
              <dt>회차</dt>
              <dd>{scheduleCount}회</dd>
            </div>
            <div>
              <dt>잔여</dt>
              <dd>
                {remaining === 0
                  ? "매진"
                  : `${remaining.toLocaleString("ko-KR")}석 / ${capacity.toLocaleString("ko-KR")}석`}
              </dd>
            </div>
          </dl>
          <p className="concert-desc">{concert.data.description}</p>
        </div>
      </header>

      <section className="schedule-list" aria-labelledby="schedule-title">
        <div className="section-heading compact">
          <p className="eyebrow">날짜와 시간</p>
          <h2 id="schedule-title">일정 선택</h2>
        </div>
        {schedules.data?.map((schedule) => (
          <button
            className="schedule-row"
            type="button"
            key={schedule.id}
            disabled={schedule.availableSeats === 0}
            onClick={() => startBooking(schedule.id)}
          >
            <time dateTime={`${schedule.scheduleDate}T${schedule.startTime}`}>
              {concertDate(schedule.scheduleDate, schedule.startTime)}
            </time>
            <span>
              {schedule.availableSeats === 0
                ? "매진"
                : `${schedule.availableSeats.toLocaleString("ko-KR")}석 남음`}
            </span>
            <strong>{schedule.availableSeats === 0 ? "선택 불가" : "예매하기"}</strong>
            <ArrowRight aria-hidden="true" />
          </button>
        ))}
      </section>
    </main>
  );
}
