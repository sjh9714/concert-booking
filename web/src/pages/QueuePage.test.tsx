import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { FetchEventSourceInit } from "@microsoft/fetch-event-source";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { QueuePage } from "./QueuePage";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  fetchEventSource: vi.fn(),
  handleUnauthorized: vi.fn(),
  session: {
    token: "test-token",
    user: { userId: 1, email: "queue@test.com", nickname: "큐테스터" },
  },
}));

vi.mock("@microsoft/fetch-event-source", () => ({
  fetchEventSource: mocks.fetchEventSource,
}));

vi.mock("../lib/api", () => ({
  apiFetch: mocks.apiFetch,
  apiUrl: (path: string) => path,
  handleUnauthorized: mocks.handleUnauthorized,
}));

vi.mock("../auth/useAuth", () => ({
  useAuth: () => ({
    session: mocks.session,
    checking: false,
    login: vi.fn(),
    signup: vi.fn(),
    demoLogin: vi.fn(),
    logout: vi.fn(),
  }),
}));

type StreamAttempt = {
  options: FetchEventSourceInit;
  resolve: () => void;
};

/*
 * 대기실이 "무엇을 기다리는지"(공연명·회차)를 보여주면서 react-query를 쓰게 됐다.
 * 그 조회는 대기열과 무관하고 실패해도 화면은 그대로 돌지만, 제공자는 있어야 한다.
 *
 * 재시도를 끈다 — 이 테스트에서 공연 조회는 어차피 붙지 않고,
 * 켜 두면 실패한 조회가 배경에서 계속 돌아 테스트가 느려진다.
 */
function renderQueue(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/queue/10?concert=1"]}>
        <Routes>
          <Route path="/queue/:scheduleId" element={<QueuePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function openStream(options: FetchEventSourceInit): Promise<void> {
  return options.onopen?.(
    new Response(null, {
      status: 200,
      headers: { "content-type": "text/event-stream" },
    }),
  ) as Promise<void>;
}

describe("QueuePage SSE lifecycle", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    /*
     * 경로마다 다른 것을 돌려준다. 전에는 무엇을 물어도 대기열 응답을 돌려줬는데,
     * 대기실이 공연·회차도 조회하게 되면서 회차 목록 자리에 객체가 와
     * `.find`가 함수가 아니라는 오류가 났다. 진짜 apiFetch는 스키마로 검증하므로
     * 이런 응답이 나올 수 없다 — mock이 실제와 달랐던 것이다.
     */
    mocks.apiFetch.mockImplementation((path: string) => {
      if (path.includes("/schedules")) return Promise.resolve([]);
      if (path.startsWith("/api/concerts/")) {
        return Promise.resolve({
          id: 1,
          title: "종이비행기",
          venue: "마포아트센터 아트홀",
          artist: "이한결",
          description: "",
          scheduleCount: 0,
          availableSeats: 0,
          totalSeats: 0,
        });
      }
      return Promise.resolve({
        status: "READY",
        position: 1,
        totalWaiting: 1,
        serverTime: new Date().toISOString(),
      });
    });
  });

  it("reconnects after a normal close and returns to live state", async () => {
    const attempts: StreamAttempt[] = [];
    mocks.fetchEventSource.mockImplementation(
      (_url: string, options: FetchEventSourceInit) =>
        new Promise<void>((resolve) => {
          attempts.push({ options, resolve });
        }),
    );

    renderQueue();
    await waitFor(() => expect(attempts).toHaveLength(1));
    await act(() => openStream(attempts[0].options));
    expect(screen.getByText("실시간 연결")).toBeInTheDocument();

    await act(async () => {
      attempts[0].options.onclose?.();
      attempts[0].resolve();
    });
    expect(screen.getByText("연결 중")).toBeInTheDocument();

    await waitFor(() => expect(attempts).toHaveLength(2), { timeout: 1_500 });
    await act(() => openStream(attempts[1].options));
    expect(screen.getByText("실시간 연결")).toBeInTheDocument();
    expect(mocks.fetchEventSource).toHaveBeenCalledTimes(2);
  });

  it("falls back to polling after three terminal reconnect failures", async () => {
    mocks.fetchEventSource.mockImplementation(
      (_url: string, options: FetchEventSourceInit) =>
        Promise.resolve().then(() => {
          options.onerror?.(new Error("network unavailable"));
        }),
    );

    renderQueue();

    expect(
      await screen.findByText("자동 새로고침", {}, { timeout: 6_000 }),
    ).toBeInTheDocument();
    expect(mocks.fetchEventSource).toHaveBeenCalledTimes(4);
    await waitFor(() =>
      expect(mocks.apiFetch).toHaveBeenCalledWith(
        "/api/queue/position?scheduleId=10",
        expect.objectContaining({ token: "test-token" }),
      ),
    );
  });

  it("lets a user re-enter when the server reports NOT_JOINED", async () => {
    let streamOptions: FetchEventSourceInit | undefined;
    mocks.fetchEventSource.mockImplementation(
      (_url: string, options: FetchEventSourceInit) => {
        streamOptions = options;
        return new Promise<void>(() => undefined);
      },
    );

    renderQueue();
    await waitFor(() => expect(streamOptions).toBeDefined());
    act(() => {
      streamOptions?.onmessage?.({
        id: "",
        event: "POSITION",
        data: JSON.stringify({
          status: "NOT_JOINED",
          position: null,
          totalWaiting: 0,
        }),
      });
    });

    const reenter = screen.getByRole("button", { name: "대기열 다시 참여" });
    expect(reenter).toBeEnabled();
    fireEvent.click(reenter);
    await waitFor(() =>
      expect(mocks.apiFetch).toHaveBeenLastCalledWith(
        "/api/queue/enter",
        expect.objectContaining({
          method: "POST",
          token: "test-token",
          body: { scheduleId: 10 },
        }),
      ),
    );
  });

  it("handles a stream 401 once without scheduling a reconnect", async () => {
    mocks.fetchEventSource.mockImplementation(
      async (_url: string, options: FetchEventSourceInit) => {
        try {
          await options.onopen?.(
            new Response(null, {
              status: 401,
              headers: { "content-type": "application/json" },
            }),
          );
        } catch (error) {
          options.onerror?.(error);
        }
      },
    );

    renderQueue();
    await waitFor(() => expect(mocks.handleUnauthorized).toHaveBeenCalledOnce());
    await new Promise((resolve) => window.setTimeout(resolve, 650));
    expect(mocks.fetchEventSource).toHaveBeenCalledOnce();
  });
});
