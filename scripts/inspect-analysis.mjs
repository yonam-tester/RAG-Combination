import { chromium } from 'playwright';

const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 80 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  await page.screenshot({ path: '/tmp/analysis-before.png', fullPage: false });

  const info = await page.evaluate(() => {
    // 테스트케이스 수
    const tcIds = [...document.querySelectorAll('.font-mono')].map(el => el.textContent?.trim()).filter(t => t?.startsWith('TC-'));

    // category / technique 배지
    const badges = [...document.querySelectorAll('[class*="text-\\[10px\\]"]')]
      .map(el => el.textContent?.trim()).filter(Boolean);

    // 근거 헤더 수집 (클릭 전)
    const evidenceItems = [...document.querySelectorAll('.border-primary')]
      .map(el => {
        const header = el.querySelector('.font-semibold');
        const scoreEl = el.querySelector('.text-amber-500');
        return {
          header: header?.textContent?.trim().slice(0, 60),
          score: scoreEl?.textContent?.trim()
        };
      });

    return { tcIds: [...new Set(tcIds)], badges: [...new Set(badges)].slice(0, 15), evidenceItems: evidenceItems.slice(0, 10) };
  });

  console.log(JSON.stringify(info, null, 2));
  await page.waitForTimeout(2000);
  await browser.close();
})();
