import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Clock3, MapPin, TicketCheck } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { Poster } from "../components/Poster";
import { apiFetch } from "../lib/api";
import { concertDate, countdown, countdownLabel, currency, dateTime, seatLabel } from "../lib/format";
import { paymentSchema, reservationDetailSchema } from "../lib/contracts";
import { clearIdempotencyKey, idempotencyKey } from "../lib/session";

const STATUS_COPY = {
  PENDING: "결제 대기",
  CONFIRMED: "예매 확정",
  CANCELLED: "취소됨",
  EXPIRED: "시간 만료",
} as const;

export function ReservationPage() {
  const { reservationId = "" } = useParams();
  const id = Number(reservationId);
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const [remaining, setRemaining] = useState(0);

  const reservation = useQuery({
    queryKey: ["reservation", id],
    queryFn: () =>
      apiFetch(`/api/reservations/${id}`, {
        token: session?.token,
        schema: reservationDetailSchema,
      }),
    enabled: Boolean(session && Number.isFinite(id)),
    refetchInterval: (query) =>
      query.state.data?.status === "PENDING" ? 5000 : false,
  });

  useEffect(() => {
    const tick = () => setRemaining(countdown(reservation.data?.expiresAt ?? null));
    tick();
    const interval = window.setInterval(tick, 1000);
    return () => window.clearInterval(interval);
  }, [reservation.data?.expiresAt]);

  const paymentScope = `payment.${id}`;
  const pay = useMutation({
    mutationFn: () =>
      apiFetch("/api/payments", {
        method: "POST",
        token: session?.token,
        headers: { "Idempotency-Key": idempotencyKey(paymentScope) },
        body: { reservationId: id },
        schema: paymentSchema,
      }),
    onSuccess: () => {
      clearIdempotencyKey(paymentScope);
      void queryClient.invalidateQueries({ queryKey: ["reservation", id] });
      void queryClient.invalidateQueries({ queryKey: ["reservations"] });
    },
  });

  const cancel = useMutation({
    mutationFn: () =>
      apiFetch(`/api/reservations/${id}`, {
        method: "DELETE",
        token: session?.token,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["reservation", id] });
      void queryClient.invalidateQueries({ queryKey: ["reservations"] });
    },
  });

  const seats = useMemo(
    () => reservation.data?.seats.map(seatLabel).join(", ") ?? "",
    [reservation.data?.seats],
  );

  if (!session) return <Navigate to={`/login?next=/reservations/${id}`} replace />;
  if (reservation.isLoading) return <LoadingState label="예매 상태를 확인하는 중" />;
  if (reservation.isError || !reservation.data) return <ErrorState>예매 정보를 찾지 못했습니다.</ErrorState>;

  const data = reservation.data;
  const pending = data.status === "PENDING";
  const expiredLocally = pending && remaining === 0;

  return (
    <main id="main-content" className="reservation-page">
      <header className={`reservation-status ${data.status.toLowerCase()}`}>
        <p className="eyebrow">RESERVATION {String(data.id).padStart(6, "0")}</p>
        <div>
          {data.status === "CONFIRMED" ? <TicketCheck aria-hidden="true" /> : <Clock3 aria-hidden="true" />}
          <h1>{STATUS_COPY[data.status]}</h1>
        </div>
        {pending && (
          <p className="hold-countdown">
            <span>남은 결제 시간</span>
            <strong>{countdownLabel(remaining)}</strong>
          </p>
        )}
      </header>

      <section className="reservation-detail" aria-labelledby="reservation-detail-title">
        {/* 티켓은 포스터로 알아본다 — 목록과 같은 그림이 여기에도 있어야 이어진다 */}
        <div className="ticket-face">
          <div className="ticket-thumb">
            <Poster title={data.concertTitle ?? ""} />
          </div>
          <div>
            <p className="eyebrow">YOUR TICKETS</p>
            <h2 id="reservation-detail-title">{data.concertTitle}</h2>
            <p><MapPin aria-hidden="true" /> {data.venue}</p>
            {data.scheduleDate && (
              // 원본 형식(2026-08-28 20:00:00)이 그대로 나오고 있었다.
              // 티켓에 적히는 날짜는 사람이 읽는 형식이어야 한다.
              <p>{concertDate(data.scheduleDate, data.startTime)}</p>
            )}
          </div>
        </div>
        <dl>
          <div><dt>좌석</dt><dd>{seats}</dd></div>
          <div><dt>예약 시각</dt><dd>{dateTime(data.createdAt)}</dd></div>
          <div><dt>결제 금액</dt><dd>{currency(data.totalAmount)}</dd></div>
        </dl>
      </section>

      {pending && !expiredLocally && (
        <section className="demo-payment" aria-labelledby="demo-payment-title">
          <div>
            <p className="eyebrow">DEMO PAYMENT</p>
            <h2 id="demo-payment-title">데모 결제로 좌석 확정</h2>
            <p>실제 카드나 결제 정보는 받지 않습니다. 버튼을 누르면 테스트 결제가 즉시 완료됩니다.</p>
          </div>
          <button className="primary-button" type="button" disabled={pay.isPending} onClick={() => pay.mutate()}>
            {pay.isPending ? "중복 결제 확인 중" : `${currency(data.totalAmount)} 데모 결제`}
          </button>
          {pay.isError && <p className="form-error" role="alert">{pay.error.message}</p>}
        </section>
      )}

      {data.status === "CONFIRMED" && (
        <section className="confirmation-note">
          <Check aria-hidden="true" />
          <div><strong>좌석이 확정되었습니다.</strong><p>이 화면은 실제 입장권이 아닌 포트폴리오 데모입니다.</p></div>
        </section>
      )}

      <footer className="reservation-actions">
        <Link className="secondary-button" to="/reservations">내 예매 보기</Link>
        {pending && (
          <button className="text-button danger" type="button" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
            {cancel.isPending ? "취소 중" : "이 예약 취소"}
          </button>
        )}
      </footer>
      {cancel.isSuccess && <p className="form-note" role="status">취소 요청이 반영되었습니다. 좌석은 곧 목록에 돌아옵니다.</p>}
    </main>
  );
}
