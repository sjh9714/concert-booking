import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight } from "lucide-react";
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
        {concerts.data?.map((concert, index) => (
          <Link className="concert-row" to={`/concerts/${concert.id}`} key={concert.id}>
            <span className="concert-index">{String(index + 1).padStart(2, "0")}</span>
            <div className="concert-primary">
              <span>{concert.artist}</span>
              <h3>{concert.title}</h3>
            </div>
            <p>{concert.venue}</p>
            <ArrowUpRight aria-hidden="true" />
          </Link>
        ))}
        {concerts.data?.length === 0 && (
          <p className="empty-copy">지금 예매 가능한 공연이 없습니다.</p>
        )}
      </section>
    </main>
  );
}
