import { currency, seatLabel } from "../lib/format";
import type { Seat } from "../lib/contracts";

type SeatGridProps = {
  seats: Seat[];
  selectedIds: number[];
  onChange: (selectedIds: number[]) => void;
  maxSelection?: number;
  disabled?: boolean;
};

/**
 * 좌석표.
 *
 * 전에는 구역이 API가 준 순서 그대로 그려졌다. 그 순서는 이름순이라
 * **가장 싼 A구역이 무대 바로 아래**에 왔다. 좌석표에서 위아래는 무대와의 거리를 뜻하므로
 * 그건 그냥 틀린 그림이다.
 *
 * 구역 순서를 값으로 정한다 — 비싼 구역이 무대에 가깝다. 구역 이름을 하드코딩하지 않으므로
 * 공연장 배치가 바뀌어도 따라간다.
 *
 * 좌석은 작은 칸이다. 전에는 한 칸이 버튼만 해서 20석짜리 열이 화면을 넘어 잘렸다.
 * 실제 좌석표는 한 화면에 공연장 전체가 보여야 어디가 남았는지 알 수 있다.
 */
export function SeatGrid({
  seats,
  selectedIds,
  onChange,
  maxSelection = 4,
  disabled = false,
}: SeatGridProps) {
  const sections = [...groupBy(seats, (seat) => seat.section).entries()].sort(
    ([, a], [, b]) => (b[0]?.price ?? 0) - (a[0]?.price ?? 0),
  );

  const toggle = (seat: Seat) => {
    if (disabled || seat.status !== "AVAILABLE") return;
    if (selectedIds.includes(seat.id)) {
      onChange(selectedIds.filter((id) => id !== seat.id));
      return;
    }
    if (selectedIds.length < maxSelection) onChange([...selectedIds, seat.id]);
  };

  return (
    <div className="seat-workspace" aria-busy={disabled}>
      <div className="stage" aria-label="무대 위치">
        STAGE
      </div>
      {sections.map(([section, sectionSeats]) => {
        const rows = groupBy(sectionSeats, (seat) => seat.rowNumber);
        const left = sectionSeats.filter((seat) => seat.status === "AVAILABLE").length;
        return (
          <section className="seat-section" key={section} aria-labelledby={`section-${section}`}>
            <div className="seat-section-heading">
              <h2 id={`section-${section}`}>{section}</h2>
              {/* 남은 수를 구역마다 적는다. 좌석표만 봐서는 몇 석 남았는지 셀 수 없다. */}
              <span className="seat-section-left">
                {left > 0 ? `${left}석 남음` : "매진"}
              </span>
              <span>{currency(sectionSeats[0]?.price ?? 0)}</span>
            </div>
            {/*
              좁은 화면에서는 좌석표가 가로로 스크롤된다. 20석 열은 폰 너비에 들어가지 않는데,
              열을 접으면 좌석표가 아니라 격자가 되어 어디가 어디인지 읽을 수 없다.
              공연장 도면은 접는 것보다 미는 편이 맞다.
            */}
            <div className="seat-rows">
            {[...rows.entries()].map(([row, rowSeats]) => (
              <div className="seat-row" key={row}>
                <span className="row-label">{row}</span>
                <div className="seat-buttons">
                  {rowSeats.map((seat, index) => {
                    const selected = selectedIds.includes(seat.id);
                    const unavailable = seat.status !== "AVAILABLE";
                    const stateLabel = disabled && !unavailable
                      ? "좌석 상태 확인 중"
                      : selected
                      ? "선택됨"
                      : seat.status === "AVAILABLE"
                        ? "선택 가능"
                        : seat.status === "HELD"
                          ? "다른 사용자가 선택 중"
                          : "예매 완료";
                    // 실제 공연장에는 가운데 통로가 있다. 통로가 없으면 좌석표가
                    // 그냥 격자로 보이고 어디가 가운데인지 읽히지 않는다.
                    const aisle = index > 0 && index === Math.floor(rowSeats.length / 2);
                    return (
                      <button
                        key={seat.id}
                        type="button"
                        className={`seat ${selected ? "selected" : ""} ${unavailable ? "unavailable" : ""} ${disabled ? "refreshing" : ""} ${aisle ? "after-aisle" : ""}`}
                        disabled={unavailable || disabled}
                        aria-pressed={selected}
                        aria-label={`${seatLabel(seat)}, ${currency(seat.price)}, ${stateLabel}`}
                        onClick={() => toggle(seat)}
                      >
                        {seat.seatNumber}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
            </div>
          </section>
        );
      })}
      <div className="seat-legend" aria-label="좌석 상태 안내">
        <span><i className="legend-dot available" />선택 가능</span>
        <span><i className="legend-dot selected" />선택함</span>
        <span><i className="legend-dot unavailable" />선택 불가</span>
      </div>
    </div>
  );
}

function groupBy<T, K>(items: T[], keyOf: (item: T) => K): Map<K, T[]> {
  const groups = new Map<K, T[]>();
  items.forEach((item) => {
    const key = keyOf(item);
    groups.set(key, [...(groups.get(key) ?? []), item]);
  });
  return groups;
}
