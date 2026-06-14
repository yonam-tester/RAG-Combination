import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 100 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1300, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  // 태그 상태 (접힘)
  const tagsBefore = await page.locator('.font-mono.text-\\[11px\\]').allTextContents();
  console.log('기본 태그:', tagsBefore.filter(t => t.startsWith('#')));

  const moreBtn = page.locator('button:has-text("더보기…")').first();
  console.log('더보기 버튼 수:', await page.locator('button:has-text("더보기…")').count());

  // 스크린샷 (접힘 상태)
  await page.screenshot({ path: '/tmp/tags-collapsed.png', fullPage: false });

  // 더보기 클릭
  if (await moreBtn.count()) {
    await moreBtn.click();
    await page.waitForTimeout(400);
    const tagsAfter = await page.locator('.font-mono.text-\\[11px\\]').allTextContents();
    console.log('펼침 후 태그:', tagsAfter.filter(t => t.startsWith('#')));
    await page.screenshot({ path: '/tmp/tags-expanded.png', fullPage: false });
  }

  await page.waitForTimeout(3000);
  await browser.close();
})();
