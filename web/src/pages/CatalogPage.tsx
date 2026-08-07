import { useQuery } from "@tanstack/react-query";
import { concertDate } from "../lib/format";
import { Link } from "react-router-dom";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { Poster } from "../components/Poster";
import { apiFetch } from "../lib/api";
import { concertListSchema } from "../lib/contracts";

/**
 * 공연 목록.
 *
 * 전에는 히어로가 첫 화면을 다 먹고 그 아래에 01·02·03 텍스트 목록이 있었다.
 * NOL 티켓과 예스24를 실측해 보니 한 화면에 이미지가 91개·118개 있고 목록은
 * 전부 포스터 격자였다. 우리 화면에는 이미지가 0개였다(`web/DESIGN.md`).
 *
 * 예매하는 사람은 포스터로 공연을 알아본다. 그래서 격자로 바꾸고 히어로를 줄여
 * 첫 화면에 공연이 보이게 한다.
 */
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
      {/*
       * 표어를 두지 않는다. 실측한 두 서비스 모두 목록 위에 홍보 문구가 없고
       * 바로 공연부터 나온다 — 예매하러 온 사람에게 첫 화면은 공연이다.
       * 서비스가 무엇을 보장하는지는 머리글의 '예매가 안전한 이유'가 말한다.
       */}
      <section className="catalog-list" aria-labelledby="concert-list-title">
        <div className="section-heading">
          <h1 id="concert-list-title">예매 중인 공연</h1>
          <span>{concerts.data?.length ?? 0}건</span>
        </div>

        <ul className="concert-grid">
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
                  ? { label: "매진임박", tone: "low" }
                  : { label: "예매중", tone: "open" };
            const dates = concert.nextScheduleDate
              ? concert.lastScheduleDate && concert.lastScheduleDate !== concert.nextScheduleDate
                ? `${concertDate(concert.nextScheduleDate)} – ${concertDate(concert.lastScheduleDate)}`
                : concertDate(concert.nextScheduleDate)
              : "일정 준비 중";

            return (
              <li key={concert.id}>
                <Link className="concert-card" to={`/concerts/${concert.id}`}>
                  {/* 첫 줄(3열)은 즉시, 나머지는 스크롤할 때 받는다 */}
                  <Poster title={concert.title} artist={concert.artist} eager={index < 3} />
                  <div className="concert-card-body">
                    <span className={`badge ${status.tone}`}>{status.label}</span>
                    <strong className="concert-card-title">{concert.title}</strong>
                    <p className="concert-card-venue">{concert.venue}</p>
                    <p className="concert-card-when">
                      {dates}
                      {concert.scheduleCount > 1 ? ` · ${concert.scheduleCount}회차` : ""}
                    </p>
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>

        {concerts.data?.length === 0 && (
          <p className="empty-copy">지금 예매 가능한 공연이 없습니다.</p>
        )}
      </section>
    </main>
  );
}
