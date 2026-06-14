/**
 * Playwright duplicate evidence detector
 * - headless: false (브라우저 화면 표시)
 * - 동일 evidenceText 기준 중복 검출
 */

import { chromium } from 'playwright';

const URL = 'http://localhost:3000/analysis-demo?analysisId=ANL-5A901218';
const TEXT_KEY_LEN = 120;

(async () => {
  const browser = await chromium.launch({ headless: false, slowMo: 100 });
  const page = await browser.newPage();
  page.setDefaultTimeout(20000);

  console.log('🌐 페이지 로딩...');
  await page.goto(URL, { waitUntil: 'networkidle' });

  // TC 카드가 렌더링될 때까지 대기
  await page.waitForSelector('text=TC-001', { timeout: 10000 }).catch(() => {
    console.log('  ⚠ TC-001 텍스트 대기 타임아웃 - 계속 진행');
  });
  await page.waitForTimeout(1000);

  // ── DOM 구조 탐색 ────────────────────────────────────────────────────────
  console.log('🔎 DOM 구조 탐색...');
  const domInfo = await page.evaluate(() => {
    return {
      glassPanels: document.querySelectorAll('.glass-panel').length,
      menuBookSpans: document.querySelectorAll('.material-symbols-outlined').length,
      // 실제 텍스트 확인
      menuBookTexts: [...document.querySelectorAll('.material-symbols-outlined')]
        .map(el => el.textContent.trim())
        .filter((v, i, a) => a.indexOf(v) === i)
        .slice(0, 10),
      // 근거 관련 요소들
      borderPrimary: document.querySelectorAll('.border-primary').length,
      surfaceLowest: document.querySelectorAll('.bg-surface-container-lowest').length,
      preWrap: document.querySelectorAll('.whitespace-pre-wrap').length,
      cursorPointer: document.querySelectorAll('.cursor-pointer').length,
    };
  });
  console.log('  DOM 정보:', JSON.stringify(domInfo, null, 2));

  // ── Evidence Accordion 헤더 클릭 (올바른 selector) ─────────────────────
  // EvidenceAccordion: bg-surface-container-lowest 안의 cursor-pointer div
  console.log('\n📂 Evidence 아코디언 헤더 클릭...');

  // 방법 1: "근거:" 텍스트 포함 요소
  const evidenceHeaders = await page.locator('text=근거:').all();
  console.log(`  "근거:" 포함 요소 수: ${evidenceHeaders.length}`);

  for (const header of evidenceHeaders) {
    try {
      await header.scrollIntoViewIfNeeded();
      await header.click({ timeout: 2000 });
      await page.waitForTimeout(150);
    } catch (e) { /* 무시 */ }
  }

  await page.waitForTimeout(800);

  // ── evidenceText 수집 ───────────────────────────────────────────────────
  console.log('\n🔍 근거 텍스트 수집...');
  const evidenceData = await page.evaluate((keyLen) => {
    const results = [];

    // EvidenceAccordion이 열리면 p.whitespace-pre-wrap이 나타남
    const allP = document.querySelectorAll('p.whitespace-pre-wrap');
    allP.forEach((el) => {
      const text = el.textContent.trim();
      if (text.length < 20) return;

      // 가장 가까운 TC 카드에서 ID 찾기
      const card = el.closest('.glass-panel');
      const tcIdEl = card ? card.querySelector('.font-mono') : null;
      const tcId = tcIdEl ? tcIdEl.textContent.trim() : 'UNKNOWN';

      results.push({ tcId, key: text.slice(0, keyLen), fullLen: text.length });
    });
    return results;
  }, TEXT_KEY_LEN);

  console.log(`  수집된 근거: ${evidenceData.length}건`);

  if (evidenceData.length === 0) {
    // DOM에서 실제 evidence 구조 덤프
    console.log('\n  ⚠ evidence 텍스트를 찾지 못했습니다. DOM 진단:');
    const dump = await page.evaluate(() => {
      const el = document.querySelector('.bg-surface-container-lowest');
      return el ? el.innerHTML.slice(0, 500) : '해당 요소 없음';
    });
    console.log('  bg-surface-container-lowest innerHTML:', dump);

    // 현재 열린 p.whitespace-pre-wrap 이 아닌 모든 p 탐색
    const allPTexts = await page.evaluate(() => {
      return [...document.querySelectorAll('p')].map(p => ({
        class: p.className,
        textSlice: p.textContent.trim().slice(0, 60),
      })).filter(p => p.textSlice.length > 10).slice(0, 10);
    });
    console.log('  모든 <p> 요소 샘플:', JSON.stringify(allPTexts, null, 2));
  }

  // ── 중복 계산 ────────────────────────────────────────────────────────────
  const keyMap = new Map();
  evidenceData.forEach((item, idx) => {
    if (!keyMap.has(item.key)) keyMap.set(item.key, []);
    keyMap.get(item.key).push({ ...item, idx });
  });

  const duplicateGroups = [...keyMap.entries()]
    .filter(([, items]) => items.length > 1)
    .map(([key, items]) => ({ key, items }));

  console.log(`\n${'═'.repeat(60)}`);
  console.log(`📊 결과: 전체 ${evidenceData.length}건, 중복 그룹 ${duplicateGroups.length}건`);
  console.log('═'.repeat(60));

  duplicateGroups.forEach((g, i) => {
    const tcIds = [...new Set(g.items.map(x => x.tcId))];
    console.log(`\n[그룹 ${i + 1}] ${g.items.length}건 중복`);
    console.log(`  TC: ${tcIds.join(', ')}`);
    console.log(`  텍스트: "${g.key.slice(0, 70).replace(/\n/g, '↵')}..."`);
  });

  // ── 하이라이트 ──────────────────────────────────────────────────────────
  if (duplicateGroups.length > 0) {
    const dupKeys = duplicateGroups.map(g => g.key);
    const count = await page.evaluate(({ keys, keyLen }) => {
      let n = 0;
      document.querySelectorAll('p.whitespace-pre-wrap').forEach(el => {
        const key = el.textContent.trim().slice(0, keyLen);
        if (keys.includes(key)) {
          el.style.outline = '2px solid #f59e0b';
          el.style.backgroundColor = 'rgba(245,158,11,0.1)';
          const badge = document.createElement('span');
          badge.textContent = '⚠ 중복';
          badge.style.cssText = 'display:inline-block;background:#f59e0b;color:#000;font-size:10px;font-weight:bold;padding:1px 5px;border-radius:3px;margin-right:4px;';
          el.prepend(badge);
          n++;
        }
      });

      const banner = document.createElement('div');
      banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:9999;background:#f59e0b;color:#000;text-align:center;padding:6px;font-size:13px;font-weight:bold;';
      banner.textContent = n > 0
        ? `⚠ 중복 근거 ${n}건 검출 — 동일 문서 청크가 여러 TC에 반복 사용됨`
        : '✅ 중복 근거 없음';
      document.body.prepend(banner);
      return n;
    }, { keys: dupKeys, keyLen: TEXT_KEY_LEN });
    console.log(`\n🎨 하이라이트: ${count}개 항목`);
  }

  console.log('\n⏸  Enter 누르면 종료...');
  process.stdin.setRawMode?.(true);
  await new Promise(r => process.stdin.once('data', r));
  await browser.close();
  process.exit(0);
})();
