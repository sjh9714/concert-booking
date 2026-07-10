import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Clock3 } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { ErrorState, LoadingState } from "../components/AsyncState";
import { SeatGrid } from "../components/SeatGrid";
import { ApiError, apiFetch } from "../lib/api";
import { currency, seatLabel } from "../lib/format";
import { reservationSummarySchema, seatListSchema } from "../lib/contracts";
import {
  clearIdempotencyKey,
  clearQueueToken,
  idempotencyKey,
  readQueueToken,
} from "../lib/session";

export function SeatsPage() {
  const { scheduleId = "" } = useParams();
  const schedule = Number(scheduleId);
  const [search] = useSearchParams();
  const concertId = Number(search.get("concert"));
  const { session } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [leaving, setLeaving] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const queueToken = Number.isFinite(schedule) ? readQueueToken(schedule) : null;

  const seats = useQuery({
    queryKey: ["seats", concertId, schedule],
    queryFn: () =>
      apiFetch(`/api/concerts/${concertId}/schedules/${schedule}/seats`, {
        token: session?.token,
        schema: seatListSchema,
      }),
    enabled: Boolean(session && queueToken && Number.isFinite(concertId)),
    refetchInterval: 5000,
  });

  const selected = useMemo(
    () => seats.data?.filter((seat) => selectedIds.includes(seat.id)) ?? [],
    [seats.data, selectedIds],
  );
  const total = selected.reduce((sum, seat) => sum + seat.price, 0);

  const reserve = useMutation({
    mutationFn: async () => {
      if (!session || !queueToken) throw new Error("입장 정보가 만료되었습니다.");
      const fingerprint = `${schedule}:${[...selectedIds].sort((a, b) => a - b).join("-")}`;
      return apiFetch("/api/reservations", {
        method: "POST",
        token: session.token,
        headers: { "Idempotency-Key": idempotencyKey(`reservation.${fingerprint}`) },
        body: { scheduleId: schedule, seatIds: selectedIds, queueToken: queueToken.token },
        schema: reservationSummarySchema,
      });
    },
    onSuccess: (reservation) => {
      setLeaving(true);
      clearQueueToken(schedule);
      clearIdempotencyKey(`reservation.${schedule}:${[...selectedIds].sort((a, b) => a - b).join("-")}`);
      navigate(`/reservations/${reservation.id}`);
    },
    onError: async (error) => {
      if (error instanceof ApiError && [400, 401, 403].includes(error.status)) {
        clearQueueToken(schedule);
      }
      setSelectedIds([]);
      setRecovering(true);
      try {
        await queryClient.refetchQueries({
          queryKey: ["seats", concertId, schedule],
          type: "active",
        });
      } finally {
        setRecovering(false);
      }
    },
  });

  if (!session) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (leaving) return <LoadingState label="예매 상세로 이동하는 중" />;
  if (!queueToken) return <Navigate to={`/queue/${schedule}${Number.isFinite(concertId) ? `?concert=${concertId}` : ""}`} replace />;
  if (seats.isLoading) return <LoadingState label="좌석 상태를 확인하는 중" />;
  if (seats.isError || !seats.data) return <ErrorState>좌석 정보를 불러오지 못했습니다.</ErrorState>;

  return (
    <main id="main-content" className="seats-page">
      <header className="seat-page-heading">
        <Link className="back-link" to={`/concerts/${concertId}`}><ArrowLeft aria-hidden="true" /> 일정 다시 선택</Link>
        <div>
          <p className="eyebrow">SELECT YOUR SEATS</p>
          <h1>좌석 선택</h1>
          <p><Clock3 aria-hidden="true" /> 최대 4석 · 상태는 5초마다 갱신됩니다.</p>
        </div>
      </header>

      <SeatGrid
        seats={seats.data}
        selectedIds={selectedIds}
        onChange={setSelectedIds}
        disabled={recovering}
      />

      <aside className="selection-bar" aria-live="polite">
        <div>
          <span>{selected.length}석 선택</span>
          <p>{selected.length ? selected.map(seatLabel).join(", ") : "원하는 좌석을 선택하세요."}</p>
        </div>
        <strong>{currency(total)}</strong>
        <button
          className="primary-button"
          type="button"
          disabled={selected.length === 0 || reserve.isPending || recovering}
          onClick={() => reserve.mutate()}
        >
          {reserve.isPending ? "좌석 확인 중" : "이 좌석으로 예매"}
        </button>
      </aside>
      {recovering && (
        <p className="floating-error" role="status">
          최신 좌석 상태를 다시 확인하는 중입니다.
        </p>
      )}
      {reserve.isError && !recovering && (
        <p className="floating-error" role="alert">
          {reserve.error.message} 좌석 상태를 다시 확인했습니다.
        </p>
      )}
    </main>
  );
}
