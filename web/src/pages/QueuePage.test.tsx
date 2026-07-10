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

function renderQueue(): void {
  render(
    <MemoryRouter initialEntries={["/queue/10?concert=1"]}>
      <Routes>
        <Route path="/queue/:scheduleId" element={<QueuePage />} />
      </Routes>
    </MemoryRouter>,
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
    mocks.apiFetch.mockResolvedValue({
      status: "READY",
      position: 1,
      totalWaiting: 1,
      serverTime: new Date().toISOString(),
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
