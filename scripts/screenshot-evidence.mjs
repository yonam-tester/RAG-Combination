import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 80 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1300, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  // 첫 번째 문서 그룹 열기
  const firstDocGroup = page.locator('button:has(.material-symbols-outlined:text("description"))').first();
  await firstDocGroup.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await firstDocGroup.click();
  await page.waitForTimeout(400);

  // 내부 EvidenceAccordion 열기
  const innerAccordion = page.locator('.border-l-2.border-primary\\/60').first();
  if (await innerAccordion.count()) {
    await innerAccordion.click();
    await page.waitForTimeout(300);
  }

  // 저신뢰도 열기
  await page.locator('button:has-text("저신뢰도 근거")').first().click().catch(() => {});
  await page.waitForTimeout(300);

  await page.screenshot({ path: '/tmp/evidence-zoom.png', fullPage: true });
  await page.waitForTimeout(4000);
  await browser.close();
})();
