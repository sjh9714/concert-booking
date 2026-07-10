import { currency, seatLabel } from "../lib/format";
import type { Seat } from "../lib/contracts";

type SeatGridProps = {
  seats: Seat[];
  selectedIds: number[];
  onChange: (selectedIds: number[]) => void;
  maxSelection?: number;
  disabled?: boolean;
};

export function SeatGrid({
  seats,
  selectedIds,
  onChange,
  maxSelection = 4,
  disabled = false,
}: SeatGridProps) {
  const sections = groupBy(seats, (seat) => seat.section);

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
      {[...sections.entries()].map(([section, sectionSeats]) => {
        const rows = groupBy(sectionSeats, (seat) => seat.rowNumber);
        return (
          <section className="seat-section" key={section} aria-labelledby={`section-${section}`}>
            <div className="seat-section-heading">
              <h2 id={`section-${section}`}>{section}</h2>
              <span>{currency(sectionSeats[0]?.price ?? 0)}</span>
            </div>
            {[...rows.entries()].map(([row, rowSeats]) => (
              <div className="seat-row" key={row}>
                <span className="row-label">{row}열</span>
                <div className="seat-buttons">
                  {rowSeats.map((seat) => {
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
                    return (
                      <button
                        key={seat.id}
                        type="button"
                        className={`seat ${selected ? "selected" : ""} ${unavailable ? "unavailable" : ""} ${disabled ? "refreshing" : ""}`}
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
