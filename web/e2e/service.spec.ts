import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

async function signUp(page: import('@playwright/test').Page) {
  await page.goto('/signup');
  await page.getByLabel('닉네임').fill('서비스 관객');
  await page.getByLabel('이메일').fill(`service-${crypto.randomUUID()}@example.com`);
  await page.getByLabel('비밀번호').fill('password123!');
  await page.getByRole('button', { name: '가입하고 시작' }).click();
  await expect(page.locator('.concert-card').first()).toBeVisible();
}
async function selectSeat(page: import('@playwright/test').Page) {
  await page.locator('.concert-card').first().click();
  await page.getByRole('button', { name: /예매하기/ }).first().click();
  await expect(page).toHaveURL(/\/seats\//);
  const seat = page.getByRole('button', { name: /선택 가능/ }).first();
  const label = await seat.getAttribute('aria-label');
  const seatsUrl = page.url();
  await seat.click();
  await page.getByRole('button', { name: '이 좌석으로 예매' }).click();
  await expect(page.getByRole('heading', { name: '결제 대기' })).toBeVisible();
  return { label, seatsUrl };
}

test('대기열 없이 가입·공연 선택·선점·테스트 결제·확인', async ({ page }, info) => {
  const queueRequests: string[] = [];
  page.on('request', request => { if (request.url().includes('/api/queue/')) queueRequests.push(request.url()); });
  await signUp(page);
  await selectSeat(page);
  await expect(page.getByText('테스트 결제입니다. 카드 정보를 받지 않으며 실제로 청구되지 않습니다.')).toBeVisible();
  await page.getByRole('button', { name: /결제하기/ }).click();
  await expect(page.getByRole('heading', { name: '예매 확정' })).toBeVisible();
  await expect(page.getByRole('button', { name: '이 예약 취소' })).toHaveCount(0);
  expect(queueRequests).toEqual([]);
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: `artifacts/service-payment-${info.project.name}.png`, fullPage: true });
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter(item => ['serious', 'critical'].includes(item.impact ?? ''))).toEqual([]);
});

test('취소 응답 직후 좌석을 다시 선택하고 재예약한다', async ({ page }) => {
  await signUp(page);
  const { label, seatsUrl } = await selectSeat(page);
  const path = new URL(page.url()).pathname;
  await page.getByRole('button', { name: '이 예약 취소' }).click();
  await expect(page.getByRole('heading', { name: '취소됨' })).toBeVisible();
  await expect(page.getByRole('status')).toContainText('좌석이 반환되었습니다');
  await page.goto(seatsUrl);
  const released = page.getByRole('button', { name: label!, exact: true });
  await expect(released).toBeEnabled();
  await released.click();
  await page.getByRole('button', { name: '이 좌석으로 예매' }).click();
  await expect(page.getByRole('heading', { name: '결제 대기' })).toBeVisible();
  expect(new URL(page.url()).pathname).not.toBe(path);
  await page.getByRole('button', { name: '이 예약 취소' }).click();
});
