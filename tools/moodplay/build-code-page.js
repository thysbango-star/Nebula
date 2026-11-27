'use strict';
// Builds shared/moodplay/code.html — the page a TV (or phone, or computer) sees after typing the short Mood PLAY
// address: enter the code, with ads for our products beside it. The characters and the Nebula bot are drawn from
// the same artwork as the app (desktop/src/ws-shapes.js), unchanged. Run: node tools/moodplay/build-code-page.js
const fs = require('fs'); const path = require('path');
const root = path.join(__dirname, '..', '..');
const w = {}; new Function('window', fs.readFileSync(path.join(root, 'desktop/src/ws-shapes.js'), 'utf8'))(w);
const SH = w.NB_SHAPES;
const tpl = fs.readFileSync(path.join(__dirname, 'code.template.html'), 'utf8');

const ch = (i, cls = '') => { const c = SH.chars[i]; return `<svg class="ch ${cls}" viewBox="-56 -58 112 114"><path d="${c.d}" fill="${c.c}"/>${c.cap ? `<path d="${c.cap}" fill="${c.capc}"/>` : ''}<path d="${c.e}" fill="#fff"/></svg>`; };
// The pile, as in the artwork (positions from the original image).
function pile(cls) {
  const xs = SH.chars.map((c) => c.x); const ys = SH.chars.map((c) => c.y);
  const x0 = Math.min(...xs) - 130; const x1 = Math.max(...xs) + 130; const y0 = Math.min(...ys) - 130; const y1 = Math.max(...ys) + 130;
  const order = [3, 6, 1, 4, 8, 0, 2, 5, 7, 9];
  return `<svg class="${cls}" viewBox="${x0} ${y0} ${x1 - x0} ${y1 - y0}">${order.map((i) => { const c = SH.chars[i]; const s = c.size / 100; return `<g transform="translate(${c.x} ${c.y}) scale(${s})"><path d="${c.d}" fill="${c.c}"/>${c.cap ? `<path d="${c.cap}" fill="${c.capc}"/>` : ''}<path d="${c.e}" fill="#fff"/></g>`; }).join('')}</svg>`;
}
const logo = (fg, eye) => `<svg class="bot" viewBox="0 0 100 ${SH.logo.h.toFixed(1)}"><path d="${SH.logo.d}" fill="${fg}"/><path d="${SH.logo.e}" fill="${eye}"/></svg>`;
// Rovian Intelligence mark: four round petals around a square.
const rovian = (fill, hole) => `<svg class="rmark" viewBox="-50 -50 100 100"><g fill="${fill}"><circle cx="0" cy="-24" r="24"/><circle cx="24" cy="0" r="24"/><circle cx="0" cy="24" r="24"/><circle cx="-24" cy="0" r="24"/><rect x="-24" y="-24" width="48" height="48"/></g><rect x="-15" y="-15" width="30" height="30" fill="${hole}"/></svg>`;
// Flowing parallel lines (the Rovian and Wave backgrounds).
function waves(cls, color, n, amp, w0 = 1000, h0 = 600, gap = 14, sw = 5) {
  let d = '';
  for (let k = 0; k < n; k++) {
    const y = 40 + k * gap; let p = `M -20 ${y}`;
    for (let x = 0; x <= w0 + 40; x += 40) p += ` L ${x} ${(y + Math.sin(x / 170 + k * 0.18) * amp + Math.sin(x / 63 + k * 0.05) * amp * 0.18).toFixed(1)}`;
    d += `<path d="${p}"/>`;
  }
  return `<svg class="${cls}" viewBox="0 0 ${w0} ${h0}" preserveAspectRatio="none"><g fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round">${d}</g></svg>`;
}

const out = tpl
  .replace('<!--PILE_A-->', pile('pile a')).replace('<!--PILE_B-->', pile('pile b'))
  .replace('<!--BOT-->', logo('#fff', '#5200F4'))
  .replace('<!--ROVIAN-->', rovian('#06070f', '#2948d6'))
  .replace('<!--WAVES_R1-->', waves('wv w1', '#fff', 12, 70)).replace('<!--WAVES_R2-->', waves('wv w2', '#fff', 12, 70))
  .replace('<!--WAVES_W-->', waves('wv ww', '#3DF27A', 18, 60, 1000, 600, 16, 3))
  .replace('<!--CH7-->', ch(7)).replace('<!--CH2-->', ch(2)).replace('<!--CH3-->', ch(3));
fs.writeFileSync(path.join(root, 'shared', 'moodplay', 'code.html'), out);
console.log(`code.html: ${(out.length / 1024).toFixed(1)} KB`);
