import { chromium } from 'playwright';
const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-F15C4934';

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 80 });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });
  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: '/tmp/page-full.png', fullPage: true });
  console.log('done');
  await page.waitForTimeout(3000);
  await browser.close();
})();
