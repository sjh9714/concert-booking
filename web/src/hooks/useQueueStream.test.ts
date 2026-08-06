import { describe, expect, it, vi } from "vitest";
import { MAX_SSE_RECONNECTS, SSE_RECONNECT_BASE_DELAY_MS, waitForReconnect } from "./useQueueStream";

/**
 * 재연결 예산을 고정한다.
 *
 * 페이지 안에 있을 때는 이걸 확인하려면 브라우저를 띄우고 서버를 죽여야 했다.
 * 훅으로 뽑고 나니 값과 대기 함수만 보면 된다.
 */
describe("대기열 스트림 재연결", () => {
  it("무한히 재시도하지 않는다", () => {
    // 예산이 없으면 서버가 죽었을 때 화면이 영원히 "연결 중"으로 남는다
    expect(MAX_SSE_RECONNECTS).toBeGreaterThan(0);
    expect(MAX_SSE_RECONNECTS).toBeLessThanOrEqual(5);
  });

  it("간격이 지수로 늘어난다", () => {
    const delays = Array.from(
      { length: MAX_SSE_RECONNECTS },
      (_, i) => SSE_RECONNECT_BASE_DELAY_MS * 2 ** i,
    );
    // 같은 간격으로 계속 두드리면 죽어 가는 서버를 더 밀어붙인다
    expect(delays).toEqual([500, 1000, 2000]);
    for (let i = 1; i < delays.length; i += 1) {
      expect(delays[i]).toBeGreaterThan(delays[i - 1]);
    }
    // 마지막 대기까지 합쳐도 4초 안에는 폴링으로 내려앉는다
    expect(delays.reduce((a, b) => a + b, 0)).toBeLessThan(4000);
  });
});

describe("재연결 대기", () => {
  it("정해진 시간만큼 기다린다", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    let done = false;
    void waitForReconnect(1000, controller.signal).then(() => {
      done = true;
    });

    await vi.advanceTimersByTimeAsync(999);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(done).toBe(true);
    vi.useRealTimers();
  });

  it("중단되면 곧바로 끝난다", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    let done = false;
    void waitForReconnect(60_000, controller.signal).then(() => {
      done = true;
    });

    // 언마운트됐는데 1분을 더 자고 있으면 안 된다
    controller.abort();
    await vi.advanceTimersByTimeAsync(0);
    expect(done).toBe(true);
    vi.useRealTimers();
  });

  it("이미 중단된 뒤에 부르면 기다리지 않는다", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(waitForReconnect(60_000, controller.signal)).resolves.toBeUndefined();
  });
});
