'use strict';
// Nebula Workspace — the welcome questions (from the "Welcome to Nebula Workspace" video) and, after them, the
// characters scrambling to spell "Nebula Workspace". One canvas, pre-rendered sprites, transforms only, so it
// stays smooth on slow laptops; it drops to 1× resolution by itself if frames start taking too long.
(() => {
  const SH = window.NB_SHAPES;
  const BRAND = '#5200F4';
  const $ = (id) => document.getElementById(id);

  // ---------------------------------------------------------------- SVG helpers (used by the app too)
  function charSvg(i, color, extra = '') {
    const c = SH.chars[i % SH.chars.length];
    return `<svg viewBox="-52 -52 104 104" ${extra}><path d="${c.d}" fill="${color || c.c}"/>${c.cap ? `<path d="${c.cap}" fill="${c.capc}"/>` : ''}<path class="eyes" d="${c.e}" fill="#fff"/></svg>`;
  }
  function logoSvg(fg = '#fff', eye = BRAND, extra = '') {
    return `<svg viewBox="0 0 100 ${SH.logo.h.toFixed(1)}" ${extra}><path d="${SH.logo.d}" fill="${fg}"/><path d="${SH.logo.e}" fill="${eye}"/></svg>`;
  }
  /** The pile exactly as in the artwork (positions from the original image). */
  function pileSvg() {
    const xs = SH.chars.map((c) => c.x); const ys = SH.chars.map((c) => c.y);
    const x0 = Math.min(...xs) - 130; const x1 = Math.max(...xs) + 130; const y0 = Math.min(...ys) - 130; const y1 = Math.max(...ys) + 130;
    const order = [3, 6, 1, 4, 8, 0, 2, 5, 7, 9]; // back to front, as drawn
    return `<svg viewBox="${x0} ${y0} ${x1 - x0} ${y1 - y0}" class="pile">${order.map((i) => { const c = SH.chars[i]; const s = c.size / 100; return `<g transform="translate(${c.x} ${c.y}) scale(${s})"><path d="${c.d}" fill="${c.c}"/>${c.cap ? `<path d="${c.cap}" fill="${c.capc}"/>` : ''}<path d="${c.e}" fill="#fff"/></g>`; }).join('')}</svg>`;
  }
  window.NB = { charSvg, logoSvg, pileSvg };

  // ---------------------------------------------------------------- onboarding
  // The video's screens, with real fields and buttons: a progress row, a big input with a round arrow, Back,
  // answer cards, and a character in the corner who watches what you type and hops when you answer.
  const SCREENS = ['welcome', 'ready', 'name', 'spin', 'heard', 'work', 'team', 'enter'];
  const QUESTIONS = ['name', 'heard', 'work', 'team'];
  const answers = { name: '', heard: '', work: '', team: '' };
  let at = -1; let busy = false;
  const ARROW = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>';
  const BACK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M11 18l-6-6 6-6"/></svg>';
  const dots = (k) => `<div class="step" aria-label="Question ${k + 1} of 4">${QUESTIONS.map((_q, i) => `<i class="${i <= k ? 'on' : ''}"></i>`).join('')}</div>`;
  const field = (id, ph, max) => `<div class="field"><input id="${id}" maxlength="${max}" autocomplete="off" spellcheck="false" placeholder="${ph}"><button class="go" data-go="${id}" title="Next" disabled>${ARROW}</button></div>`;
  /** A character with separate eyes, so it can look and blink. */
  function peekSvg(i, color) {
    const c = SH.chars[i]; const eyes = c.e.split(/(?=M)/).filter((x) => x.trim());
    return `<svg viewBox="-56 -58 112 114"><path d="${c.d}" fill="${color || c.c}"/>${c.cap ? `<path d="${c.cap}" fill="${c.capc}"/>` : ''}<g class="ey">${eyes.map((e) => `<path d="${e}" fill="#fff"/>`).join('')}</g></svg>`;
  }

  function build(prefill) {
    const o = $('onb');
    o.innerHTML = `
      <section class="scr" data-s="welcome"><div class="mid"><h1>Welcome to Nebula Workspace</h1><p class="sub">Let’s get your bots set up</p></div><div class="mascot">${logoSvg()}</div></section>
      <section class="scr" data-s="ready"><div class="mid up"><h2>We’re Getting Things Ready For You</h2><div class="bar"><i id="obBar"></i></div></div><div class="pilewrap">${pileSvg()}</div></section>
      <section class="scr" data-s="name"><div class="mid">${dots(0)}<h2>What’s Your Name?</h2>${field('obName', 'Your first name', 40)}<p class="hint">Press <kbd>Enter ↵</kbd></p></div></section>
      <section class="scr" data-s="spin"><div class="mid"><div class="ring"></div></div></section>
      <section class="scr" data-s="heard"><button class="back" data-back="2" title="Back">${BACK}</button><div class="mid">${dots(1)}<h2>How did you hear about us?</h2>${field('obHeard', 'A friend, TikTok, a search…', 80)}<button class="skip" data-skip="heard">Skip</button></div></section>
      <section class="scr" data-s="work"><button class="back" data-back="4" title="Back">${BACK}</button><div class="mid">${dots(2)}<h2 class="big">What do you do?</h2>${field('obWork', 'Student, designer, run a shop…', 80)}<p class="hint">Your bots use this to pitch their help at the right level.</p></div></section>
      <section class="scr" data-s="team"><button class="back" data-back="5" title="Back">${BACK}</button><div class="mid">${dots(3)}<h2>How many people are in your team?</h2>
        <div class="choices"><button class="choice" id="obJust">${peekSvg(7)}Just me<small>Bots work for you</small></button><button class="choice" id="obNum"><span style="display:flex;gap:4px">${peekSvg(2).replace('<svg', '<svg style="width:40px;height:40px"')}${peekSvg(5).replace('<svg', '<svg style="width:40px;height:40px"')}</span>A team<small>Bots help everyone</small></button></div>
        <div class="numfield" id="obNumWrap"><div class="field" style="width:min(300px,60vw)"><input id="obNumIn" type="number" min="2" max="100000" placeholder="How many?"><button class="go" data-go="obNumIn" title="Next" disabled>${ARROW}</button></div></div></div></section>
      <section class="scr" data-s="enter"><div class="mid"><h2>You’re all set<span id="obHi"></span></h2><button class="cta" id="obEnter">Enter the Workspace ${ARROW}</button></div><div class="mascot">${logoSvg()}</div></section>
      <div class="peek" id="obPeek" hidden>${peekSvg(3)}</div>`;
    $('obName').value = prefill || '';
    const next = {
      obName: () => { const v = $('obName').value.trim(); if (!v) return; answers.name = v; hop(); go(3); setTimeout(() => go(4), 1100); },
      obHeard: () => { answers.heard = $('obHeard').value.trim(); hop(); go(5); },
      obWork: () => { const v = $('obWork').value.trim(); if (!v) return; answers.work = v; hop(); go(6); },
      obNumIn: () => { const n = Math.round(+$('obNumIn').value); if (!(n > 0)) return; answers.team = String(n); hop(); go(7); },
    };
    for (const id of Object.keys(next)) {
      const inp = $(id); const btn = o.querySelector(`[data-go="${id}"]`);
      const sync = () => { btn.disabled = !inp.value.trim() && id !== 'obHeard'; look(inp); };
      inp.addEventListener('input', sync); sync();
      inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') next[id](); });
      btn.onclick = () => next[id]();
    }
    o.querySelector('[data-skip=heard]').onclick = () => next.obHeard();
    o.querySelectorAll('[data-back]').forEach((b) => (b.onclick = () => go(+b.dataset.back, true)));
    $('obJust').onclick = () => { answers.team = '1'; hop(); go(7); };
    $('obNum').onclick = () => { $('obNumWrap').classList.add('on'); setTimeout(() => $('obNumIn').focus(), 30); };
    $('obEnter').onclick = finish;
  }

  // The corner character: eyes follow the text you type (and the mouse), and it hops when you answer.
  function look(inp) {
    const p = $('obPeek'); if (!p || p.hidden) return;
    const r = inp.getBoundingClientRect(); const pr = p.getBoundingClientRect();
    const len = Math.min(1, inp.value.length / 24);
    const tx = r.left + 30 + (r.width - 60) * len; const ty = r.top + r.height / 2;
    aim(tx - (pr.left + pr.width / 2), ty - (pr.top + pr.height * 0.3));
  }
  function aim(dx, dy) { const ey = $('obPeek')?.querySelector('.ey'); if (!ey) return; const d = Math.hypot(dx, dy) || 1; ey.style.transform = `translate(${(dx / d) * 7}px, ${(dy / d) * 6}px)`; }
  function hop() { const p = $('obPeek'); if (!p) return; p.classList.remove('hop'); void p.offsetWidth; p.classList.add('hop'); }
  addEventListener('mousemove', (e) => { const p = $('obPeek'); if (!p || p.hidden || $('onb').hidden || document.activeElement?.tagName === 'INPUT') return; const r = p.getBoundingClientRect(); aim(e.clientX - (r.left + r.width / 2), e.clientY - (r.top + r.height * 0.3)); }, { passive: true });

  function go(i, back = false) {
    if (busy && i !== 4) return;
    const prev = at; at = i;
    const els = [...document.querySelectorAll('#onb .scr')];
    const cur = els[i]; const old = els[prev];
    const kind = back ? 'f' : i <= 2 ? 'h' : i === 3 || i === 4 ? 'v' : 'f'; // slide sideways, slide up, or crossfade — as in the video
    if (old) { old.className = `scr out-${kind}`; }
    cur.className = `scr from-${kind}`;
    void cur.offsetWidth; // start the transition from the "from" state
    cur.className = 'scr on';
    const inp = cur.querySelector('.field input'); if (inp && cur.dataset.s !== 'team') setTimeout(() => { inp.focus(); look(inp); }, 420);
    const peek = $('obPeek'); if (peek) peek.hidden = !QUESTIONS.includes(SCREENS[i]);
    if (SCREENS[i] === 'enter') $('obHi').textContent = answers.name ? `, ${answers.name}` : '';
    if (SCREENS[i] === 'ready') { const b = $('obBar'); b.style.transition = 'none'; b.style.transform = 'scaleX(.04)'; requestAnimationFrame(() => requestAnimationFrame(() => { b.style.transition = 'transform 2.6s cubic-bezier(.3,.6,.3,1)'; b.style.transform = 'scaleX(1)'; })); setTimeout(() => go(2), 3000); }
  }

  async function finish() {
    if (busy) return; busy = true;
    try { await window.nebula.invoke('ws:profile', { name: answers.name, heard: answers.heard, work: answers.work, team: answers.team, onboarded: true, onboardedAt: Date.now() }); } catch { /* still carry on */ }
    const o = $('onb'); o.classList.add('leaving');
    setTimeout(() => { o.hidden = true; }, 420);
    playIntro(() => { busy = false; });
  }

  window.NBOnboarding = {
    start(prefill) { $('onb').hidden = false; $('onb').classList.remove('leaving'); build(prefill); at = -1; busy = false; go(0); setTimeout(() => { if (at === 0) go(1); }, 2600); $('onb').onclick = (e) => { if (at === 0 && !e.target.closest('input,button')) go(1); }; },
  };

  // ---------------------------------------------------------------- the intro animation
  // One bot wakes in the centre, the others arrive from every side — each with its own way of moving — gather
  // around it, line up, and introduce the wordmark. One canvas, pre-rendered sprites and backgrounds, transforms
  // only; it drops to 1× resolution and skips the glow on its own if frames run slow. Skip and mute are always
  // on screen; reduced motion gets a short, still version.
  const WORD = 'Nebula Workspace';
  const SIGN = 'Powered by Rovian Intelligence';
  const MUTE_KEY = 'nb-intro-muted';
  const muted = () => { try { return localStorage.getItem(MUTE_KEY) === '1'; } catch { return false; } };

  /** The startup sound: original, quiet, synthesised (no samples). Times are seconds from the start. */
  function introSound(reduce) {
    let ac;
    try { ac = new (window.AudioContext || window.webkitAudioContext)(); } catch { return null; }
    const out = ac.createGain(); out.gain.value = muted() ? 0 : 0.55; out.connect(ac.destination);
    const t0 = ac.currentTime + 0.05;
    const tone = (at, f, dur, gain, type = 'sine', attack = 0.02) => {
      const o = ac.createOscillator(); const g = ac.createGain(); o.type = type; o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t0 + at); g.gain.exponentialRampToValueAtTime(gain, t0 + at + attack); g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + dur);
      o.connect(g).connect(out); o.start(t0 + at); o.stop(t0 + at + dur + 0.05); return o;
    };
    // 1. soft digital activation: a little rising two-note bloom
    const a = tone(0.1, 392, 0.9, 0.05, 'sine', 0.06); a.frequency.setValueAtTime(330, t0 + 0.1); a.frequency.exponentialRampToValueAtTime(392, t0 + 0.4);
    tone(0.22, 784, 0.6, 0.018, 'triangle', 0.04);
    if (!reduce) {
      // 2. airy movement while they arrive: filtered noise with a slow sweep
      const len = ac.sampleRate * 2.2; const buf = ac.createBuffer(1, len, ac.sampleRate); const d = buf.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * 0.6;
      const n = ac.createBufferSource(); n.buffer = buf; const bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 1.2;
      bp.frequency.setValueAtTime(700, t0 + 0.6); bp.frequency.exponentialRampToValueAtTime(2600, t0 + 2.6);
      const ng = ac.createGain(); ng.gain.setValueAtTime(0.0001, t0 + 0.6); ng.gain.exponentialRampToValueAtTime(0.022, t0 + 1.4); ng.gain.exponentialRampToValueAtTime(0.0001, t0 + 2.8);
      n.connect(bp).connect(ng).connect(out); n.start(t0 + 0.6); n.stop(t0 + 2.9);
      // 3. settle: a light, rounded pluck
      tone(3.62, 659.3, 0.5, 0.045, 'triangle', 0.008); tone(3.7, 987.8, 0.45, 0.025, 'sine', 0.008);
    }
    // 4. the signature as the wordmark appears: a calm open chord with a high shimmer
    const s = reduce ? 0.5 : 4.0;
    [[329.6, 0.03], [493.9, 0.024], [659.3, 0.02], [987.8, 0.012]].forEach(([f, g], i) => tone(s + i * 0.05, f, 2.2, g, 'sine', 0.25));
    tone(s + 0.35, 1975.5, 1.4, 0.006, 'sine', 0.3);
    return { set(m) { out.gain.setTargetAtTime(m ? 0 : 0.55, ac.currentTime, 0.05); }, close() { setTimeout(() => ac.close().catch(() => {}), 400); } };
  }

  function playIntro(done) {
    const layer = $('intro'); const cv = $('introCanvas'); const skip = $('introSkip');
    layer.hidden = false; layer.classList.remove('leaving');
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    let dpr = Math.min(1.5, devicePixelRatio || 1); let lite = document.body.classList.contains('lite');
    const ctx = cv.getContext('2d', { alpha: false });
    const FONT = (px, w = 800) => `${w} ${px}px "NB Open Sans", "Open Sans", "Segoe UI", sans-serif`;
    const lerp = (a, b, k) => a + (b - a) * k;
    const cl = (k) => Math.min(1, Math.max(0, k));
    const outCubic = (k) => 1 - Math.pow(1 - cl(k), 3);
    const outQuint = (k) => 1 - Math.pow(1 - cl(k), 5);
    const inOut = (k) => { k = cl(k); return k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2; };
    const outBack = (k, s = 1.1) => { k = cl(k) - 1; return 1 + (s + 1) * k * k * k + s * k * k; };
    function mix(hex, k) { const n = parseInt(hex.slice(1), 16); const r = n >> 16; const g = (n >> 8) & 255; const b = n & 255; return `rgb(${Math.round(lerp(r, 255, k))},${Math.round(lerp(g, 255, k))},${Math.round(lerp(b, 255, k))})`; }

    // The cast: the ten characters, the moon wakes first in the middle of the line-up.
    const ROW = [0, 7, 2, 9, 3, 5, 4, 8, 1, 6]; const LEAD = 3;
    const STYLE = { 0: 'glide', 7: 'hop', 2: 'spin', 9: 'arc', 5: 'glide', 4: 'arc', 8: 'spin', 1: 'hop', 6: 'arc' };
    let W = 0; let H = 0; let size = 0; let fs = 0; let base = 0; let rowY = 0; let signY = 0; let slots = []; let rowX = [];
    let bg = null; let glow = null; let txt = null; const sprites = new Map();

    function layout() {
      W = innerWidth; H = innerHeight;
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cv.style.width = `${W}px`; cv.style.height = `${H}px`;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      fs = Math.round(Math.min(W * 0.078, H * 0.13, 132));
      size = Math.min(W * 0.062, H * 0.1, 108);
      const signPx = Math.max(14, Math.round(fs * 0.2));
      // One centred block: the line-up, the wordmark, the signature.
      const block = size + size * 0.6 + fs * 0.74 + fs * 0.62 + signPx;
      const top = (H - block) / 2;
      rowY = top + size / 2; base = top + size + size * 0.6 + fs * 0.74; signY = base + fs * 0.62 + signPx * 0.8;
      ctx.font = FONT(fs);
      const total = ctx.measureText(WORD).width; const x0 = (W - total) / 2;
      slots = [];
      for (let i = 0; i < WORD.length; i++) { if (WORD[i] === ' ') continue; slots.push({ ch: WORD[i], left: x0 + ctx.measureText(WORD.slice(0, i)).width }); }
      // The line-up spans the wordmark exactly, so the bots sit over it like a crest.
      const gap = Math.max(size * 1.12, Math.min((total - size * 0.8) / (ROW.length - 1), (W * 0.92 - size) / (ROW.length - 1))); rowX = ROW.map((_s, i) => W / 2 + (i - (ROW.length - 1) / 2) * gap);
      // Background: electric violet, a darker vignette, faint depth — drawn once.
      bg = document.createElement('canvas'); bg.width = cv.width; bg.height = cv.height; const b = bg.getContext('2d'); b.scale(dpr, dpr);
      const lin = b.createLinearGradient(0, 0, W, H); lin.addColorStop(0, '#5a0cf8'); lin.addColorStop(0.55, '#4f00ec'); lin.addColorStop(1, '#3f00c4'); b.fillStyle = lin; b.fillRect(0, 0, W, H);
      const depth = b.createRadialGradient(W * 0.72, H * 0.2, 0, W * 0.72, H * 0.2, Math.max(W, H) * 0.6); depth.addColorStop(0, 'rgba(150,110,255,.16)'); depth.addColorStop(1, 'rgba(150,110,255,0)'); b.fillStyle = depth; b.fillRect(0, 0, W, H);
      const vig = b.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.hypot(W, H) * 0.62); vig.addColorStop(0, 'rgba(18,0,60,0)'); vig.addColorStop(1, 'rgba(18,0,60,.55)'); b.fillStyle = vig; b.fillRect(0, 0, W, H);
      // The soft light behind the centre (drawn each frame with its own strength).
      const gr = Math.max(W, H) * 0.5; glow = document.createElement('canvas'); glow.width = glow.height = Math.ceil(gr * 2 * Math.min(dpr, 1));
      const g = glow.getContext('2d'); const rg = g.createRadialGradient(glow.width / 2, glow.height / 2, 0, glow.width / 2, glow.height / 2, glow.width / 2);
      rg.addColorStop(0, 'rgba(190,160,255,.42)'); rg.addColorStop(0.45, 'rgba(160,120,255,.12)'); rg.addColorStop(1, 'rgba(160,120,255,0)'); g.fillStyle = rg; g.fillRect(0, 0, glow.width, glow.height); glow.r = gr;
      txt = document.createElement('canvas'); txt.width = cv.width; txt.height = cv.height;
      sprites.clear();
    }
    function sprite(i) {
      const px = Math.round(size); const key = `${i}:${px}:${dpr}`; if (sprites.has(key)) return sprites.get(key);
      const c = SH.chars[i]; const s = Math.ceil(px * dpr * 1.12);
      const body = document.createElement('canvas'); body.width = body.height = s; const g = body.getContext('2d');
      g.translate(s / 2, s / 2); g.scale(s / 108, s / 108); g.fillStyle = c.c; g.fill(new Path2D(c.d)); if (c.cap) { g.fillStyle = c.capc; g.fill(new Path2D(c.cap)); }
      const eyes = document.createElement('canvas'); eyes.width = eyes.height = s; const e = eyes.getContext('2d');
      e.translate(s / 2, s / 2); e.scale(s / 108, s / 108); e.fillStyle = '#fff'; e.fill(new Path2D(c.e));
      const px2 = e.getImageData(0, 0, s, s).data; let y0 = s; let y1 = 0; let x0 = s; let x1 = 0;
      for (let y = 0; y < s; y += 2) for (let x = 0; x < s; x += 2) if (px2[(y * s + x) * 4 + 3] > 40) { if (y < y0) y0 = y; if (y > y1) y1 = y; if (x < x0) x0 = x; if (x > x1) x1 = x; }
      const r = { body, eyes, s: s / dpr, ecx: ((x0 + x1) / 2 - s / 2) / dpr, ecy: ((y0 + y1) / 2 - s / 2) / dpr }; sprites.set(key, r); return r;
    }
    layout();

    // Timeline (seconds). The whole intro is ~7.6 s; reduced motion gets ~2.6 s with fades only.
    const T = reduce ? { wake: 0, arrive: 0, gather: 0, form: 0, formEnd: 0, settle: 0, word: 0.35, white: 0.9, sign: 0.9, sweep: 99, breathe: 99, rest: 1.3, out: 2.6 }
      : { wake: 0.15, arrive: 0.7, gather: 2.35, form: 2.45, formEnd: 3.65, settle: 3.65, word: 3.95, white: 4.75, sign: 5.05, sweep: 5.45, breathe: 5.9, rest: 6.2, out: 7.6 };

    // Each arriving bot: a direction, a way of moving, an arrival time and a resting place around the lead.
    const others = ROW.filter((s) => s !== LEAD);
    const bots = ROW.map((sh, slot) => {
      const lead = sh === LEAD; const k = others.indexOf(sh);
      const ang = lead ? 0 : (-Math.PI / 2) + (k / others.length) * Math.PI * 2 + 0.35; // around the clock, starting top
      const R = Math.hypot(W, H) * 0.62;
      const ring = 1.55 + (k % 2) * 0.55; // a loose two-ring gathering around the lead
      return { sh, slot, lead, style: lead ? 'lead' : STYLE[sh], ang,
        sx: W / 2 + Math.cos(ang) * R, sy: H / 2 + Math.sin(ang) * R,
        gx: W / 2 + Math.cos(ang + 0.5) * size * ring * 1.6, gy: rowY + Math.sin(ang + 0.5) * size * ring * 0.9 + size * 0.2,
        at: lead ? 0 : T.arrive + k * 0.12, dur: lead ? 0 : 1.15 + (k % 3) * 0.12,
        spin: (k % 2 ? 1 : -1) * (0.7 + (k % 3) * 0.2), bend: (k % 2 ? 1 : -1) * size * (2.2 + (k % 3) * 0.6),
        blinkAt: 1.2 + Math.random() * 3, formAt: 0, x: 0, y: 0, r: 0, sx2: 1, sy2: 1, a: 0, lx: 0, ly: 0, open: 1 };
    });
    // Line up in the order that keeps paths from crossing: the gathering, sorted left to right, takes the slots.
    { const order = bots.filter((b) => !b.lead).sort((a, b) => a.gx - b.gx); const free = ROW.map((_s, i) => i).filter((i) => ROW[i] !== LEAD);
      order.forEach((b, i) => { b.slot = free[i]; }); }
    bots.forEach((b) => { b.formAt = T.form + Math.abs(b.slot - (ROW.length - 1) / 2) * 0.045; });

    function place(b, t) {
      // Final row position (with the subtle closing wave).
      const fx = rowX[b.slot]; const fy = rowY;
      let x; let y; let r = 0; let sx = 1; let sy = 1; let a = 1; let lx = 0; let ly = 0; let open = 1;
      if (reduce) { x = fx; y = fy; a = outCubic(t / 0.5); }
      else if (t < b.formAt) {
        if (b.lead) {
          // Wakes up: grows in with a gentle overshoot, opens its eyes, a ring of light spreads out.
          const k = (t - T.wake) / 0.7; const s = t < T.wake ? 0 : outBack(k, 0.9);
          x = W / 2; y = rowY + size * 0.2; sx = sy = s; a = cl(k * 2.5); open = cl((t - T.wake - 0.32) / 0.18);
          if (t > T.arrive) { const look = Math.sin((t - T.arrive) * 2.1); lx = look * 0.6; }
        } else if (t < b.at) { x = b.sx; y = b.sy; a = 0; }
        else {
          const k = (t - b.at) / b.dur; const e = b.style === 'glide' ? outQuint(k) : outCubic(k);
          x = lerp(b.sx, b.gx, e); y = lerp(b.sy, b.gy, e); a = 1;
          if (b.style === 'arc') { const p = Math.sin(e * Math.PI); x += Math.cos(b.ang + Math.PI / 2) * b.bend * p * 0.35; y += Math.sin(b.ang + Math.PI / 2) * b.bend * p * 0.35; }
          if (b.style === 'spin') r = b.spin * (1 - e);
          if (b.style === 'hop' && k > 0.7 && k < 1.3) { const h = (k - 0.7) / 0.6; y -= Math.sin(h * Math.PI) * size * 0.14 * (1 - h * 0.5); }
          if (b.style === 'glide') r = Math.sin(e * Math.PI) * 0.12 * (b.ang > Math.PI / 2 ? 1 : -1);
          // look where it's going, then at the lead
          const vx = b.gx - b.sx; const vy = b.gy - b.sy; const d = Math.hypot(vx, vy) || 1;
          lx = (vx / d) * (1 - e); ly = (vy / d) * (1 - e) * 0.6;
          if (k >= 1) { const toL = Math.sign(W / 2 - b.gx); lx = toL * 0.7; ly = 0; const idle = Math.sin((t - b.at) * 2.3 + b.slot); y += idle * size * 0.015; }
        }
      } else {
        // Forming the line: smooth, staggered from the middle out, a slight arc, natural deceleration.
        const from = b.lead ? { x: W / 2, y: rowY + size * 0.2 } : { x: b.gx, y: b.gy };
        const k = (t - b.formAt) / (T.formEnd - T.form - 0.2); const e = inOut(k);
        x = lerp(from.x, fx, e); y = lerp(from.y, fy, e) - Math.sin(e * Math.PI) * size * 0.25;
        lx = Math.sign(fx - from.x) * Math.sin(e * Math.PI) * 0.8;
        if (k >= 1) {
          // Landed: a tiny squash, then calm. They glance down at the wordmark as it appears, then at you.
          const st = t - (b.formAt + (T.formEnd - T.form - 0.2)); const q = cl(st / 0.32);
          sy = 1 - Math.sin(q * Math.PI) * 0.06; sx = 1 + Math.sin(q * Math.PI) * 0.04;
          if (t > T.word - 0.1 && t < T.sign + 0.2) { ly = 0.9; lx = (W / 2 - fx) / W; } else if (t >= T.sign + 0.2) { lx = 0; ly = 0.15; }
          // the final wave: one small breath along the line, left to right
          const w = (t - T.breathe - b.slot * 0.05) / 0.5; if (w > 0 && w < 1) { const p = Math.sin(w * Math.PI); sy *= 1 + p * 0.05; sx *= 1 - p * 0.02; y -= p * size * 0.05; }
        }
      }
      // blinks (not while waking)
      if (!reduce && t > 1.0) { const bt = t - b.blinkAt; if (bt > 0 && bt < 0.12) open = Math.min(open, 0.12); else if (bt >= 0.12) b.blinkAt = t + 2.2 + Math.random() * 2.8; }
      Object.assign(b, { x, y, r, sx2: sx, sy2: sy, a, lx, ly, open });
    }

    function draw(t) {
      ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
      ctx.drawImage(bg, 0, 0, W, H);
      if (!lite) {
        // the centre light: there from the start, a little brighter as the wordmark lands, one slow breath
        let gl = 0.55 + 0.25 * outCubic((t - T.word) / 0.8);
        const br = (t - T.breathe) / 1.1; if (br > 0 && br < 1) gl += Math.sin(br * Math.PI) * 0.22;
        ctx.globalAlpha = cl(gl * outCubic(t / 0.6 + (reduce ? 1 : 0))); ctx.drawImage(glow, W / 2 - glow.r, (rowY + base) / 2 - glow.r, glow.r * 2, glow.r * 2); ctx.globalAlpha = 1;
      }
      // the lead's wake-up ring
      if (!reduce && t > T.wake + 0.2 && t < T.wake + 1.3) { const k = (t - T.wake - 0.2) / 1.1; ctx.strokeStyle = `rgba(255,255,255,${(0.35 * (1 - k)).toFixed(3)})`; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(W / 2, rowY + size * 0.2, size * (0.6 + outCubic(k) * 1.4), 0, Math.PI * 2); ctx.stroke(); }
      for (const b of bots) {
        place(b, t); if (b.a <= 0.001 || b.sx2 <= 0.001) continue;
        const sp = sprite(b.sh);
        ctx.save(); ctx.globalAlpha = b.a; ctx.translate(b.x, b.y + sp.s * 0.4); ctx.rotate(b.r); ctx.scale(b.sx2, b.sy2); ctx.translate(0, -sp.s * 0.4);
        ctx.drawImage(sp.body, -sp.s / 2, -sp.s / 2, sp.s, sp.s);
        ctx.translate(sp.ecx + b.lx * sp.s * 0.05, sp.ecy + b.ly * sp.s * 0.04); ctx.scale(1, Math.max(0.08, b.open));
        ctx.drawImage(sp.eyes, -sp.s / 2 - sp.ecx, -sp.s / 2 - sp.ecy, sp.s, sp.s);
        ctx.restore();
      }
      // The wordmark: fades up a few pixels, colours from the bots, then crisp white; a light passes across once.
      const wk = (t - T.word) / 0.7;
      if (wk > 0) {
        const tc = txt.getContext('2d'); tc.setTransform(1, 0, 0, 1, 0, 0); tc.clearRect(0, 0, txt.width, txt.height); tc.setTransform(dpr, 0, 0, dpr, 0, 0);
        tc.font = FONT(fs); tc.textBaseline = 'alphabetic';
        const rise = (1 - outCubic(wk)) * fs * 0.14;
        slots.forEach((s, i) => {
          const c = SH.chars[ROW[i % ROW.length]].c; const wt = outCubic((t - T.white - i * 0.025) / 0.55);
          tc.globalAlpha = outCubic((t - T.word - i * 0.018) / 0.6); tc.fillStyle = wt > 0 ? mix(c, wt) : c; tc.fillText(s.ch, s.left, base + rise);
        });
        const sw = (t - T.sweep) / 0.9;
        if (sw > 0 && sw < 1) {
          tc.globalAlpha = 1; tc.globalCompositeOperation = 'source-atop';
          const cx = lerp(slots[0].left - fs * 2, slots[slots.length - 1].left + fs * 2, inOut(sw));
          const g = tc.createLinearGradient(cx - fs * 1.2, 0, cx + fs * 1.2, 0); g.addColorStop(0, 'rgba(190,160,255,0)'); g.addColorStop(0.5, 'rgba(190,160,255,.55)'); g.addColorStop(1, 'rgba(190,160,255,0)');
          tc.fillStyle = g; tc.fillRect(0, base - fs, W, fs * 1.4); tc.globalCompositeOperation = 'source-over';
        }
        ctx.drawImage(txt, 0, 0, W, H);
      }
      // The signature, with room to breathe.
      const sk = (t - T.sign) / 0.8;
      if (sk > 0) {
        const px = Math.max(14, Math.round(fs * 0.2));
        ctx.save(); ctx.globalAlpha = outCubic(sk) * 0.92; ctx.fillStyle = '#fff'; ctx.font = FONT(px, 500); ctx.textAlign = 'center';
        try { ctx.letterSpacing = `${(px * 0.1).toFixed(1)}px`; } catch { /* older engines */ }
        ctx.fillText(SIGN, W / 2, signY + (1 - outCubic(sk)) * 6); ctx.restore();
      }
    }

    // ---------------------------------------------------------------- run
    let t0 = performance.now(); let raf = 0; let ended = false; let frames = 0; let slow = 0; let prev = 0;
    const sound = introSound(reduce);
    const muteBtn = document.createElement('button'); muteBtn.className = 'skipb mute'; muteBtn.type = 'button';
    const setMuteLabel = () => { muteBtn.textContent = muted() ? 'Sound off' : 'Sound on'; muteBtn.title = muted() ? 'Turn the intro sound on' : 'Mute the intro sound'; };
    setMuteLabel(); layer.appendChild(muteBtn);
    muteBtn.onclick = () => { try { localStorage.setItem(MUTE_KEY, muted() ? '0' : '1'); } catch { /* */ } setMuteLabel(); if (sound) sound.set(muted()); };
    function frame(now) {
      frames++;
      if (frames > 4 && frames < 50 && now - prev > 26) slow++;
      prev = now;
      if (slow > 8 && dpr > 1) { dpr = 1; slow = 0; layout(); } else if (slow > 8 && !lite) { lite = true; slow = 0; }
      const t = (now - t0) / 1000;
      draw(t);
      if (t > T.out) return end();
      raf = requestAnimationFrame(frame);
    }
    function end() {
      if (ended) return; ended = true; cancelAnimationFrame(raf);
      removeEventListener('resize', onResize); removeEventListener('keydown', onKey);
      layer.classList.add('leaving');
      window.dispatchEvent(new CustomEvent('nb-intro-done'));
      if (sound) { sound.set(true); sound.close(); }
      setTimeout(() => { layer.hidden = true; muteBtn.remove(); ctx.clearRect(0, 0, cv.width, cv.height); cv.width = cv.height = 1; sprites.clear(); bg = glow = txt = null; done && done(); }, 650);
    }
    const onKey = (e) => { if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') end(); };
    const onResize = () => { layout(); };
    skip.onclick = end; addEventListener('keydown', onKey); addEventListener('resize', onResize);
    document.fonts.load(FONT(fs)).then(() => { layout(); t0 = prev = performance.now(); raf = requestAnimationFrame(frame); }, () => { raf = requestAnimationFrame(frame); });
  }
  window.NBIntro = { play: playIntro };
})();
