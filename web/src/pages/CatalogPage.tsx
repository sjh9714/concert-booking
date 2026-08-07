import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight } from "lucide-react";
import { concertDate } from "../lib/format";
import { Link } from "react-router-dom";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { apiFetch } from "../lib/api";
import { concertListSchema } from "../lib/contracts";

export function CatalogPage() {
  const concerts = useQuery({
    queryKey: ["concerts"],
    queryFn: () => apiFetch("/api/concerts", { schema: concertListSchema }),
  });

  if (concerts.isLoading) return <LoadingState label="공연을 준비하는 중" />;
  if (concerts.isError) {
    return (
      <ErrorState action={<button onClick={() => void concerts.refetch()}>다시 시도</button>}>
        공연 목록을 가져오지 못했습니다.
      </ErrorState>
    );
  }

  return (
    <main id="main-content">
      <section className="catalog-intro">
        <div className="catalog-copy">
          <p className="eyebrow">LIVE / SEOUL</p>
          <h1>좋은 자리를<br />놓치지 않는 흐름.</h1>
          <p className="intro-copy">
            대기 순서를 지키고, 선택한 좌석은 제한 시간 안에 안전하게 확정합니다.
          </p>
        </div>
        <div className="venue-mark" aria-hidden="true">
          <span>01</span>
          <div className="venue-ring"><i /></div>
          <small>DOORS OPEN</small>
        </div>
      </section>

      <section className="catalog-list" aria-labelledby="concert-list-title">
        <div className="section-heading">
          <p className="eyebrow">현재 예매 가능</p>
          <h2 id="concert-list-title">공연</h2>
          <span>{String(concerts.data?.length ?? 0).padStart(2, "0")}</span>
        </div>
        {concerts.data?.map((concert, index) => {
          /*
           * 예매 목록에서 사람이 고르려면 언제 하는지와 자리가 남았는지를 알아야 한다.
           * 전에는 제목·장소·아티스트만 있어서 목록만 보고는 아무것도 고를 수 없었다.
           *
           * 남은 비율로 상태를 말한다 — 숫자만 적으면 436 중 40이 많은지 적은지 모른다.
           */
          const ratio = concert.totalSeats > 0 ? concert.availableSeats / concert.totalSeats : 0;
          const status =
            concert.availableSeats === 0
              ? { label: "매진", tone: "soldout" }
              : ratio < 0.15
                ? { label: "매진 임박", tone: "low" }
                : { label: "예매 가능", tone: "open" };
          const dates = concert.nextScheduleDate
            ? concert.lastScheduleDate && concert.lastScheduleDate !== concert.nextScheduleDate
              ? `${concertDate(concert.nextScheduleDate)} – ${concertDate(concert.lastScheduleDate)}`
              : concertDate(concert.nextScheduleDate)
            : "일정 준비 중";
          return (
            <Link className="concert-row" to={`/concerts/${concert.id}`} key={concert.id}>
              <span className="concert-index">{String(index + 1).padStart(2, "0")}</span>
              <div className="concert-primary">
                <span>{concert.artist}</span>
                <h3>{concert.title}</h3>
                <p className="concert-when">
                  {dates}
                  {concert.scheduleCount > 1 ? ` · ${concert.scheduleCount}회차` : ""}
                </p>
              </div>
              <div className="concert-meta">
                <p>{concert.venue}</p>
                <p className={`concert-status ${status.tone}`}>
                  <i aria-hidden="true" />
                  {status.label}
                  {concert.availableSeats > 0 ? ` · ${concert.availableSeats.toLocaleString("ko-KR")}석` : ""}
                </p>
              </div>
              <ArrowUpRight aria-hidden="true" />
            </Link>
          );
        })}
        {concerts.data?.length === 0 && (
          <p className="empty-copy">지금 예매 가능한 공연이 없습니다.</p>
        )}
      </section>
    </main>
  );
}
