import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-5A901218';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 80 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  const roundedFullCount = await page.locator('.rounded-full.bg-primary\\/10').count();
  const lowConfPillCount = await page.locator('span:has-text("저신뢰도")').count();

  console.log('출처 pill 수:', roundedFullCount, roundedFullCount === 0 ? '✅ 제거됨' : '❌ 잔존');
  console.log('저신뢰도 pill 수:', lowConfPillCount);

  // Happy Path 아코디언 아래 구조 확인
  const hapPath = page.locator('text=Happy Path 너머 예외 검증 시나리오').first();
  if (await hapPath.count()) {
    await hapPath.scrollIntoViewIfNeeded();
    await page.waitForTimeout(300);
  }

  await page.screenshot({ path: '/tmp/pills-removed.png', fullPage: false });
  console.log('📸 /tmp/pills-removed.png');

  await page.waitForTimeout(3000);
  await browser.close();
})();
