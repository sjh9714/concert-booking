import { describe, expect, it } from "vitest";
import { queuePositionSchema, queueTokenSchema } from "./contracts";

describe("queue contracts", () => {
  it("parses the explicit READY state", () => {
    expect(
      queuePositionSchema.parse({
        status: "READY",
        position: 1,
        totalWaiting: 8,
        serverTime: "2026-07-10T16:00:00+09:00",
      }).status,
    ).toBe("READY");
  });

  it("requires token expiry so the client cannot reuse an unknown token", () => {
    expect(() => queueTokenSchema.parse({ token: "token", scheduleId: 1 })).toThrow();
  });
});
