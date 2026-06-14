import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 80 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  // 1) 첫 번째 문서 그룹 펼치기
  const firstGroup = page.locator('button:has(.material-symbols-outlined:text("description"))').first();
  await firstGroup.click();
  await page.waitForTimeout(400);

  // 2) 섹션 pill 텍스트 확인 (## 잔존 여부)
  const sectionPills = await page.locator('.font-mono.bg-white\\/5.px-1\\.5').allTextContents();
  console.log('섹션 pill 텍스트:');
  sectionPills.forEach(t => console.log(' -', JSON.stringify(t)));
  const hasHash = sectionPills.some(t => t.startsWith('##'));
  console.log(hasHash ? '❌ ## 잔존' : '✅ ## 없음');

  // 3) 첫 번째 섹션 pill 클릭 (evidence 본문 열기)
  const firstPill = page.locator('.rounded-lg.border.overflow-hidden').first()
    .locator('.p-2\\.5').first();
  if (await firstPill.count()) {
    await firstPill.click();
    await page.waitForTimeout(400);
  }

  // 4) 저신뢰도 섹션 클릭해서 펼치기
  const lowBtn = page.locator('button:has-text("저신뢰도 근거")').first();
  if (await lowBtn.count()) {
    await lowBtn.click();
    await page.waitForTimeout(400);
  }

  await page.screenshot({ path: '/tmp/evidence-final.png', fullPage: false });
  console.log('📸 스크린샷: /tmp/evidence-final.png');
  await page.waitForTimeout(4000);
  await browser.close();
})();
