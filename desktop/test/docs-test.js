'use strict';
// CI check: every document type builds, and the Office files are valid zip packages with the expected parts.
const zlib = require('zlib');
const docs = require('../src/docs');

function entries(buf) {
  const out = {}; let i = 0;
  while (buf.readUInt32LE(i) === 0x04034b50) {
    const comp = buf.readUInt32LE(i + 18); const n = buf.readUInt16LE(i + 26); const x = buf.readUInt16LE(i + 28);
    const name = buf.toString('utf8', i + 30, i + 30 + n);
    out[name] = zlib.inflateRawSync(buf.subarray(i + 30 + n + x, i + 30 + n + x + comp)).toString('utf8');
    i += 30 + n + x + comp;
  }
  return out;
}
const must = (cond, msg) => { if (!cond) { console.error('FAIL:', msg); process.exit(1); } };
(async () => {
  const x = entries(await docs.build(docs.parse({ type: 'xlsx', title: 'Budget', sheets: [{ name: 'M', columns: [{ header: 'Item' }, { header: 'Amt', format: 'currency' }], rows: [['Rent', 8500], ['Food', '=B2/2']], totals: true, chart: { type: 'pie' } }] })));
  must(x['xl/worksheets/sheet1.xml'].includes('<f>SUM(B2:B3)</f>'), 'xlsx totals formula');
  must(x['xl/charts/chart1.xml'] && x['xl/charts/chart1.xml'].includes('pieChart'), 'xlsx chart');
  const d = entries(await docs.build(docs.parse({ type: 'docx', title: 'T', blocks: [{ h1: 'A' }, { p: '**b** c' }, { numbered: ['1'] }, { table: { header: ['a'], rows: [['b']] } }] })));
  must(d['word/document.xml'].includes('Heading1') && d['word/document.xml'].includes('<w:b/>') && d['word/document.xml'].includes('<w:tbl>'), 'docx content');
  const p = entries(await docs.build(docs.parse({ type: 'pptx', title: 'D', slides: [{ layout: 'title', title: 'Hi' }, { layout: 'bullets', title: 'B', bullets: ['x'] }] })));
  must(p['ppt/slides/slide2.xml'] && p['ppt/presentation.xml'].includes('sldId'), 'pptx slides');
  for (const t of ['csv', 'md', 'txt', 'html']) must((await docs.build(docs.parse({ type: t, title: 'X', blocks: [{ p: 'y' }], sheets: [{ columns: ['a'], rows: [['1']] }] }))).length > 3, t);
  console.log('Document writers OK');
})();
