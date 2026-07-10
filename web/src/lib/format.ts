import { differenceInSeconds, format, parseISO } from "date-fns";
import { ko } from "date-fns/locale";

export function currency(value: number): string {
  return new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(value);
}

export function concertDate(date: string, time?: string): string {
  const parsed = parseISO(`${date}T${time ?? "00:00:00"}`);
  return format(parsed, time ? "M월 d일 (EEE) HH:mm" : "M월 d일 (EEE)", { locale: ko });
}

export function dateTime(value: string): string {
  return format(parseISO(value), "M월 d일 HH:mm", { locale: ko });
}

export function countdown(expiresAt: string | null): number {
  if (!expiresAt) return 0;
  return Math.max(0, differenceInSeconds(parseISO(expiresAt), new Date()));
}

export function countdownLabel(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

export function seatLabel(
  seat: Pick<import("./contracts").Seat, "section" | "rowNumber" | "seatNumber">,
): string {
  return `${seat.section} ${seat.rowNumber}열 ${seat.seatNumber}번`;
}
