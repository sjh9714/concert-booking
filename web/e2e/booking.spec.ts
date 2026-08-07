import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:4173";

type Auth = {
  token: string;
  userId: number;
  email: string;
  nickname: string;
};

type Concert = { id: number };
type Schedule = { id: number; concertId: number };
type Seat = {
  id: number;
  section: string;
  rowNumber: number;
  seatNumber: number;
  status: "AVAILABLE" | "HELD" | "RESERVED";
};
type QueueToken = { token: string; scheduleId: number; expiresAt: string };
type Reservation = { id: number; status: string };
type Payment = { id: number; reservationId: number };
type BookingTarget = {
  concert: Concert;
  schedule: Schedule;
  seats: Seat[];
};

function captureClientErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(error.message));
  return errors;
}

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}@example.com`;
}

async function json<T>(response: APIResponse, label: string): Promise<T> {
  if (!response.ok()) {
    throw new Error(`${label} failed (${response.status()}): ${await response.text()}`);
  }
  return response.json() as Promise<T>;
}

function authHeaders(auth: Auth): Record<string, string> {
  return { Authorization: `Bearer ${auth.token}` };
}

async function createUser(request: APIRequestContext, prefix: string): Promise<Auth> {
  const email = uniqueEmail(prefix);
  const password = "password123!";
  const nickname = `${prefix}-${email.slice(-10, -4)}`;
  const signup = await request.post("/api/auth/signup", {
    data: { email, password, nickname },
  });
  expect(signup.status()).toBe(201);
  return json<Auth>(
    await request.post("/api/auth/login", { data: { email, password } }),
    "login",
  );
}

async function bookingTarget(request: APIRequestContext): Promise<BookingTarget> {
  const concerts = await json<Concert[]>(
    await request.get("/api/concerts"),
    "concert list",
  );
  const concert = concerts[0];
  if (!concert) throw new Error("The e2e fixture has no concerts.");
  const schedules = await json<Schedule[]>(
    await request.get(`/api/concerts/${concert.id}/schedules`),
    "schedule list",
  );
  const schedule = schedules[0];
  if (!schedule) throw new Error("The e2e fixture has no schedules.");
  const seats = await json<Seat[]>(
    await request.get(
      `/api/concerts/${concert.id}/schedules/${schedule.id}/seats`,
    ),
    "seat list",
  );
  return { concert, schedule, seats };
}

async function resetPrimarySchedule(
  request: APIRequestContext,
): Promise<BookingTarget> {
  const target = await bookingTarget(request);
  const response = await request.post(
    `/api/admin/load-test/reset?scheduleId=${target.schedule.id}&userCount=2`,
  );
  expect(response.ok(), await response.text()).toBe(true);
  return bookingTarget(request);
}

async function issueQueueToken(
  request: APIRequestContext,
  auth: Auth,
  scheduleId: number,
): Promise<QueueToken> {
  const entered = await request.post("/api/queue/enter", {
    headers: authHeaders(auth),
    data: { scheduleId },
  });
  expect(entered.status()).toBe(201);
  return json<QueueToken>(
    await request.get(`/api/queue/token?scheduleId=${scheduleId}`, {
      headers: authHeaders(auth),
    }),
    "queue token",
  );
}

async function installSession(
  context: BrowserContext,
  auth: Auth,
  queueToken?: QueueToken,
): Promise<void> {
  await context.addInitScript(
    ({ authValue, tokenValue }) => {
      sessionStorage.setItem(
        "ticketline.auth",
        JSON.stringify({
          token: authValue.token,
          user: {
            userId: authValue.userId,
            email: authValue.email,
            nickname: authValue.nickname,
          },
        }),
      );
      if (tokenValue) {
        sessionStorage.setItem(
          `ticketline.queue.${tokenValue.scheduleId}`,
          JSON.stringify(tokenValue),
        );
      }
    },
    { authValue: auth, tokenValue: queueToken },
  );
}

async function createAuthenticatedPage(
  browser: Browser,
  auth: Auth,
  queueToken?: QueueToken,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL: BASE_URL });
  await installSession(context, auth, queueToken);
  return { context, page: await context.newPage() };
}

async function reserveViaApi(
  request: APIRequestContext,
  auth: Auth,
  target: BookingTarget,
  seatId: number,
): Promise<Reservation> {
  const queueToken = await issueQueueToken(
    request,
    auth,
    target.schedule.id,
  );
  return json<Reservation>(
    await request.post("/api/reservations", {
      headers: {
        ...authHeaders(auth),
        "Idempotency-Key": crypto.randomUUID(),
      },
      data: {
        scheduleId: target.schedule.id,
        seatIds: [seatId],
        queueToken: queueToken.token,
      },
    }),
    "reservation",
  );
}

async function waitForSeatStatus(
  request: APIRequestContext,
  target: BookingTarget,
  seatId: number,
  expectedStatus: Seat["status"],
): Promise<void> {
  await expect
    .poll(
      async () => {
        const seats = await json<Seat[]>(
          await request.get(
            `/api/concerts/${target.concert.id}/schedules/${target.schedule.id}/seats`,
          ),
          "seat status",
        );
        return seats.find((seat) => seat.id === seatId)?.status;
      },
      { timeout: 15_000 },
    )
    .toBe(expectedStatus);
}

function skipMobile(projectName: string): void {
  test.skip(projectName === "mobile", "Fault and concurrency journeys run once on desktop.");
}

test("catalog is accessible and opens a concert", async ({ page }) => {
  const clientErrors = captureClientErrors(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("예매 중인 공연");
  const menu = page.getByRole("button", { name: "메뉴 열기" });
  if (await menu.isVisible()) {
    await menu.click();
    await expect(page.getByRole("navigation", { name: "주요 메뉴" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("navigation", { name: "주요 메뉴" })).toBeHidden();
  }
  const firstConcert = page.locator(".concert-card").first();
  await expect(firstConcert).toBeVisible();
  await firstConcert.click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(
    results.violations.filter((violation) =>
      ["serious", "critical"].includes(violation.impact ?? ""),
    ),
  ).toEqual([]);
  expect(clientErrors).toEqual([]);
});

test("correctness explanation opens as a keyboard-dismissible drawer", async ({
  page,
}, testInfo) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  const menu = page.getByRole("button", { name: "메뉴 열기" });
  if (await menu.isVisible()) {
    await menu.click();
    await expect(page.getByRole("navigation", { name: "주요 메뉴" })).toBeVisible();
  }
  const trigger = page.getByRole("button", { name: "예매가 안전한 이유" });
  await trigger.click();
  const drawer = page.getByRole("dialog", {
    name: "예매 흐름이 정확성을 지키는 법",
  });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByText("같은 좌석에는 한 명만")).toBeVisible();
  const results = await new AxeBuilder({ page }).include("#correctness-drawer").analyze();
  expect(
    results.violations.filter((violation) =>
      ["serious", "critical"].includes(violation.impact ?? ""),
    ),
  ).toEqual([]);
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  if (testInfo.project.name === "desktop") await expect(trigger).toBeFocused();
});

test("user can reserve and complete a demo payment", async ({ page, request }) => {
  await resetPrimarySchedule(request);
  const clientErrors = captureClientErrors(page);
  const unique = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  await page.goto("/signup");
  await page.getByLabel("닉네임").fill(`관객-${unique.slice(-6)}`);
  await page.getByLabel("이메일").fill(`${unique}@example.com`);
  await page.getByLabel("비밀번호").fill("password123!");
  await page.getByRole("button", { name: "가입하고 시작" }).click();

  await page.locator(".concert-card").first().click();
  await page.getByRole("button", { name: /예매하기/ }).first().click();
  await expect(page.getByText(/현재 내 순서/)).toBeVisible();
  await page.getByRole("button", { name: "좌석 선택으로 입장" }).click();
  await page.getByRole("button", { name: /선택 가능/ }).first().click();
  await page.getByRole("button", { name: "이 좌석으로 예매" }).click();
  await expect(page.getByText("데모 결제로 좌석 확정")).toBeVisible();
  await page.getByRole("button", { name: /데모 결제/ }).click();
  await expect(page.getByRole("heading", { name: "예매 확정" })).toBeVisible();
  const results = await new AxeBuilder({ page }).analyze();
  expect(
    results.violations.filter((violation) =>
      ["serious", "critical"].includes(violation.impact ?? ""),
    ),
  ).toEqual([]);
  expect(clientErrors).toEqual([]);
});

test("one fixed demo account opens the product without typing credentials", async ({
  page,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  await page.goto("/login");
  await page.getByRole("button", { name: "데모 계정으로 바로 시작" }).click();
  await expect(page).toHaveURL(`${BASE_URL}/`);
  await expect(page.locator(".nav-user")).toHaveText("데모 관객");
});

test("two browsers competing for one seat yield one winner and a recoverable loser", async ({
  browser,
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const [firstAuth, secondAuth] = await Promise.all([
    createUser(request, "race-a"),
    createUser(request, "race-b"),
  ]);
  const [firstToken, secondToken] = await Promise.all([
    issueQueueToken(request, firstAuth, target.schedule.id),
    issueQueueToken(request, secondAuth, target.schedule.id),
  ]);
  const first = await createAuthenticatedPage(browser, firstAuth, firstToken);
  const second = await createAuthenticatedPage(browser, secondAuth, secondToken);

  try {
    const seatsPath = `/seats/${target.schedule.id}?concert=${target.concert.id}`;
    await Promise.all([first.page.goto(seatsPath), second.page.goto(seatsPath)]);
    const firstSeat = first.page.getByRole("button", { name: /선택 가능/ }).first();
    const sameFirstSeat = second.page.getByRole("button", { name: /선택 가능/ }).first();

    /*
     * 다투는 좌석은 **화면에서** 읽는다. 예전에는 API 목록의 첫 AVAILABLE을 썼는데,
     * 좌석표가 구역을 무대와 가까운 순(비싼 순)으로 그리기 시작하면서
     * API 순서(구역 이름순)와 화면 순서가 갈렸다. 두 브라우저는 화면의 첫 좌석을 누르므로
     * API에서 고른 좌석과 다른 자리를 다투게 된다.
     *
     * 사용자가 실제로 누른 좌석을 보는 편이 이 테스트의 뜻에도 맞는다.
     */
    const contestedLabel = (await firstSeat.getAttribute("aria-label"))?.split(",")[0];
    if (!contestedLabel) throw new Error("Could not read the contested seat label.");
    expect(await sameFirstSeat.getAttribute("aria-label")).toContain(contestedLabel);

    await Promise.all([firstSeat.click(), sameFirstSeat.click()]);
    await Promise.all([
      first.page.getByRole("button", { name: "이 좌석으로 예매" }).click(),
      second.page.getByRole("button", { name: "이 좌석으로 예매" }).click(),
    ]);

    const pages = [first.page, second.page];
    await expect
      .poll(
        () =>
          pages.filter((candidate) =>
            new URL(candidate.url()).pathname.startsWith("/reservations/"),
          ).length,
        { timeout: 15_000 },
      )
      .toBe(1);

    const loser = pages.find(
      (candidate) => !new URL(candidate.url()).pathname.startsWith("/reservations/"),
    );
    expect(loser).toBeDefined();
    await expect(loser!.getByRole("alert")).toContainText(
      "좌석 상태를 다시 확인했습니다",
    );
    const staleSeat = loser!.locator(
      `button[aria-label^="${contestedLabel},"]`,
    );
    await expect(staleSeat).toBeDisabled();
    await expect(staleSeat).toHaveAttribute(
      "aria-label",
      /다른 사용자가 선택 중|예매 완료/,
    );
    const recoveredSeat = loser!
      .getByRole("button", { name: /선택 가능/ })
      .first();
    expect(await recoveredSeat.getAttribute("aria-label")).not.toContain(
      contestedLabel,
    );
    await recoveredSeat.click();
    await loser!.getByRole("button", { name: "이 좌석으로 예매" }).click();
    await expect(loser!).toHaveURL(/\/reservations\/\d+$/, { timeout: 15_000 });
  } finally {
    await Promise.all([first.context.close(), second.context.close()]);
  }
});

test("lost queue-token response is replayed and duplicate reservation keys return one reservation", async ({
  page,
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const auth = await createUser(request, "replay");
  await page.addInitScript((authValue: Auth) => {
    sessionStorage.setItem(
      "ticketline.auth",
      JSON.stringify({
        token: authValue.token,
        user: {
          userId: authValue.userId,
          email: authValue.email,
          nickname: authValue.nickname,
        },
      }),
    );
  }, auth);

  let lostToken: QueueToken | null = null;
  let loseFirstResponse = true;
  await page.route("**/api/queue/token?*", async (route) => {
    if (!loseFirstResponse) {
      await route.continue();
      return;
    }
    loseFirstResponse = false;
    const upstream = await route.fetch();
    lostToken = (await upstream.json()) as QueueToken;
    await route.abort("failed");
  });

  await page.goto(
    `/queue/${target.schedule.id}?concert=${target.concert.id}`,
  );
  const enterSeats = page.getByRole("button", { name: "좌석 선택으로 입장" });
  await expect(enterSeats).toBeEnabled();
  await enterSeats.click();
  await expect(page.getByRole("alert")).toBeVisible();
  await enterSeats.click();
  await expect(page).toHaveURL(/\/seats\/\d+/, { timeout: 10_000 });

  const replayedToken = await page.evaluate((scheduleId) => {
    const value = sessionStorage.getItem(`ticketline.queue.${scheduleId}`);
    return value ? (JSON.parse(value) as QueueToken) : null;
  }, target.schedule.id);
  expect(replayedToken?.token).toBe(lostToken?.token);

  if (!replayedToken) throw new Error("No queue token.");

  /*
   * 좌석을 **화면에서 먼저 고르고** 그 좌석으로 멱등성 키를 심는다.
   *
   * 예전에는 API 목록의 첫 AVAILABLE을 썼는데, 좌석표가 구역을 무대와 가까운 순으로
   * 그리기 시작하면서 API 순서(구역 이름순)와 화면 순서가 갈렸다. 그러면 브라우저가 누르는
   * 좌석과 키를 심어 둔 좌석이 달라져, 같은 예약이 아니라 서로 다른 예약 두 건이 된다.
   * 큐 토큰은 한 번만 쓸 수 있으므로 뒤에 온 쪽이 INVALID_QUEUE_TOKEN으로 떨어졌다.
   *
   * 이 테스트가 확인하려는 것은 "같은 멱등성 키로 두 번 요청해도 예약은 하나"이므로
   * 두 요청이 같은 좌석을 가리키는 것이 전제다.
   */
  const seatButton = page.getByRole("button", { name: /선택 가능/ }).first();
  const seatLabel = (await seatButton.getAttribute("aria-label")) ?? "";
  const [, section, row, number] =
    seatLabel.match(/^(\S+) (\d+)열 (\d+)번,/) ?? [];
  const availableSeat = target.seats.find(
    (seat) =>
      seat.section === section &&
      seat.rowNumber === Number(row) &&
      seat.seatNumber === Number(number),
  );
  if (!availableSeat) throw new Error(`Could not match the clicked seat: ${seatLabel}`);

  const idempotency = crypto.randomUUID();
  await page.evaluate(
    ({ scheduleId, seatId, key }) => {
      sessionStorage.setItem(
        `ticketline.idempotency.reservation.${scheduleId}:${seatId}`,
        key,
      );
    },
    {
      scheduleId: target.schedule.id,
      seatId: availableSeat.id,
      key: idempotency,
    },
  );
  await seatButton.click();

  const browserResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/reservations",
  );
  const [, replayResponse] = await Promise.all([
    page.getByRole("button", { name: "이 좌석으로 예매" }).click(),
    request.post("/api/reservations", {
      headers: {
        ...authHeaders(auth),
        "Idempotency-Key": idempotency,
      },
      data: {
        scheduleId: target.schedule.id,
        seatIds: [availableSeat.id],
        queueToken: replayedToken.token,
      },
    }),
  ]);
  const [browserReservation, replayReservation] = await Promise.all([
    json<Reservation>(await browserResponse, "browser reservation"),
    json<Reservation>(replayResponse, "replayed reservation"),
  ]);
  expect(browserReservation.id).toBe(replayReservation.id);
  await expect(page).toHaveURL(
    new RegExp(`/reservations/${replayReservation.id}$`),
  );
});

test("cancelled and expired reservations restore their seats", async ({
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const auth = await createUser(request, "restore");
  const [cancelSeat, expireSeat] = target.seats.filter(
    (seat) => seat.status === "AVAILABLE",
  );
  if (!cancelSeat || !expireSeat) throw new Error("Not enough available e2e seats.");

  const cancelled = await reserveViaApi(
    request,
    auth,
    target,
    cancelSeat.id,
  );
  const cancelResponse = await request.delete(
    `/api/reservations/${cancelled.id}`,
    { headers: authHeaders(auth) },
  );
  expect(cancelResponse.status()).toBe(204);
  await waitForSeatStatus(request, target, cancelSeat.id, "AVAILABLE");

  const expired = await reserveViaApi(request, auth, target, expireSeat.id);
  const expireResponse = await request.post(
    `/api/admin/load-test/reservations/${expired.id}/expire`,
  );
  expect(expireResponse.ok(), await expireResponse.text()).toBe(true);
  await waitForSeatStatus(request, target, expireSeat.id, "AVAILABLE");
});

test("another user cannot read a reservation or its payment", async ({
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const owner = await createUser(request, "owner");
  const stranger = await createUser(request, "stranger");
  const seat = target.seats.find((candidate) => candidate.status === "AVAILABLE");
  if (!seat) throw new Error("No available e2e seat.");
  const reservation = await reserveViaApi(request, owner, target, seat.id);
  const payment = await json<Payment>(
    await request.post("/api/payments", {
      headers: {
        ...authHeaders(owner),
        "Idempotency-Key": crypto.randomUUID(),
      },
      data: { reservationId: reservation.id },
    }),
    "payment",
  );

  for (const path of [
    `/api/reservations/${reservation.id}`,
    `/api/payments/${payment.id}`,
  ]) {
    const response = await request.get(path, { headers: authHeaders(stranger) });
    expect(response.status()).toBe(403);
    expect(await response.json()).toMatchObject({ code: "FORBIDDEN" });
  }
});

test("a normally closed queue stream reconnects and returns to live updates", async ({
  page,
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const auth = await createUser(request, "sse-close");
  await page.addInitScript((authValue: Auth) => {
    sessionStorage.setItem(
      "ticketline.auth",
      JSON.stringify({
        token: authValue.token,
        user: {
          userId: authValue.userId,
          email: authValue.email,
          nickname: authValue.nickname,
        },
      }),
    );
  }, auth);

  let streamRequests = 0;
  let pollRequests = 0;
  page.on("request", (observed) => {
    const url = new URL(observed.url());
    if (url.pathname === "/api/queue/position") pollRequests += 1;
  });
  await page.route("**/api/queue/events?*", async (route) => {
    streamRequests += 1;
    if (streamRequests > 1) {
      await route.continue();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `event: POSITION\ndata: ${JSON.stringify({
        status: "READY",
        position: 1,
        totalWaiting: 1,
      })}\n\n`,
    });
  });

  await page.goto(
    `/queue/${target.schedule.id}?concert=${target.concert.id}`,
  );
  await expect
    .poll(() => streamRequests, { timeout: 3_000 })
    .toBeGreaterThanOrEqual(2);
  await expect(page.getByText("실시간 연결")).toBeVisible();
  expect(pollRequests).toBe(0);
});

test("repeated queue stream failures fall back to polling and NOT_JOINED can re-enter", async ({
  page,
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const auth = await createUser(request, "sse-fallback");
  await page.addInitScript((authValue: Auth) => {
    sessionStorage.setItem(
      "ticketline.auth",
      JSON.stringify({
        token: authValue.token,
        user: {
          userId: authValue.userId,
          email: authValue.email,
          nickname: authValue.nickname,
        },
      }),
    );
  }, auth);

  let streamRequests = 0;
  let pollRequests = 0;
  let enterRequests = 0;
  page.on("request", (observed) => {
    const url = new URL(observed.url());
    if (url.pathname === "/api/queue/enter") enterRequests += 1;
  });
  await page.route("**/api/queue/events?*", async (route) => {
    streamRequests += 1;
    await route.abort("failed");
  });
  await page.route("**/api/queue/position?*", async (route) => {
    pollRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        status: "NOT_JOINED",
        position: null,
        totalWaiting: 0,
      }),
    });
  });

  await page.goto(
    `/queue/${target.schedule.id}?concert=${target.concert.id}`,
  );
  await expect(page.getByText("자동 새로고침")).toBeVisible({
    timeout: 8_000,
  });
  expect(streamRequests).toBe(4);
  await expect.poll(() => pollRequests).toBeGreaterThan(0);

  const reenter = page.getByRole("button", { name: "대기열 다시 참여" });
  await expect(reenter).toBeEnabled();
  await reenter.click();
  await expect.poll(() => enterRequests).toBeGreaterThanOrEqual(2);
  await expect(
    page.getByRole("button", { name: "좌석 선택으로 입장" }),
  ).toBeEnabled();
});

test("a queue-stream 401 clears session immediately and redirects without reconnecting", async ({
  page,
  request,
}, testInfo) => {
  skipMobile(testInfo.project.name);
  const target = await resetPrimarySchedule(request);
  const auth = await createUser(request, "queue-401");
  await page.addInitScript((authValue: Auth) => {
    sessionStorage.setItem(
      "ticketline.auth",
      JSON.stringify({
        token: authValue.token,
        user: {
          userId: authValue.userId,
          email: authValue.email,
          nickname: authValue.nickname,
        },
      }),
    );
  }, auth);
  let streamRequests = 0;
  await page.route("**/api/queue/events?*", async (route) => {
    streamRequests += 1;
    await route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ code: "UNAUTHORIZED", message: "로그인이 필요합니다." }),
    });
  });

  const returnPath = `/queue/${target.schedule.id}?concert=${target.concert.id}`;
  await page.goto(returnPath);
  await expect(page).toHaveURL(/\/login\?next=/);
  expect(streamRequests).toBe(1);
  expect(new URL(page.url()).searchParams.get("next")).toBe(returnPath);
  expect(
    await page.evaluate(() => sessionStorage.getItem("ticketline.auth")),
  ).toBeNull();
});
