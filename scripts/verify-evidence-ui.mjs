import { chromium } from 'playwright';

const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 100 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  await page.screenshot({ path: '/tmp/evidence-ui-after.png', fullPage: false });

  // 출처 pill 확인
  const pills = await page.locator('.rounded-full.bg-primary\\/10').allTextContents();
  console.log('📌 출처 문서 pill:', pills);

  // 저신뢰도 섹션 확인
  const lowSection = await page.locator('text=저신뢰도 근거').count();
  console.log('⚠️  저신뢰도 섹션 수:', lowSection);

  // 문서 그룹 버튼 확인
  const docGroups = await page.locator('button:has(.material-symbols-outlined:text("description"))').allTextContents();
  console.log('📂 문서 그룹:', docGroups.map(t => t.trim().slice(0, 60)));

  // 첫 번째 문서 그룹 클릭해서 펼치기
  const firstGroup = page.locator('button:has(.material-symbols-outlined:text("description"))').first();
  if (await firstGroup.count() > 0) {
    await firstGroup.click();
    await page.waitForTimeout(500);
    await page.screenshot({ path: '/tmp/evidence-ui-expanded.png', fullPage: false });
    console.log('✅ 첫 번째 문서 그룹 펼침 완료');
  }

  // ## 텍스트 잔존 여부 확인
  const hashHeaders = await page.locator('text=/^##/').count();
  console.log('## 미제거 텍스트 수:', hashHeaders, hashHeaders === 0 ? '✅ 없음' : '❌ 잔존');

  await page.waitForTimeout(4000);
  await browser.close();
})();
