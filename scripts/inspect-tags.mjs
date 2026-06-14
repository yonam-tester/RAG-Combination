import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-FA5348AA';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1300, height: 900 });

  // 페이지 로드 + 에러 캐치
  try {
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 15000 });
  } catch(e) { console.log('goto error:', e.message); }

  await new Promise(r => setTimeout(r, 3000));

  const info = await page.evaluate(() => {
    const monoSpans = [...document.querySelectorAll('span.font-mono')]
      .map(el => el.textContent?.trim()).filter(Boolean).slice(0, 20);

    const hashItems = [...document.querySelectorAll('*')]
      .filter(el => el.children.length === 0 && (el.textContent?.trim() || '').startsWith('#'))
      .map(el => el.textContent?.trim()).filter(Boolean).slice(0, 15);

    // glass-panel 개수 + 첫 카드 구조
    const panels = document.querySelectorAll('.glass-panel').length;
    const firstPanel = document.querySelector('.glass-panel');
    // gap-xs div 탐색
    const tagWrappers = [...document.querySelectorAll('.gap-xs')].map(el => el.textContent?.trim().slice(0, 80));

    return { monoSpans, hashItems, panels, tagWrappers };
  });

  console.log(JSON.stringify(info, null, 2));
  await browser.close();
})();
