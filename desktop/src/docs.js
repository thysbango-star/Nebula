'use strict';
// Nebula documents for Windows: the same writers as the phone app, ported to JavaScript.
// Real .docx / .xlsx (with native charts) / .pptx files built from Office Open XML, plus
// csv, txt, md, html, and pdf (rendered from the html by Electron). No third-party packages.

const zlib = require('zlib');

// ------------------------------------------------------------------ zip

function crc32(buf) {
  if (zlib.crc32) return zlib.crc32(buf) >>> 0;
  let c, crc = 0xFFFFFFFF;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xFF;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

/** Minimal ZIP writer (deflate), [Content_Types].xml first. */
function zip(parts) {
  const names = Object.keys(parts).sort((a, b) => (a === '[Content_Types].xml' ? -1 : b === '[Content_Types].xml' ? 1 : 0));
  const local = []; const central = []; let offset = 0;
  const d = new Date();
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  for (const name of names) {
    const data = Buffer.isBuffer(parts[name]) ? parts[name] : Buffer.from(parts[name], 'utf8');
    const comp = zlib.deflateRawSync(data, { level: 6 });
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt16LE(time, 10); h.writeUInt16LE(date, 12); h.writeUInt32LE(crc, 14);
    h.writeUInt32LE(comp.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(nameBuf.length, 26); h.writeUInt16LE(0, 28);
    local.push(h, nameBuf, comp);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt16LE(time, 12); c.writeUInt16LE(date, 14); c.writeUInt32LE(crc, 16); c.writeUInt32LE(comp.length, 20);
    c.writeUInt32LE(data.length, 24); c.writeUInt16LE(nameBuf.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, nameBuf);
    offset += 30 + nameBuf.length + comp.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(names.length, 8); end.writeUInt16LE(names.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, cd, end]);
}

// ------------------------------------------------------------------ helpers

const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
const REL_NS = 'http://schemas.openxmlformats.org/package/2006/relationships';
const R_DOC = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

function esc(s) {
  let out = '';
  for (const ch of String(s ?? '')) {
    const c = ch.codePointAt(0);
    if (ch === '&') out += '&amp;'; else if (ch === '<') out += '&lt;'; else if (ch === '>') out += '&gt;';
    else if (ch === '"') out += '&quot;'; else if (ch === "'") out += '&apos;';
    else if (c >= 0x20 || ch === '\n' || ch === '\t' || ch === '\r') out += ch;
  }
  return out;
}
function cleanHex(hex, fallback = 'E8531E') {
  const h = String(hex || '').trim().replace(/^#/, '').toUpperCase();
  return /^[0-9A-F]{6}$/.test(h) ? h : fallback;
}
function tint(hex, amount) {
  const h = cleanHex(hex);
  return [0, 1, 2].map((i) => {
    const c = parseInt(h.substr(i * 2, 2), 16);
    return Math.max(0, Math.min(255, Math.floor(c + (255 - c) * amount))).toString(16).padStart(2, '0').toUpperCase();
  }).join('');
}
function plain(s) {
  return String(s ?? '').replace(/\*\*(.+?)\*\*/g, '$1').replace(/__(.+?)__/g, '$1').replace(/`([^`]*)`/g, '$1').replace(/^#+\s*/, '').trim();
}
const nowIso = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z');
const coreProps = (title, author, created) => XML_HEAD +
  '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" ' +
  'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" ' +
  'xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' +
  `<dc:title>${esc(title)}</dc:title><dc:creator>${esc(author)}</dc:creator>` +
  '<cp:lastModifiedBy>Nebula</cp:lastModifiedBy>' +
  `<dcterms:created xsi:type="dcterms:W3CDTF">${created}</dcterms:created>` +
  `<dcterms:modified xsi:type="dcterms:W3CDTF">${created}</dcterms:modified></cp:coreProperties>`;
const appProps = () => XML_HEAD +
  '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" ' +
  'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>Nebula</Application></Properties>';

// ------------------------------------------------------------------ spec (same JSON as the phone)

const TYPES = {
  docx: 'Word document', xlsx: 'Excel spreadsheet', pptx: 'PowerPoint presentation', pdf: 'PDF',
  csv: 'CSV file', txt: 'text file', md: 'Markdown file', html: 'web page',
};
function parseType(s) {
  const t = String(s || '').trim().replace(/^\./, '').toLowerCase();
  if (TYPES[t]) return t;
  return { word: 'docx', doc: 'docx', excel: 'xlsx', spreadsheet: 'xlsx', xls: 'xlsx', sheet: 'xlsx',
    powerpoint: 'pptx', ppt: 'pptx', slides: 'pptx', presentation: 'pptx', text: 'txt', markdown: 'md' }[t] || null;
}

const SCHEMA_HINT = `Document spec JSON:
{"type":"docx|xlsx|pptx|pdf|csv|txt|md|html","title":"...","subtitle":"optional",
 "blocks":[{"h1":"..."},{"h2":"..."},{"h3":"..."},{"p":"paragraph, **bold** allowed"},{"bullets":["..."]},{"numbered":["..."]},
           {"table":{"header":["A","B"],"rows":[["1","2"]]}},{"quote":"..."},{"pagebreak":true}],      <- docx, pdf, txt, md, html
 "sheets":[{"name":"Sheet","columns":[{"header":"Item","format":"text|number|currency|percent|date|integer","width":14}],
            "rows":[["Rent",8500,"=B2*12"]],"totals":true,
            "chart":{"type":"column|bar|line|pie","title":"...","categoryColumn":0,"valueColumns":[1]},
            "validations":[{"column":2,"list":["Yes","No"]}]}],                                           <- xlsx, csv
 "slides":[{"layout":"title|bullets|two_column|section|quote|closing","title":"...","subtitle":"...","bullets":["..."],
            "leftTitle":"...","left":["..."],"rightTitle":"...","right":["..."]}]}                        <- pptx
Use real formulas (=SUM(B2:B9), =B2*C2) in spreadsheets, dates as yyyy-mm-dd, 6-12 slides for decks, and substantial content.`;

const strs = (a) => (Array.isArray(a) ? a.map((x) => (x == null ? '' : String(x))) : []);

function parse(json, fallbackType, currency = '$') {
  const o = typeof json === 'string' ? JSON.parse(json) : (json || {});
  const blocks = [];
  for (const b of o.blocks || o.content || []) {
    if (typeof b === 'string') { blocks.push({ k: 'p', text: b }); continue; }
    if (!b || typeof b !== 'object') continue;
    if ('h1' in b) blocks.push({ k: 'h', level: 1, text: String(b.h1) });
    else if ('h2' in b) blocks.push({ k: 'h', level: 2, text: String(b.h2) });
    else if ('h3' in b) blocks.push({ k: 'h', level: 3, text: String(b.h3) });
    else if ('heading' in b) blocks.push({ k: 'h', level: b.level || 1, text: String(b.heading) });
    else if ('p' in b || 'paragraph' in b || 'text' in b) blocks.push({ k: 'p', text: String(b.p ?? b.paragraph ?? b.text) });
    else if ('bullets' in b) blocks.push({ k: 'list', items: strs(b.bullets), numbered: false });
    else if ('numbered' in b) blocks.push({ k: 'list', items: strs(b.numbered), numbered: true });
    else if ('list' in b) blocks.push({ k: 'list', items: strs(b.list), numbered: !!b.ordered });
    else if (b.table) blocks.push({ k: 'table', header: strs(b.table.header), rows: (b.table.rows || []).map(strs) });
    else if ('quote' in b) blocks.push({ k: 'quote', text: String(b.quote) });
    else if (b.pagebreak) blocks.push({ k: 'break' });
  }
  const sheets = (o.sheets || []).filter((s) => s && typeof s === 'object').map((s, i) => ({
    name: String(s.name || '').trim() || `Sheet${i + 1}`,
    columns: (s.columns || []).map((c) => (c && typeof c === 'object'
      ? { header: String(c.header ?? c.name ?? ''), format: String(c.format || 'text').toLowerCase(), width: typeof c.width === 'number' ? c.width : null }
      : { header: String(c ?? ''), format: 'text', width: null })),
    rows: (s.rows || []).map((r) => (Array.isArray(r) ? r.map((v) => (v === undefined ? null : v)) : [])),
    totals: !!s.totals,
    chart: s.chart ? { type: String(s.chart.type || 'column').toLowerCase(), title: String(s.chart.title || ''),
      categoryColumn: s.chart.categoryColumn | 0, valueColumns: Array.isArray(s.chart.valueColumns) ? s.chart.valueColumns.map((x) => x | 0) : [1] } : null,
    validations: (s.validations || []).map((v) => ({ column: v.column | 0, list: strs(v.list) })),
    freezeHeader: s.freezeHeader !== false,
  }));
  const slides = (o.slides || []).filter((s) => s && typeof s === 'object').map((s) => ({
    layout: String(s.layout || 'bullets').toLowerCase(), title: String(s.title || ''), subtitle: String(s.subtitle || ''),
    bullets: strs(s.bullets || s.points), left: strs(s.left), right: strs(s.right),
    leftTitle: String(s.leftTitle || ''), rightTitle: String(s.rightTitle || ''), notes: String(s.notes || ''),
  }));
  return {
    type: parseType(o.type) || fallbackType || 'docx',
    title: String(o.title || '').trim() || 'Untitled', subtitle: String(o.subtitle || ''), author: 'Nebula',
    blocks, sheets, slides, accent: cleanHex(o.accent || 'E8531E'), currency: String(o.currency || currency),
  };
}

/** Back to the AI-facing JSON (saved next to each file so it can be edited later). */
function toJson(d) {
  const o = { type: d.type, title: d.title, subtitle: d.subtitle, accent: d.accent, currency: d.currency };
  if (d.blocks.length) o.blocks = d.blocks.map((b) => (b.k === 'h' ? { [`h${Math.min(3, Math.max(1, b.level))}`]: b.text }
    : b.k === 'p' ? { p: b.text } : b.k === 'list' ? { [b.numbered ? 'numbered' : 'bullets']: b.items }
      : b.k === 'table' ? { table: { header: b.header, rows: b.rows } } : b.k === 'quote' ? { quote: b.text } : { pagebreak: true }));
  if (d.sheets.length) o.sheets = d.sheets;
  if (d.slides.length) o.slides = d.slides;
  return o;
}

// ------------------------------------------------------------------ DOCX

const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';

function docx(spec) {
  const accent = cleanHex(spec.accent);
  const numbered = [];
  const run = (t, rPr) => `<w:r>${rPr ? `<w:rPr>${rPr}</w:rPr>` : ''}<w:t xml:space="preserve">${esc(t)}</w:t></w:r>`;
  const runs = (text, extra = '') => {
    let out = ''; let last = 0; const re = /\*\*(.+?)\*\*|\*(.+?)\*/g; let m;
    while ((m = re.exec(text))) {
      if (m.index > last) out += run(text.slice(last, m.index), extra);
      out += m[1] ? run(m[1], extra + '<w:b/>') : run(m[2], extra + '<w:i/>');
      last = m.index + m[0].length;
    }
    if (last < text.length) out += run(text.slice(last), extra);
    return out;
  };
  const p = (style, text) => `<w:p>${style ? `<w:pPr><w:pStyle w:val="${style}"/></w:pPr>` : ''}${runs(String(text).trim())}</w:p>`;
  const listItem = (text, numId) => `<w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="${numId}"/></w:numPr></w:pPr>${runs(String(text).trim())}</w:p>`;
  const table = (t) => {
    const cols = Math.max(1, t.header.length, ...t.rows.map((r) => r.length));
    const total = 9300; const w = Math.floor(total / cols);
    let sb = `<w:tbl><w:tblPr><w:tblW w:w="${total}" w:type="dxa"/><w:tblBorders>`;
    for (const side of ['top', 'bottom', 'insideH']) sb += `<w:${side} w:val="single" w:sz="4" w:space="0" w:color="DDD8D2"/>`;
    sb += '</w:tblBorders><w:tblCellMar><w:top w:w="90" w:type="dxa"/><w:left w:w="120" w:type="dxa"/>' +
      '<w:bottom w:w="90" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>';
    for (let i = 0; i < cols; i++) sb += `<w:gridCol w:w="${w}"/>`;
    sb += '</w:tblGrid>';
    const row = (cells, header, shade) => {
      sb += '<w:tr>'; if (header) sb += '<w:trPr><w:tblHeader/></w:trPr>';
      for (let i = 0; i < cols; i++) {
        const c = cells[i] || '';
        sb += `<w:tc><w:tcPr><w:tcW w:w="${w}" w:type="dxa"/>`;
        if (shade) sb += `<w:shd w:val="clear" w:color="auto" w:fill="${shade}"/>`;
        sb += '</w:tcPr><w:p><w:pPr><w:spacing w:before="0" w:after="0"/></w:pPr>';
        sb += header ? run(plain(c), '<w:b/><w:color w:val="FFFFFF"/>') : runs(c);
        sb += '</w:p></w:tc>';
      }
      sb += '</w:tr>';
    };
    if (t.header.length) row(t.header, true, accent);
    t.rows.forEach((r, i) => row(r, false, i % 2 === 1 ? tint(accent, 0.92) : null));
    return sb + '</w:tbl><w:p/>';
  };

  let body = p('Title', spec.title);
  if (spec.subtitle.trim()) body += p('Subtitle', spec.subtitle);
  for (const b of spec.blocks) {
    if (b.k === 'h') body += p(`Heading${Math.min(3, Math.max(1, b.level))}`, b.text);
    else if (b.k === 'p') b.text.split('\n').filter((x) => x.trim()).forEach((x) => { body += p(null, x); });
    else if (b.k === 'list') { const numId = b.numbered ? 3 + numbered.length : 1; if (b.numbered) numbered.push(numId); b.items.forEach((it) => { body += listItem(it, numId); }); }
    else if (b.k === 'table') body += table(b);
    else if (b.k === 'quote') body += p('Quote', b.text);
    else if (b.k === 'break') body += '<w:p><w:r><w:br w:type="page"/></w:r></w:p>';
  }

  const font = (size, color, bold = false, family = 'Aptos') =>
    `<w:rPr><w:rFonts w:ascii="${family}" w:hAnsi="${family}" w:cs="${family}"/>${bold ? '<w:b/>' : ''}<w:color w:val="${color}"/><w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr>`;
  const style = (id, name, pPr, rPr) => `<w:style w:type="paragraph" w:styleId="${id}"><w:name w:val="${name}"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>${pPr}${rPr}</w:style>`;
  const styles = XML_HEAD + `<w:styles xmlns:w="${W}">` +
    `<w:docDefaults><w:rPrDefault>${font(22, '2B2724')}</w:rPrDefault>` +
    '<w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>' +
    '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>' +
    style('Title', 'Title', '<w:pPr><w:spacing w:before="0" w:after="80"/></w:pPr>', font(56, '1E1B18', true, 'Aptos Display')) +
    style('Subtitle', 'Subtitle', `<w:pPr><w:spacing w:after="360"/><w:pBdr><w:bottom w:val="single" w:sz="12" w:space="10" w:color="${accent}"/></w:pBdr></w:pPr>`, font(26, '6E6760')) +
    style('Heading1', 'heading 1', '<w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>', font(34, accent, true, 'Aptos Display')) +
    style('Heading2', 'heading 2', '<w:pPr><w:keepNext/><w:spacing w:before="260" w:after="100"/><w:outlineLvl w:val="1"/></w:pPr>', font(28, '2B2724', true, 'Aptos Display')) +
    style('Heading3', 'heading 3', '<w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="2"/></w:pPr>', font(24, '4A443F', true)) +
    style('ListParagraph', 'List Paragraph', '<w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/></w:pPr>', '') +
    style('Quote', 'Quote', `<w:pPr><w:spacing w:before="160" w:after="200"/><w:ind w:left="560"/><w:pBdr><w:left w:val="single" w:sz="24" w:space="12" w:color="${accent}"/></w:pBdr></w:pPr>`, '<w:rPr><w:i/><w:color w:val="5A534D"/></w:rPr>') +
    '</w:styles>';
  let num = XML_HEAD + `<w:numbering xmlns:w="${W}">` +
    '<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>' +
    '<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>' +
    '<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>';
  for (const id of numbered) num += `<w:num w:numId="${id}"><w:abstractNumId w:val="1"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>`;
  num += '</w:numbering>';
  const g = '<w:rPr><w:color w:val="9A948E"/><w:sz w:val="18"/></w:rPr>';
  return zip({
    '[Content_Types].xml': XML_HEAD + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
      '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>' +
      '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>' +
      '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>' +
      '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>' +
      '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
      '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>',
    '_rels/.rels': XML_HEAD + `<Relationships xmlns="${REL_NS}">` +
      `<Relationship Id="rId1" Type="${R_DOC}/officeDocument" Target="word/document.xml"/>` +
      '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
      `<Relationship Id="rId3" Type="${R_DOC}/extended-properties" Target="docProps/app.xml"/></Relationships>`,
    'word/_rels/document.xml.rels': XML_HEAD + `<Relationships xmlns="${REL_NS}">` +
      `<Relationship Id="rId1" Type="${R_DOC}/styles" Target="styles.xml"/><Relationship Id="rId2" Type="${R_DOC}/numbering" Target="numbering.xml"/>` +
      `<Relationship Id="rId3" Type="${R_DOC}/settings" Target="settings.xml"/><Relationship Id="rId4" Type="${R_DOC}/footer" Target="footer1.xml"/></Relationships>`,
    'word/document.xml': XML_HEAD + `<w:document xmlns:w="${W}" xmlns:r="${R_DOC}"><w:body>${body}` +
      '<w:sectPr><w:footerReference w:type="default" r:id="rId4"/><w:pgSz w:w="11906" w:h="16838"/>' +
      '<w:pgMar w:top="1300" w:right="1300" w:bottom="1300" w:left="1300" w:header="708" w:footer="600" w:gutter="0"/></w:sectPr></w:body></w:document>',
    'word/styles.xml': styles,
    'word/numbering.xml': num,
    'word/settings.xml': XML_HEAD + `<w:settings xmlns:w="${W}"><w:defaultTabStop w:val="720"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>`,
    'word/footer1.xml': XML_HEAD + `<w:ftr xmlns:w="${W}"><w:p><w:pPr><w:jc w:val="right"/></w:pPr>` +
      `<w:r>${g}<w:t xml:space="preserve">${esc(spec.title)}  ·  </w:t></w:r><w:r>${g}<w:fldChar w:fldCharType="begin"/></w:r>` +
      `<w:r>${g}<w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r>${g}<w:fldChar w:fldCharType="separate"/></w:r>` +
      `<w:r>${g}<w:t>1</w:t></w:r><w:r>${g}<w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>`,
    'docProps/core.xml': coreProps(spec.title, spec.author, nowIso()),
    'docProps/app.xml': appProps(),
  });
}

// ------------------------------------------------------------------ XLSX

const S_NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const ST_HEADER = 1;
const FMT_STYLE = { number: 2, currency: 3, percent: 4, date: 5, integer: 6 };
const ST_TOTAL_LABEL = 7;
const TOTAL_STYLE = { number: 8, currency: 9, percent: 10, integer: 11, text: 7, date: 7 };
const NUMERIC = new Set(['number', 'currency', 'percent', 'integer']);
const PALETTE = ['E8531E', 'F2A31B', '7C5CFF', '2BB3A3', 'E5487F', '3D8BFD', '8A9A2B', 'B5651D'];

function col(i) { let n = i + 1; let s = ''; while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26); } return s; }
const sheetRef = (name) => `'${name.replace(/'/g, "''")}'`;

function parseNumber(t, format) {
  let x = String(t).replace(/[\s ]/g, '').replace(/^[^0-9\-+.,(]+/, '').replace(/[^0-9.,%-]+$/, '');
  const pct = x.endsWith('%'); x = x.replace(/%$/, '');
  if ((x.match(/,/g) || []).length >= 1 && !x.includes('.') && /,\d{1,2}$/.test(x)) x = x.replace(/,/g, '.'); else x = x.replace(/,/g, '');
  if (!/^[-+]?\d*\.?\d+(e[-+]?\d+)?$/i.test(x)) return null;
  const d = Number(x);
  if (!Number.isFinite(d)) return null;
  return pct || (format === 'percent' && d > 1) ? d / 100 : d;
}
function dateSerial(t) {
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(String(t));
  if (!m) return null;
  return Math.round((Date.UTC(+m[1], +m[2] - 1, +m[3]) - Date.UTC(1899, 11, 30)) / 86400000);
}
function display(v) {
  if (v == null) return '';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(2);
  const s = String(v); return s.startsWith('=') ? '0000000000' : s;
}
const sAttr = (st) => (st ? ` s="${st}"` : '');
const numCell = (ref, d, st) => `<c r="${ref}"${sAttr(st)}><v>${Number.isInteger(d) && Math.abs(d) < 1e15 ? d : d}</v></c>`;
const strCell = (ref, text, st) => `<c r="${ref}" t="inlineStr"${sAttr(st)}><is><t xml:space="preserve">${esc(text)}</t></is></c>`;
function cell(ref, v, format, st) {
  if (v == null || v === '') return st ? `<c r="${ref}" s="${st}"/>` : '';
  if (typeof v === 'number') return numCell(ref, v, st);
  if (typeof v === 'boolean') return `<c r="${ref}" t="b"${sAttr(st)}><v>${v ? 1 : 0}</v></c>`;
  const t = String(v).trim();
  if (t.startsWith('=') && t.length > 1) return `<c r="${ref}"${sAttr(st)}><f>${esc(t.slice(1))}</f></c>`;
  if (format === 'date' && dateSerial(t) != null) return numCell(ref, dateSerial(t), st);
  if (NUMERIC.has(format) && parseNumber(t, format) != null) return numCell(ref, parseNumber(t, format), st);
  return strCell(ref, t, NUMERIC.has(format) || format === 'date' ? 0 : st);
}

function buildSheet(sheet) {
  const cols = sheet.columns.length ? sheet.columns : [{ header: 'A', format: 'text', width: null }];
  const nCols = Math.max(cols.length, ...sheet.rows.map((r) => r.length), 1);
  const fmt = Array.from({ length: nCols }, (_, i) => (cols[i]?.format || 'text').toLowerCase());
  const data = sheet.rows;
  const lastRow = 1 + data.length + (sheet.totals ? 1 : 0);
  let sb = XML_HEAD + `<worksheet xmlns="${S_NS}" xmlns:r="${R_DOC}"><dimension ref="A1:${col(nCols - 1)}${lastRow}"/><sheetViews><sheetView workbookViewId="0"`;
  sb += sheet.freezeHeader ? '><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView>' : '/>';
  sb += '</sheetViews><sheetFormatPr defaultRowHeight="18"/><cols>';
  for (let c = 0; c < nCols; c++) {
    const given = cols[c]?.width;
    const longest = Math.max(...[cols[c]?.header || '', ...data.map((r) => display(r[c]))].map((s) => s.length));
    const w = Math.min(100, Math.max(4, given ?? Math.min(60, Math.max(9, longest * 1.15 + 3))));
    sb += `<col min="${c + 1}" max="${c + 1}" width="${w.toFixed(1)}" customWidth="1"/>`;
  }
  sb += '</cols><sheetData><row r="1" ht="24" customHeight="1">';
  for (let c = 0; c < nCols; c++) sb += strCell(`${col(c)}1`, cols[c]?.header || '', ST_HEADER);
  sb += '</row>';
  data.forEach((row, i) => {
    const r = i + 2; sb += `<row r="${r}">`;
    for (let c = 0; c < nCols; c++) sb += cell(`${col(c)}${r}`, row[c], fmt[c], FMT_STYLE[fmt[c]] || 0);
    sb += '</row>';
  });
  if (sheet.totals && data.length) {
    const r = data.length + 2; sb += `<row r="${r}" ht="22" customHeight="1">`;
    for (let c = 0; c < nCols; c++) {
      const ref = `${col(c)}${r}`; const st = TOTAL_STYLE[fmt[c]] || ST_TOTAL_LABEL;
      if (c === 0 && !NUMERIC.has(fmt[c])) sb += strCell(ref, 'Total', ST_TOTAL_LABEL);
      else if (NUMERIC.has(fmt[c]) && fmt[c] !== 'percent') sb += `<c r="${ref}" s="${st}"><f>SUM(${col(c)}2:${col(c)}${r - 1})</f></c>`;
      else if (fmt[c] === 'percent') sb += `<c r="${ref}" s="${st}"><f>AVERAGE(${col(c)}2:${col(c)}${r - 1})</f></c>`;
      else sb += `<c r="${ref}" s="${ST_TOTAL_LABEL}"/>`;
    }
    sb += '</row>';
  }
  sb += '</sheetData>';
  const filterRef = data.length ? `$A$1:$${col(nCols - 1)}$${data.length + 1}` : null;
  if (filterRef) sb += `<autoFilter ref="A1:${col(nCols - 1)}${data.length + 1}"/>`;
  const vals = sheet.validations.filter((v) => v.column >= 0 && v.column < nCols && v.list.length);
  if (vals.length) {
    sb += `<dataValidations count="${vals.length}">`;
    for (const v of vals) {
      const list = v.list.map((x) => x.replace(/,/g, ' ').replace(/"/g, "'")).join(',').slice(0, 250);
      const c = col(v.column);
      sb += `<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="${c}2:${c}${Math.max(200, data.length + 50)}"><formula1>${esc(`"${list}"`)}</formula1></dataValidation>`;
    }
    sb += '</dataValidations>';
  }
  sb += '<pageMargins left="0.6" right="0.6" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>';
  sb += `<pageSetup orientation="${nCols > 6 ? 'landscape' : 'portrait'}" fitToWidth="1" fitToHeight="0"/><!--DRAWING--></worksheet>`;
  return { xml: sb, dataRows: data.length, filterRef };
}

const drawingXml = (nCols) => XML_HEAD +
  '<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">' +
  `<xdr:twoCellAnchor editAs="oneCell"><xdr:from><xdr:col>${nCols + 1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>` +
  `<xdr:to><xdr:col>${nCols + 9}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>20</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>` +
  '<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="2" name="Chart 1"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>' +
  '<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">' +
  `<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="${R_DOC}" r:id="rId1"/></a:graphicData></a:graphic></xdr:graphicFrame>` +
  '<xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>';

function chartXml(sheet, name, rows, accent) {
  const ch = sheet.chart; const type = ch.type;
  const nCols = Math.max(sheet.columns.length, ...sheet.rows.map((r) => r.length), 1);
  const catCol = Math.min(nCols - 1, Math.max(0, ch.categoryColumn));
  let valCols = ch.valueColumns.filter((c) => c >= 0 && c < nCols && c !== catCol);
  if (!valCols.length) valCols = [...Array(nCols).keys()].filter((c) => c !== catCol && NUMERIC.has(sheet.columns[c]?.format)).slice(0, 3);
  if (!valCols.length) valCols = [Math.min(nCols - 1, catCol + 1)];
  const sref = sheetRef(name); const first = 2; const last = rows + 1;
  const colors = [accent, ...PALETTE.filter((c) => c !== accent)];
  const strCache = (c) => `<c:strCache><c:ptCount val="${rows}"/>${sheet.rows.slice(0, rows).map((r, i) => `<c:pt idx="${i}"><c:v>${esc(display(r[c]))}</c:v></c:pt>`).join('')}</c:strCache>`;
  const numCache = (c) => {
    const f = sheet.columns[c]?.format || 'number';
    const pts = sheet.rows.slice(0, rows).map((r, i) => {
      const v = r[c]; const d = typeof v === 'number' ? v : typeof v === 'string' && !v.startsWith('=') ? parseNumber(v, f) : null;
      return d == null ? '' : `<c:pt idx="${i}"><c:v>${d}</c:v></c:pt>`;
    }).join('');
    return `<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${rows}"/>${pts}</c:numCache>`;
  };
  const series = (idx, c) => {
    const color = colors[idx % colors.length];
    let sb = `<c:ser><c:idx val="${idx}"/><c:order val="${idx}"/>`;
    sb += `<c:tx><c:strRef><c:f>${esc(`${sref}!$${col(c)}$1`)}</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(sheet.columns[c]?.header || `Series ${idx + 1}`)}</c:v></c:pt></c:strCache></c:strRef></c:tx>`;
    if (type === 'line') sb += `<c:spPr><a:ln w="28575" cap="rnd"><a:solidFill><a:srgbClr val="${color}"/></a:solidFill><a:round/></a:ln></c:spPr><c:marker><c:symbol val="circle"/><c:size val="6"/><c:spPr><a:solidFill><a:srgbClr val="${color}"/></a:solidFill></c:spPr></c:marker>`;
    else if (type === 'pie') {
      sb += '<c:spPr><a:ln w="12700"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></c:spPr>';
      for (let p = 0; p < rows; p++) sb += `<c:dPt><c:idx val="${p}"/><c:bubble3D val="0"/><c:spPr><a:solidFill><a:srgbClr val="${colors[p % colors.length]}"/></a:solidFill><a:ln w="12700"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></c:spPr></c:dPt>`;
      sb += '<c:dLbls><c:showLegendKey val="0"/><c:showVal val="0"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="1"/><c:showBubbleSize val="0"/><c:showLeaderLines val="1"/></c:dLbls>';
    } else sb += `<c:spPr><a:solidFill><a:srgbClr val="${color}"/></a:solidFill></c:spPr><c:invertIfNegative val="0"/>`;
    sb += `<c:cat><c:strRef><c:f>${esc(`${sref}!$${col(catCol)}$${first}:$${col(catCol)}$${last}`)}</c:f>${strCache(catCol)}</c:strRef></c:cat>`;
    sb += `<c:val><c:numRef><c:f>${esc(`${sref}!$${col(c)}$${first}:$${col(c)}$${last}`)}</c:f>${numCache(c)}</c:numRef></c:val>`;
    if (type === 'line') sb += '<c:smooth val="0"/>';
    return sb + '</c:ser>';
  };
  const title = ch.title || sheet.columns[valCols[0]]?.header || sheet.name;
  const axes = `<c:catAx><c:axId val="500001"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="${type === 'bar' ? 'l' : 'b'}"/><c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/>` +
    '<c:tickLblPos val="nextTo"/><c:spPr><a:ln w="9525"><a:solidFill><a:srgbClr val="D9D4CE"/></a:solidFill></a:ln></c:spPr><c:crossAx val="500002"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>' +
    `<c:valAx><c:axId val="500002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="${type === 'bar' ? 'b' : 'l'}"/><c:majorGridlines><c:spPr><a:ln w="9525"><a:solidFill><a:srgbClr val="EEEAE6"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>` +
    '<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:spPr><a:ln><a:noFill/></a:ln></c:spPr><c:crossAx val="500001"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>';
  const plot = type === 'pie' ? `<c:pieChart><c:varyColors val="1"/>${series(0, valCols[0])}<c:firstSliceAng val="0"/></c:pieChart>`
    : type === 'line' ? `<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>${valCols.map((c, i) => series(i, c)).join('')}<c:marker val="1"/><c:axId val="500001"/><c:axId val="500002"/></c:lineChart>${axes}`
      : `<c:barChart><c:barDir val="${type === 'bar' ? 'bar' : 'col'}"/><c:grouping val="clustered"/><c:varyColors val="0"/>${valCols.map((c, i) => series(i, c)).join('')}<c:gapWidth val="70"/><c:axId val="500001"/><c:axId val="500002"/></c:barChart>${axes}`;
  return XML_HEAD + `<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${R_DOC}">` +
    '<c:roundedCorners val="0"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz="1400" b="1"/></a:pPr>' +
    `<a:r><a:rPr lang="en-US" sz="1400" b="1"/><a:t>${esc(title)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>` +
    `<c:autoTitleDeleted val="0"/><c:plotArea><c:layout/>${plot}</c:plotArea>` +
    '<c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart>' +
    '<c:spPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln><a:noFill/></a:ln></c:spPr></c:chartSpace>';
}

function xlsxStyles(accent, currency) {
  const c = String(currency).replace(/"/g, '');
  const cur = esc(`"${c} "#,##0.00;[Red]-"${c} "#,##0.00`);
  const light = tint(accent, 0.88);
  const tot = (n) => `<xf numFmtId="${n}" fontId="2" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>`;
  return XML_HEAD + `<styleSheet xmlns="${S_NS}">` +
    `<numFmts count="4"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="${cur}"/><numFmt numFmtId="166" formatCode="0.0%"/><numFmt numFmtId="167" formatCode="yyyy-mm-dd"/></numFmts>` +
    '<fonts count="3"><font><sz val="11"/><color rgb="FF2B2724"/><name val="Aptos"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Aptos"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FF1E1B18"/><name val="Aptos"/><family val="2"/></font></fonts>' +
    `<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF${accent}"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF${light}"/><bgColor indexed="64"/></patternFill></fill></fills>` +
    `<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top style="medium"><color rgb="FF${accent}"/></top><bottom/><diagonal/></border></borders>` +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="12">' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
    '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>' +
    tot(164) + tot(165) + tot(166) + tot(3) +
    '</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>';
}

function xlsx(spec) {
  const accent = cleanHex(spec.accent);
  const sheets = spec.sheets.length ? spec.sheets : [{ name: 'Sheet1', columns: [{ header: 'Notes', format: 'text', width: null }], rows: [[spec.title]], totals: false, chart: null, validations: [], freezeHeader: true }];
  const used = new Set();
  const names = sheets.map((s, i) => {
    const base = (s.name.replace(/[\\/?*[\]:]/g, ' ').trim() || `Sheet${i + 1}`).slice(0, 31);
    let n = base; let k = 2; while (used.has(n.toLowerCase())) { n = `${base.slice(0, 28)} ${k++}`; } used.add(n.toLowerCase()); return n;
  });
  const parts = {};
  let ct = XML_HEAD + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
    '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
    '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>';
  let wbRels = XML_HEAD + `<Relationships xmlns="${REL_NS}">`; let wbSheets = ''; let defined = ''; let chartNo = 0;
  sheets.forEach((sheet, i) => {
    const n = i + 1; const name = names[i]; const built = buildSheet(sheet);
    let drawingRel = '';
    if (sheet.chart && built.dataRows > 0) {
      chartNo++;
      drawingRel = '<drawing r:id="rId1"/>';
      parts[`xl/worksheets/_rels/sheet${n}.xml.rels`] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/drawing" Target="../drawings/drawing${chartNo}.xml"/></Relationships>`;
      parts[`xl/drawings/drawing${chartNo}.xml`] = drawingXml(sheet.columns.length);
      parts[`xl/drawings/_rels/drawing${chartNo}.xml.rels`] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/chart" Target="../charts/chart${chartNo}.xml"/></Relationships>`;
      parts[`xl/charts/chart${chartNo}.xml`] = chartXml(sheet, name, built.dataRows, accent);
      ct += `<Override PartName="/xl/drawings/drawing${chartNo}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>`;
      ct += `<Override PartName="/xl/charts/chart${chartNo}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>`;
    }
    parts[`xl/worksheets/sheet${n}.xml`] = built.xml.replace('<!--DRAWING-->', drawingRel);
    ct += `<Override PartName="/xl/worksheets/sheet${n}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`;
    wbRels += `<Relationship Id="rId${n}" Type="${R_DOC}/worksheet" Target="worksheets/sheet${n}.xml"/>`;
    wbSheets += `<sheet name="${esc(name)}" sheetId="${n}" r:id="rId${n}"/>`;
    if (built.filterRef) defined += `<definedName name="_xlnm._FilterDatabase" localSheetId="${i}" hidden="1">${esc(sheetRef(name))}!${built.filterRef}</definedName>`;
  });
  wbRels += `<Relationship Id="rId${sheets.length + 1}" Type="${R_DOC}/styles" Target="styles.xml"/></Relationships>`;
  parts['[Content_Types].xml'] = ct + '</Types>';
  parts['_rels/.rels'] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/officeDocument" Target="xl/workbook.xml"/>` +
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
    `<Relationship Id="rId3" Type="${R_DOC}/extended-properties" Target="docProps/app.xml"/></Relationships>`;
  parts['xl/workbook.xml'] = XML_HEAD + `<workbook xmlns="${S_NS}" xmlns:r="${R_DOC}"><bookViews><workbookView/></bookViews><sheets>${wbSheets}</sheets>` +
    (defined ? `<definedNames>${defined}</definedNames>` : '') + '<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>';
  parts['xl/_rels/workbook.xml.rels'] = wbRels;
  parts['xl/styles.xml'] = xlsxStyles(accent, spec.currency);
  parts['docProps/core.xml'] = coreProps(spec.title, spec.author, nowIso());
  parts['docProps/app.xml'] = appProps();
  return zip(parts);
}

// ------------------------------------------------------------------ PPTX

const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const CX = 12192000; const CY = 6858000;
const INK = '1E1B18'; const SOFT = '6E6760'; const PAPER = 'FAF8F6';

function pptx(spec) {
  const accent = cleanHex(spec.accent);
  const slides = spec.slides.length ? spec.slides : [{ layout: 'title', title: spec.title, subtitle: spec.subtitle, bullets: [], left: [], right: [], leftTitle: '', rightTitle: '' }];
  const grpHead = () => '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>';
  const xfrm = (x, y, w, h) => `<a:xfrm><a:off x="${Math.round(x)}" y="${Math.round(y)}"/><a:ext cx="${Math.round(w)}" cy="${Math.round(h)}"/></a:xfrm>`;
  const fill = (c, a) => `<a:solidFill><a:srgbClr val="${c}">${a < 100 ? `<a:alpha val="${a * 1000}"/>` : ''}</a:srgbClr></a:solidFill>`;
  const shape = (id, name, geom, x, y, w, h, c, a) => `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="${name} ${id}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, w, h)}` +
    `<a:prstGeom prst="${geom}"><a:avLst/></a:prstGeom>${fill(c, a)}<a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>`;
  const fit = (text, big, small, chars) => (text.length <= chars ? big : Math.max(small, Math.floor(big * chars / text.length)));
  const text = (id, x, y, w, h, runs, anchor = 't', align = 'l') => {
    const paras = runs.map((r) => `<a:p><a:pPr algn="${align}"/><a:r><a:rPr lang="en-US" sz="${r.size}" b="${r.bold ? 1 : 0}" dirty="0"><a:solidFill><a:srgbClr val="${r.color}"/></a:solidFill><a:latin typeface="Aptos"/></a:rPr><a:t>${esc(plain(r.text))}</a:t></a:r></a:p>`).join('');
    return `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Text ${id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>` +
      `<p:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="${anchor}"><a:normAutofit/></a:bodyPr><a:lstStyle/>${paras}</p:txBody></p:sp>`;
  };
  const bullets = (id, x, y, w, h, items) => {
    const list = items.length ? items : [' '];
    const total = list.reduce((a, s) => a + s.length, 0);
    const size = list.length <= 3 && total < 160 ? 2400 : list.length <= 5 && total < 320 ? 2000 : list.length <= 7 && total < 500 ? 1700 : 1400;
    const paras = list.map((item) => `<a:p><a:pPr marL="342900" indent="-342900"><a:spcBef><a:spcPts val="${size / 2}"/></a:spcBef><a:buClr><a:srgbClr val="${accent}"/></a:buClr><a:buFont typeface="Arial"/><a:buChar char="•"/></a:pPr>` +
      `<a:r><a:rPr lang="en-US" sz="${size}" dirty="0"><a:solidFill><a:srgbClr val="3A3430"/></a:solidFill><a:latin typeface="Aptos"/></a:rPr><a:t>${esc(plain(item))}</a:t></a:r></a:p>`).join('');
    return `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Bullets ${id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>` +
      `<p:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t"><a:normAutofit/></a:bodyPr><a:lstStyle/>${paras}</p:txBody></p:sp>`;
  };
  const R = (t, size, bold, color) => ({ text: t, size, bold, color });

  const slideXml = (s, n, total) => {
    let shapes = ''; let id = 2;
    const add = (x) => { shapes += x; id++; };
    const layout = s.layout;
    let bg = `<a:solidFill><a:srgbClr val="${PAPER}"/></a:solidFill>`;
    if (layout === 'title' || layout === 'closing') {
      bg = `<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:srgbClr val="F2A31B"/></a:gs><a:gs pos="55000"><a:srgbClr val="${accent}"/></a:gs><a:gs pos="100000"><a:srgbClr val="B8241B"/></a:gs></a:gsLst><a:lin ang="2700000" scaled="0"/></a:gradFill>`;
      add(shape(id, 'Glow', 'ellipse', -1600000, -2200000, 6200000, 6200000, 'FFFFFF', 12));
      add(shape(id, 'Glow', 'ellipse', 8600000, 3900000, 5200000, 5200000, 'FFFFFF', 8));
      add(text(id, 900000, 2150000, CX - 1800000, 1700000, [R(s.title, fit(s.title, 5400, 3600, 30), true, 'FFFFFF')], 'b'));
      const sub = s.subtitle || (layout === 'closing' ? 'Thank you' : '');
      if (sub) add(text(id, 900000, 3950000, CX - 1800000, 900000, [R(sub, 2000, false, 'FFF3E8')]));
      add(shape(id, 'Bar', 'rect', 900000, 1900000, 900000, 70000, 'FFFFFF', 100));
    } else if (layout === 'section') {
      bg = `<a:solidFill><a:srgbClr val="${tint(accent, 0.88)}"/></a:solidFill>`;
      add(text(id, 900000, 1500000, 3000000, 1600000, [R(String(n).padStart(2, '0'), 9600, true, accent)]));
      add(text(id, 900000, 3200000, CX - 1800000, 1300000, [R(s.title, fit(s.title, 4400, 3200, 40), true, INK)], 't'));
      if (s.subtitle) add(text(id, 900000, 4500000, CX - 1800000, 800000, [R(s.subtitle, 1800, false, SOFT)]));
    } else if (layout === 'quote') {
      add(text(id, 900000, 900000, 2000000, 1800000, [R('“', 16000, true, accent)]));
      add(text(id, 1500000, 2000000, CX - 3000000, 2600000, [R(s.title, fit(s.title, 3200, 2200, 120), false, INK)], 'ctr'));
      if (s.subtitle) add(text(id, 1500000, 4800000, CX - 3000000, 600000, [R('— ' + s.subtitle, 1600, true, SOFT)]));
    } else if (layout === 'two_column') {
      add(shape(id, 'Bar', 'rect', 0, 0, 160000, CY, accent, 100));
      add(text(id, 700000, 450000, CX - 1400000, 1000000, [R(s.title, fit(s.title, 3200, 2400, 50), true, INK)], 'b'));
      const w = (CX - 1400000 - 400000) / 2;
      [[s.leftTitle, s.left, 700000], [s.rightTitle, s.right, 700000 + w + 400000]].forEach(([t, items, x]) => {
        add(shape(id, 'Card', 'roundRect', x, 1700000, w, 4500000, 'FFFFFF', 100));
        let y = 1950000;
        if (t) { add(text(id, x + 300000, y, w - 600000, 600000, [R(t, 2000, true, accent)])); y += 650000; }
        add(bullets(id, x + 300000, y, w - 600000, 6000000 - y, items));
      });
    } else {
      add(shape(id, 'Bar', 'rect', 0, 0, 160000, CY, accent, 100));
      add(text(id, 700000, 450000, CX - 1400000, 1100000, [R(s.title, fit(s.title, 3400, 2400, 50), true, INK)], 'b'));
      add(shape(id, 'Bar', 'rect', 700000, 1650000, 700000, 50000, accent, 100));
      if (s.subtitle) add(text(id, 700000, 1780000, CX - 1400000, 500000, [R(s.subtitle, 1600, false, SOFT)]));
      const top = s.subtitle ? 2350000 : 1950000;
      add(bullets(id, 700000, top, CX - 1400000, CY - top - 700000, s.bullets));
    }
    if (layout !== 'title' && layout !== 'closing') {
      add(text(id, 700000, CY - 520000, 6000000, 320000, [R(spec.title, 1000, false, 'A39C95')]));
      add(text(id, CX - 1700000, CY - 520000, 1000000, 320000, [R(`${n} / ${total}`, 1000, true, 'A39C95')], 't', 'r'));
    }
    return XML_HEAD + `<p:sld xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}"><p:cSld><p:bg><p:bgPr>${bg}<a:effectLst/></p:bgPr></p:bg><p:spTree>${grpHead()}${shapes}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>`;
  };

  const parts = {};
  let ct = XML_HEAD + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>' +
    '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>' +
    '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>' +
    '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>' +
    '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>' +
    '<Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>' +
    '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
    '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>';
  let presRels = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/slideMaster" Target="slideMasters/slideMaster1.xml"/>`;
  let sldIds = '';
  slides.forEach((s, i) => {
    const n = i + 1;
    parts[`ppt/slides/slide${n}.xml`] = slideXml(s, n, slides.length);
    parts[`ppt/slides/_rels/slide${n}.xml.rels`] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>`;
    ct += `<Override PartName="/ppt/slides/slide${n}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>`;
    presRels += `<Relationship Id="rId${n + 1}" Type="${R_DOC}/slide" Target="slides/slide${n}.xml"/>`;
    sldIds += `<p:sldId id="${255 + n}" r:id="rId${n + 1}"/>`;
  });
  const k = slides.length + 2;
  presRels += `<Relationship Id="rId${k}" Type="${R_DOC}/theme" Target="theme/theme1.xml"/><Relationship Id="rId${k + 1}" Type="${R_DOC}/presProps" Target="presProps.xml"/><Relationship Id="rId${k + 2}" Type="${R_DOC}/viewProps" Target="viewProps.xml"/></Relationships>`;
  parts['[Content_Types].xml'] = ct + '</Types>';
  parts['_rels/.rels'] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/officeDocument" Target="ppt/presentation.xml"/>` +
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
    `<Relationship Id="rId3" Type="${R_DOC}/extended-properties" Target="docProps/app.xml"/></Relationships>`;
  parts['ppt/presentation.xml'] = XML_HEAD + `<p:presentation xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}" saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>` +
    `<p:sldIdLst>${sldIds}</p:sldIdLst><p:sldSz cx="${CX}" cy="${CY}"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:defaultTextStyle></p:presentation>`;
  parts['ppt/_rels/presentation.xml.rels'] = presRels;
  parts['ppt/presProps.xml'] = XML_HEAD + `<p:presentationPr xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}"/>`;
  parts['ppt/viewProps.xml'] = XML_HEAD + `<p:viewPr xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}"><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr><p:gridSpacing cx="76200" cy="76200"/></p:viewPr>`;
  parts['ppt/slideMasters/slideMaster1.xml'] = XML_HEAD + `<p:sldMaster xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>${grpHead()}</p:spTree></p:cSld>` +
    '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>' +
    '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>' +
    '<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2000"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:defRPr></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>';
  parts['ppt/slideMasters/_rels/slideMaster1.xml.rels'] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="${R_DOC}/theme" Target="../theme/theme1.xml"/></Relationships>`;
  parts['ppt/slideLayouts/slideLayout1.xml'] = XML_HEAD + `<p:sldLayout xmlns:a="${A}" xmlns:r="${R_DOC}" xmlns:p="${P}" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>${grpHead()}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>`;
  parts['ppt/slideLayouts/_rels/slideLayout1.xml.rels'] = XML_HEAD + `<Relationships xmlns="${REL_NS}"><Relationship Id="rId1" Type="${R_DOC}/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>`;
  const c = (n, v) => `<a:${n}><a:srgbClr val="${v}"/></a:${n}>`;
  const solid = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>';
  parts['ppt/theme/theme1.xml'] = XML_HEAD + `<a:theme xmlns:a="${A}" name="Nebula"><a:themeElements><a:clrScheme name="Nebula"><a:dk1><a:srgbClr val="${INK}"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>` +
    c('dk2', '3A3430') + c('lt2', 'F3EFEB') + c('accent1', accent) + c('accent2', 'F2A31B') + c('accent3', '7C5CFF') + c('accent4', '2BB3A3') + c('accent5', 'E5487F') + c('accent6', '3D8BFD') + c('hlink', '3D8BFD') + c('folHlink', '7C5CFF') +
    '</a:clrScheme><a:fontScheme name="Nebula"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>' +
    `<a:fmtScheme name="Nebula"><a:fillStyleLst>${solid}${solid}${solid}</a:fillStyleLst><a:lnStyleLst><a:ln w="6350">${solid}</a:ln><a:ln w="12700">${solid}</a:ln><a:ln w="19050">${solid}</a:ln></a:lnStyleLst>` +
    '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>' +
    `<a:bgFillStyleLst>${solid}${solid}${solid}</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>`;
  parts['docProps/core.xml'] = coreProps(spec.title, spec.author, nowIso());
  parts['docProps/app.xml'] = appProps();
  return zip(parts);
}

// ------------------------------------------------------------------ text formats

function csv(spec) {
  const q = (s) => (/[,"\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  const row = (cells) => cells.map((c) => q(c == null ? '' : String(c))).join(',');
  let out = '';
  const sheet = spec.sheets[0];
  if (sheet) { out += row(sheet.columns.map((c) => c.header)) + '\r\n'; sheet.rows.forEach((r) => { out += row(r) + '\r\n'; }); }
  else { const t = spec.blocks.find((b) => b.k === 'table'); if (t) { out += row(t.header) + '\r\n'; t.rows.forEach((r) => { out += row(r) + '\r\n'; }); } }
  return Buffer.concat([Buffer.from([0xEF, 0xBB, 0xBF]), Buffer.from(out, 'utf8')]);
}

function txt(spec) {
  let s = `${spec.title}\n${'='.repeat(Math.min(60, spec.title.length))}\n\n`;
  if (spec.subtitle) s += `${spec.subtitle}\n\n`;
  for (const b of spec.blocks) {
    if (b.k === 'h') s += (b.level === 1 ? plain(b.text).toUpperCase() : plain(b.text)) + '\n\n';
    else if (b.k === 'p') s += plain(b.text) + '\n\n';
    else if (b.k === 'list') { b.items.forEach((it, i) => { s += `${b.numbered ? `${i + 1}. ` : '- '}${plain(it)}\n`; }); s += '\n'; }
    else if (b.k === 'table') {
      const all = [b.header, ...b.rows]; const cols = Math.max(...all.map((r) => r.length));
      const w = [...Array(cols).keys()].map((c) => Math.min(40, Math.max(...all.map((r) => (r[c] || '').length))));
      all.forEach((r, ri) => {
        s += [...Array(cols).keys()].map((c) => (r[c] || '').slice(0, 40).padEnd(w[c])).join('  ').trimEnd() + '\n';
        if (ri === 0 && b.header.length) s += w.map((x) => '-'.repeat(x)).join('  ') + '\n';
      });
      s += '\n';
    } else if (b.k === 'quote') s += `  "${plain(b.text)}"\n\n`;
    else s += '\n\n';
  }
  return Buffer.from(s.trimEnd() + '\n', 'utf8');
}

function markdown(spec) {
  let s = `# ${spec.title}\n\n`;
  if (spec.subtitle) s += `_${spec.subtitle}_\n\n`;
  for (const b of spec.blocks) {
    if (b.k === 'h') s += `${'#'.repeat(Math.min(3, Math.max(1, b.level)) + 1)} ${b.text}\n\n`;
    else if (b.k === 'p') s += `${b.text}\n\n`;
    else if (b.k === 'list') { b.items.forEach((it, i) => { s += `${b.numbered ? `${i + 1}. ` : '- '}${it}\n`; }); s += '\n'; }
    else if (b.k === 'table') {
      const cols = Math.max(b.header.length, ...b.rows.map((r) => r.length));
      const r = (c) => `| ${[...Array(cols).keys()].map((i) => (c[i] || '').replace(/\|/g, '\\|')).join(' | ')} |\n`;
      s += r(b.header) + `|${' --- |'.repeat(cols)}\n`; b.rows.forEach((x) => { s += r(x); }); s += '\n';
    } else if (b.k === 'quote') s += `> ${b.text}\n\n`;
    else s += '---\n\n';
  }
  return Buffer.from(s, 'utf8');
}

/** Web page. With print=true it's tuned for A4 PDF output (page breaks, margins, no max-width). */
function html(spec, print = false) {
  const a = cleanHex(spec.accent);
  const e = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
  let body = '';
  for (const b of spec.blocks) {
    const lv = Math.min(3, Math.max(1, b.level || 1)) + 1;
    if (b.k === 'h') body += `<h${lv}>${e(b.text)}</h${lv}>`;
    else if (b.k === 'p') body += b.text.split('\n').filter((x) => x.trim()).map((x) => `<p>${e(x)}</p>`).join('');
    else if (b.k === 'list') { const t = b.numbered ? 'ol' : 'ul'; body += `<${t}>${b.items.map((x) => `<li>${e(x)}</li>`).join('')}</${t}>`; }
    else if (b.k === 'table') body += `<table><thead><tr>${b.header.map((x) => `<th>${e(x)}</th>`).join('')}</tr></thead><tbody>${b.rows.map((r) => `<tr>${r.map((x) => `<td>${e(x)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
    else if (b.k === 'quote') body += `<blockquote>${e(b.text)}</blockquote>`;
    else body += print ? '<div style="page-break-after:always"></div>' : '<hr>';
  }
  const page = print
    ? '@page{size:A4;margin:22mm 20mm}body{font:11pt/1.55 "Segoe UI",system-ui,sans-serif;color:#2b2724;margin:0}h1{font-size:28pt;margin:0 0 4pt}h2{color:#' + a + ';font-size:16pt;margin:18pt 0 6pt;page-break-after:avoid}h3{font-size:13pt;margin:14pt 0 4pt;page-break-after:avoid}h4{font-size:11.5pt;color:#4a443f}tr{page-break-inside:avoid}'
    : 'body{font:16px/1.6 system-ui,sans-serif;color:#2b2724;max-width:760px;margin:48px auto;padding:0 20px}h1{font-size:2.2em;margin:0}h2{color:#' + a + '}';
  const doc = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(spec.title)}</title><style>${page}` +
    `table{border-collapse:collapse;width:100%;margin:8pt 0}th{background:#${a};color:#fff;text-align:left}th,td{padding:6px 9px;border-bottom:1px solid #ddd8d2}tbody tr:nth-child(even){background:#${tint(a, 0.93)}}` +
    `blockquote{border-left:4px solid #${a};margin:14px 0;padding:4px 16px;color:#5a534d;font-style:italic}.sub{color:#6e6760;border-bottom:3px solid #${a};padding-bottom:12px;margin-bottom:18px}</style></head><body>` +
    `<h1>${esc(spec.title)}</h1>${spec.subtitle ? `<p class="sub">${esc(spec.subtitle)}</p>` : ''}${body}</body></html>`;
  return Buffer.from(doc, 'utf8');
}

/**
 * Builds the file bytes for a spec. PDF needs [printPdf] (html string → Buffer), which the
 * Electron main process supplies.
 */
async function build(spec, printPdf) {
  switch (spec.type) {
    case 'docx': return docx(spec);
    case 'xlsx': return xlsx(spec);
    case 'pptx': return pptx(spec);
    case 'csv': return csv(spec);
    case 'txt': return txt(spec);
    case 'md': return markdown(spec);
    case 'html': return html(spec);
    case 'pdf': if (!printPdf) throw new Error('PDF printer unavailable'); return printPdf(html(spec, true).toString('utf8'));
    default: return docx(spec);
  }
}

/** One-line description for the AI and the Files list. */
function describe(spec) {
  if (spec.type === 'xlsx' || spec.type === 'csv') return `${spec.sheets.length || 1} sheet(s), ${spec.sheets.reduce((a, s) => a + s.rows.length, 0)} rows`;
  if (spec.type === 'pptx') return `${spec.slides.length || 1} slides`;
  return `${spec.blocks.length} sections`;
}

module.exports = { parse, toJson, build, describe, parseType, TYPES, SCHEMA_HINT, zip, parseNumber, dateSerial };
