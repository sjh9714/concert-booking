import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, UNAUTHORIZED_EVENT } from "./api";

describe("apiFetch authentication cleanup", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it("clears auth, queue, and idempotency state on every 401", async () => {
    sessionStorage.setItem("ticketline.auth", "auth");
    sessionStorage.setItem("ticketline.queue.1", "queue");
    sessionStorage.setItem("ticketline.idempotency.reservation.1", "key");
    const unauthorized = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, unauthorized, { once: true });
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(
      JSON.stringify({ code: "UNAUTHORIZED", message: "로그인이 필요합니다." }),
      { status: 401, headers: { "Content-Type": "application/json" } },
    ));

    await expect(apiFetch("/api/reservations/my", { token: "expired" }))
      .rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });

    expect(sessionStorage.getItem("ticketline.auth")).toBeNull();
    expect(sessionStorage.getItem("ticketline.queue.1")).toBeNull();
    expect(sessionStorage.getItem("ticketline.idempotency.reservation.1")).toBeNull();
    expect(unauthorized).toHaveBeenCalledOnce();
  });
});
