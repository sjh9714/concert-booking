import { useQuery } from "@tanstack/react-query";
import { ArrowRight } from "lucide-react";
import { Link, Navigate } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { apiFetch } from "../lib/api";
import { currency, dateTime } from "../lib/format";
import { reservationListSchema } from "../lib/contracts";

const STATUS = {
  PENDING: "결제 대기",
  CONFIRMED: "예매 확정",
  CANCELLED: "취소됨",
  EXPIRED: "시간 만료",
} as const;

export function ReservationsPage() {
  const { session } = useAuth();
  const reservations = useQuery({
    queryKey: ["reservations"],
    queryFn: () =>
      apiFetch("/api/reservations/my", {
        token: session?.token,
        schema: reservationListSchema,
      }),
    enabled: Boolean(session),
  });

  if (!session) return <Navigate to="/login?next=/reservations" replace />;
  if (reservations.isLoading) return <LoadingState label="내 예매를 확인하는 중" />;
  if (reservations.isError) return <ErrorState>예매 목록을 불러오지 못했습니다.</ErrorState>;

  return (
    <main id="main-content" className="reservations-page">
      <header className="list-page-heading">
        <p className="eyebrow">MY RESERVATIONS</p>
        <h1>내 예매</h1>
        <p>결제 대기와 확정된 좌석을 한곳에서 확인합니다.</p>
      </header>
      <section className="reservation-list" aria-label="예매 목록">
        {reservations.data?.map((reservation) => (
          <Link to={`/reservations/${reservation.id}`} className="reservation-row" key={reservation.id}>
            <span className={`status-label ${reservation.status.toLowerCase()}`}>{STATUS[reservation.status]}</span>
            <div>
              <h2>{reservation.concertTitle ?? `예매 #${reservation.id}`}</h2>
              <p>{reservation.venue ?? "공연장 정보는 상세에서 확인하세요."}</p>
            </div>
            <time dateTime={reservation.createdAt}>{dateTime(reservation.createdAt)}</time>
            <strong>{currency(reservation.totalAmount)}</strong>
            <ArrowRight aria-hidden="true" />
          </Link>
        ))}
        {reservations.data?.length === 0 && (
          <div className="empty-state">
            <h2>아직 예매가 없습니다.</h2>
            <p>현재 열려 있는 공연에서 첫 좌석을 선택해보세요.</p>
            <Link className="primary-button" to="/">공연 보기</Link>
          </div>
        )}
      </section>
    </main>
  );
}
