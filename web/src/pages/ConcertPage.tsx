import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, MapPin } from "lucide-react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { ErrorState, LoadingState } from "../components/AsyncState";
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

  return (
    <main id="main-content" className="detail-page">
      <Link className="back-link" to="/"><ArrowLeft aria-hidden="true" /> 공연 목록</Link>
      <header className="concert-hero">
        <div>
          <p className="eyebrow">{concert.data.artist}</p>
          <h1>{concert.data.title}</h1>
          <p>{concert.data.description}</p>
        </div>
        <div className="venue-block">
          <MapPin aria-hidden="true" />
          <span>VENUE</span>
          <strong>{concert.data.venue}</strong>
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
