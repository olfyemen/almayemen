// Browser end-to-end test for the room web page.
// Run: cd android && ALMA_E2E=$PWD/app/src/test/e2e/web-room.js ./gradlew :app:testDebugUnitTest --tests '*TransferServerTest*'
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
(async () => {
  const [url, tmp] = process.argv.slice(2);
  const shots = path.join(__dirname, 'shots'); fs.mkdirSync(shots, { recursive: true });
  const work = path.join(tmp, 'web-src'); fs.mkdirSync(path.join(work, 'test-folder', 'sub'), { recursive: true });
  fs.writeFileSync(path.join(work, 'ملاحظات.txt'), 'مرحبا من المتصفح');
  fs.writeFileSync(path.join(work, 'test-folder', 'a.txt'), 'A'); fs.writeFileSync(path.join(work, 'test-folder', 'sub', 'b.txt'), 'BB');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 390, height: 760 }, deviceScaleFactor: 2, acceptDownloads: true });
  page.on('pageerror', e => { console.log('PAGE ERROR', e.message); process.exitCode = 1; });
  await page.goto(url);
  await page.fill('#name', 'زائر المتصفح');
  await page.fill('#pass', 'خطأ'); await page.click('#join');
  await page.waitForSelector('.err:text("كلمة السر غير صحيحة")'); console.log('wrong password rejected ✓');
  await page.fill('#pass', '123'); await page.click('#join');
  await page.waitForSelector('#composer', { state: 'visible' }); console.log('joined ✓');
  await page.fill('#text', 'السلام عليكم من المتصفح'); await page.press('#text', 'Enter');
  await page.waitForSelector('.me .bubble:has-text("السلام عليكم من المتصفح")'); console.log('message ✓');
  await page.waitForSelector('.other .bubble:has-text("أهلاً بالجميع من التطبيق")', { timeout: 15000 }); console.log('message from app ✓');
  // Playwright can't attach local files with non-ASCII names, so pass the content in memory
  await page.setInputFiles('#files', { name: 'ملاحظات.txt', mimeType: 'text/plain', buffer: Buffer.from('مرحبا من المتصفح') });
  await page.waitForTimeout(3000);
  await page.waitForSelector('.bubble:has-text("ملاحظات.txt")'); console.log('file upload ✓');
  await page.setInputFiles('#folder', path.join(work, 'test-folder'));
  await page.waitForSelector('.bubble:has-text("b.txt")', { timeout: 15000 }); console.log('folder upload ✓');
  await page.waitForSelector('a:has-text("ZIP")'); console.log('zip link ✓');
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('.bubble:has-text("ملاحظات.txt") a')]);
  const got = fs.readFileSync(await dl.path(), 'utf8'); if (got !== 'مرحبا من المتصفح') throw new Error('download mismatch'); console.log('download ✓', dl.suggestedFilename());
  await page.screenshot({ path: path.join(shots, 'web-room.png') });
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
