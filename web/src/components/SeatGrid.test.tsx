import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SeatGrid } from "./SeatGrid";
import type { Seat } from "../lib/contracts";

const seats: Seat[] = [
  { id: 1, section: "VIP", rowNumber: 1, seatNumber: 1, price: 150000, status: "AVAILABLE" },
  { id: 2, section: "VIP", rowNumber: 1, seatNumber: 2, price: 150000, status: "HELD" },
  { id: 3, section: "VIP", rowNumber: 1, seatNumber: 3, price: 150000, status: "AVAILABLE" },
];

describe("SeatGrid", () => {
  it("exposes complete seat state and selects an available seat", async () => {
    const onChange = vi.fn();
    render(<SeatGrid seats={seats} selectedIds={[]} onChange={onChange} />);

    const available = screen.getByRole("button", { name: /VIP 1열 1번.*선택 가능/ });
    const held = screen.getByRole("button", { name: /VIP 1열 2번.*다른 사용자가 선택 중/ });

    expect(held).toBeDisabled();
    await userEvent.click(available);
    expect(onChange).toHaveBeenCalledWith([1]);
  });

  it("does not add more than four seats", async () => {
    const manySeats = Array.from({ length: 5 }, (_, index): Seat => ({
      id: index + 1,
      section: "A",
      rowNumber: 1,
      seatNumber: index + 1,
      price: 120000,
      status: "AVAILABLE",
    }));
    const onChange = vi.fn();
    render(<SeatGrid seats={manySeats} selectedIds={[1, 2, 3, 4]} onChange={onChange} />);

    await userEvent.click(screen.getByRole("button", { name: /A 1열 5번/ }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
