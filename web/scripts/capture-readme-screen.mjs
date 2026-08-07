/**
 * README에 넣을 제품 화면을 찍는다.
 *
 * 왜 스크립트로 두는가: README의 화면은 CI의 "Check docs evidence policy"가 존재를 강제한다.
 * 화면이 바뀌면 다시 찍어야 하는데, 손으로 찍으면 매번 조건이 달라져 재현되지 않는다.
 * 여기서 조건을 고정한다.
 *
 * 좌석을 실제로 하나 골라 둔 상태를 찍는다. 이 프로젝트의 이야기가 좌석 경합에 있는데,
 * 아무것도 고르지 않은 좌석표는 그 이야기를 하지 않는다.
 *
 * 실행:
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait web
 *   (cd web && node scripts/capture-readme-screen.mjs)
 */
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const WEB = process.env.CAPTURE_WEB_URL ?? "http://localhost:4173";
// 실행 위치가 아니라 스크립트 위치를 기준으로 잡는다.
// playwright가 web/node_modules에만 있어 web/에서 실행하게 되는데,
// cwd 기준으로 두면 web/docs/ 아래에 떨어진다.
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../docs/assets/screens");
const VIEWPORT = { width: 1280, height: 900 };

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: VIEWPORT, deviceScaleFactor: 2 });

  await page.goto(`${WEB}/login`);
  await page.getByRole("button", { name: "데모 계정으로 바로 시작" }).click();
  await page.locator(".concert-row").first().click();
  await page.getByRole("button", { name: /예매하기/ }).first().click();
  await page.getByRole("button", { name: "좌석 선택으로 입장" }).click();
  await page.getByRole("button", { name: /선택 가능/ }).first().click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);

  await mkdir(OUT, { recursive: true });
  const out = path.join(OUT, "seat-selection.png");
  await page.screenshot({ path: out, animations: "disabled" });
  console.log(`saved ${path.relative(process.cwd(), out)}`);
} finally {
  await browser.close();
}
