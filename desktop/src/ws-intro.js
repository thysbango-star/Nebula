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
  const WORD = 'Nebula Workspace';
  function playIntro(done) {
    const layer = $('intro'); const cv = $('introCanvas'); const skip = $('introSkip');
    layer.hidden = false; layer.classList.remove('leaving');
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    let dpr = Math.min(1.5, devicePixelRatio || 1);
    let W = 0; let H = 0; let fs = 0; let base = 0; let slots = []; const sprites = new Map();
    const ctx = cv.getContext('2d', { alpha: false });
    const FONT = (px, w = 800) => `${w} ${px}px "NB Open Sans", "Open Sans", sans-serif`;

    function layout() {
      W = innerWidth; H = innerHeight;
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cv.style.width = `${W}px`; cv.style.height = `${H}px`;
      fs = Math.round(Math.min(W * 0.082, H * 0.2, 150));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.font = FONT(fs); ctx.textBaseline = 'alphabetic';
      const total = ctx.measureText(WORD).width; const x0 = (W - total) / 2; base = H * 0.5 + fs * 0.32;
      slots = [];
      for (let i = 0; i < WORD.length; i++) {
        if (WORD[i] === ' ') continue;
        const left = x0 + ctx.measureText(WORD.slice(0, i)).width; const m = ctx.measureText(WORD[i]);
        slots.push({ ch: WORD[i], left, w: m.width, cx: left + m.width / 2, top: base - m.actualBoundingBoxAscent, bot: base + m.actualBoundingBoxDescent });
      }
      sprites.clear();
    }
    function sprite(i, px) {
      const key = `${i}:${px}`; if (sprites.has(key)) return sprites.get(key);
      const c = SH.chars[i]; const s = Math.ceil(px * dpr * 1.1);
      const o = document.createElement('canvas'); o.width = o.height = s; const g = o.getContext('2d');
      g.translate(s / 2, s / 2); g.scale(s / 108, s / 108);
      g.fillStyle = c.c; g.fill(new Path2D(c.d)); if (c.cap) { g.fillStyle = c.capc; g.fill(new Path2D(c.cap)); }
      const body = o; const eyes = document.createElement('canvas'); eyes.width = eyes.height = s; const e = eyes.getContext('2d');
      e.translate(s / 2, s / 2); e.scale(s / 108, s / 108); e.fillStyle = '#fff'; e.fill(new Path2D(c.e));
      // Where the eyes sit (so they can blink and look around their own centre): scan the sprite once.
      const px2 = e.getImageData(0, 0, s, s).data; let y0 = s; let y1 = 0; let x0 = s; let x1 = 0;
      for (let y = 0; y < s; y += 2) for (let x = 0; x < s; x += 2) if (px2[(y * s + x) * 4 + 3] > 40) { if (y < y0) y0 = y; if (y > y1) y1 = y; if (x < x0) x0 = x; if (x > x1) x1 = x; }
      const ecx = ((x0 + x1) / 2 - s / 2) / dpr; const ecy = ((y0 + y1) / 2 - s / 2) / dpr;
      const r = { body, eyes, s: s / dpr, ecx, ecy }; sprites.set(key, r); return r;
    }

    layout();
    const actorSize = () => fs * 0.86;
    // Shapes for the 15 letters: every character appears, none twice in a row.
    const pick = [0, 3, 7, 1, 5, 2, 9, 4, 8, 6, 3, 0, 7, 2, 5];
    const N = slots.length;
    const actors = [];
    // Wrong spots first: three pairs squeeze into each other's places, then realise.
    const wrong = [...Array(N).keys()]; [[1, 2], [6, 7], [10, 12]].forEach(([a, b]) => { [wrong[a], wrong[b]] = [wrong[b], wrong[a]]; });
    for (let i = 0; i < N; i++) {
      const sh = pick[i % pick.length]; const c = SH.chars[sh];
      const from = i % 3; // from the bottom, the left or the right
      actors.push({ i, sh, color: c.c, hh: (c.h / 100) * actorSize() / 2, x: from === 1 ? -80 - Math.random() * 200 : from === 2 ? W + 80 + Math.random() * 200 : slots[i].cx + (Math.random() - 0.5) * W * 0.5,
        y: from === 0 ? H + 120 : H * (0.25 + Math.random() * 0.2), vx: 0, vy: from === 0 ? -(H * 1.05 + Math.random() * 200) : -160 - Math.random() * 160,
        blinkAt: 0.6 + Math.random() * 2.5, wide: 0,
        r: 0, vr: (Math.random() - 0.5) * 9, sq: 0, vsq: 0, target: wrong[i], born: i * 0.055, lock: 0, m: 0 });
    }
    let t0 = performance.now(); let last = t0; let slow = 0; let frames = 0; let raf = 0; let ended = false;
    const T = { realise: 1.55, align: 2.45, lock: 3.15, morph: 3.4, white: 4.35, sub: 4.7, out: 5.7 };
    if (reduce) Object.assign(T, { realise: 0, align: 0, lock: 0, morph: 0, white: 0, sub: 0.1, out: 1.4 });

    const lerp = (a, b, k) => a + (b - a) * k;
    const ease = (k) => 1 - Math.pow(1 - Math.min(1, Math.max(0, k)), 3);
    const backOut = (k) => { const x = Math.min(1, Math.max(0, k)) - 1; return 1 + 2.4 * x * x * x + 1.4 * x * x; };
    function mix(hex, k) { const n = parseInt(hex.slice(1), 16); const r = n >> 16; const g = (n >> 8) & 255; const b = n & 255; return `rgb(${Math.round(lerp(r, 255, k))},${Math.round(lerp(g, 255, k))},${Math.round(lerp(b, 255, k))})`; }

    function step(dt, t) {
      const size = actorSize(); const rad = size * 0.42;
      for (const a of actors) {
        if (t < a.born) continue;
        const s = slots[a.target];
        const floor = base - a.hh;
        if (t < T.align) {
          // Clumsy scramble: gravity, bouncy floor, a weak pull towards the (maybe wrong) spot.
          a.vy += 2600 * dt;
          a.vx += ((s.cx - a.x) * 9 - a.vx * 1.6) * dt;
          if (Math.random() < 0.012 && a.y >= floor - 1) { a.vy = -(380 + Math.random() * 420); a.vr += (Math.random() - 0.5) * 8; } // little hops
        } else {
          // Wobbling into alignment: springs to the exact spot, upright.
          const k = t < T.lock ? 90 : 260; const d = t < T.lock ? 11 : 30;
          a.vx += ((s.cx - a.x) * k - a.vx * d) * dt;
          a.vy += ((floor - a.y) * k - a.vy * d) * dt + (t < T.lock ? 900 * dt : 0);
          a.vr += ((-a.r) * 120 - a.vr * 12) * dt;
        }
        a.x += a.vx * dt; a.y += a.vy * dt; a.r += a.vr * dt; a.vr *= Math.pow(0.4, dt);
        if (a.y > floor) { if (a.vy > 220) { a.sq = Math.min(0.32, a.vy / 2600); a.vr += (Math.random() - 0.5) * 3; } a.y = floor; a.vy = -a.vy * 0.42; if (Math.abs(a.vy) < 60) a.vy = 0; }
        // Squash-and-stretch spring.
        a.vsq += (-a.sq * 340 - a.vsq * 16) * dt; a.sq += a.vsq * dt;
        if (t < T.align) a.r = Math.max(-0.7, Math.min(0.7, a.r));
      }
      // Bumping into each other.
      if (t < T.lock) {
        for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) {
          const A = actors[i]; const B = actors[j]; if (t < A.born || t < B.born) continue;
          const dx = B.x - A.x; const dy = B.y - A.y; const d2 = dx * dx + dy * dy; const min = rad * 2 * (t < T.align ? 1 : 0.92);
          if (d2 < min * min && d2 > 0.01) {
            const d = Math.sqrt(d2); const push = (min - d) / 2; const nx = dx / d; const ny = dy / d;
            A.x -= nx * push; A.y -= ny * push; B.x += nx * push; B.y += ny * push;
            const rv = (B.vx - A.vx) * nx + (B.vy - A.vy) * ny;
            if (rv < 0) { const imp = -rv * 0.9; A.vx -= imp * nx; A.vy -= imp * ny; B.vx += imp * nx; B.vy += imp * ny; A.vr -= imp * 0.004; B.vr += imp * 0.004; A.sq = Math.max(A.sq, 0.12); B.sq = Math.max(B.sq, 0.12); }
          }
        }
      }
    }

    function draw(t) {
      ctx.fillStyle = BRAND; ctx.fillRect(0, 0, W, H);
      const size = actorSize();
      for (const a of actors) {
        if (t < a.born) continue;
        const s = slots[a.i]; const sp = sprite(a.sh, Math.round(size));
        const mt = ease((t - T.morph - a.i * 0.035) / 0.6); // 0 → 1 as the shape pours into its letter
        const lockPulse = t > T.lock ? Math.max(0, Math.sin(Math.min(1, (t - T.lock - a.i * 0.03) / 0.22) * Math.PI)) * 0.14 : 0;
        // The letter, revealed from the character's centre outwards in the character's colour, then turning white.
        if (mt > 0) {
          const wt = ease((t - T.white - a.i * 0.02) / 0.45);
          const rr = mt * fs * 1.25;
          const pop = 0.72 + 0.28 * backOut(mt); const cy = base - fs * 0.36;
          ctx.save(); ctx.beginPath(); ctx.arc(a.x, a.y, rr, 0, Math.PI * 2); ctx.clip();
          ctx.translate(s.cx, cy); ctx.scale(pop, pop); ctx.translate(-s.cx, -cy);
          ctx.font = FONT(fs); ctx.fillStyle = wt > 0 ? mix(a.color, wt) : a.color; ctx.fillText(s.ch, s.left, base); ctx.restore();
        }
        if (mt < 1) {
          const k = 1 - mt;
          const sx = (1 + a.sq + lockPulse) * k; const sy = (1 - a.sq + lockPulse * 0.6) * k;
          ctx.save(); ctx.translate(a.x, a.y + a.hh * (1 - k)); ctx.rotate(a.r); ctx.scale(sx, sy);
          ctx.drawImage(sp.body, -sp.s / 2, -sp.s / 2, sp.s, sp.s);
          // Eyes: look where they're going (or at their real spot once they notice), go wide when they realise
          // they're in the wrong place, blink now and then, and squint happily once they've locked in.
          let lx; let ly;
          if (t > T.realise && t < T.realise + 0.55 && a.wrongWas) { lx = Math.sign(slots[a.i].cx - a.x) * 1; ly = -0.4; }
          else { lx = Math.max(-1, Math.min(1, a.vx / 500)); ly = Math.max(-1, Math.min(1, a.vy / 900)); }
          if (t > T.lock) { lx *= 0.2; ly = -0.3; }
          let ex = 1; let ey = 1;
          if (a.wrongWas && t > T.realise && t < T.realise + 0.55) { ex = ey = 1.28; }
          const bt = t - a.blinkAt; if (bt > 0 && bt < 0.13) ey *= 0.12; else if (bt >= 0.13) a.blinkAt = t + 1.6 + Math.random() * 2.4;
          if (t > T.lock + 0.1) ey *= 0.38; // happy
          ctx.globalAlpha = Math.max(0, 1 - mt * 2.2);
          ctx.translate(sp.ecx + lx * sp.s * 0.05, sp.ecy + ly * sp.s * 0.04); ctx.scale(ex, ey);
          ctx.drawImage(sp.eyes, -sp.s / 2 - sp.ecx, -sp.s / 2 - sp.ecy, sp.s, sp.s);
          ctx.restore();
        }
      }
      if (t > T.sub) {
        const k = ease((t - T.sub) / 0.5);
        ctx.globalAlpha = k; ctx.fillStyle = '#fff'; ctx.font = FONT(Math.round(fs * 0.2), 600); ctx.textAlign = 'center';
        ctx.fillText('powered by Rovian Intelligence', W / 2, base + fs * 0.62 + (1 - k) * 12); ctx.textAlign = 'left'; ctx.globalAlpha = 1;
      }
    }

    function frame(now) {
      const dt = Math.min(0.033, (now - last) / 1000); last = now; frames++;
      if (frames > 3 && frames < 40 && now - (frame.prev || now) > 26) slow++;
      frame.prev = now;
      if (slow > 8 && dpr > 1) { dpr = 1; slow = 0; layout(); }
      const t = (now - t0) / 1000;
      // Fixed sub-steps keep the physics stable whatever the frame rate.
      const n = Math.max(1, Math.ceil(dt / 0.008)); for (let i = 0; i < n; i++) step(dt / n, t);
      if (t >= T.realise && !frame.realised) { frame.realised = true; for (const a of actors) if (a.target !== a.i) { a.wrongWas = true; a.target = a.i; a.vy = -650; a.vr += (Math.random() - 0.5) * 10; } }
      draw(t);
      if (t > T.out) return end();
      raf = requestAnimationFrame(frame);
    }
    function end() {
      if (ended) return; ended = true; cancelAnimationFrame(raf);
      removeEventListener('resize', layout); removeEventListener('keydown', onKey);
      layer.classList.add('leaving');
      window.dispatchEvent(new CustomEvent('nb-intro-done'));
      setTimeout(() => { layer.hidden = true; ctx.clearRect(0, 0, cv.width, cv.height); cv.width = cv.height = 1; sprites.clear(); done && done(); }, 650);
    }
    const onKey = (e) => { if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') end(); };
    skip.onclick = end; addEventListener('keydown', onKey); addEventListener('resize', layout);
    document.fonts.load(FONT(fs)).then(() => { layout(); t0 = last = performance.now(); raf = requestAnimationFrame(frame); }, () => { raf = requestAnimationFrame(frame); });
  }
  window.NBIntro = { play: playIntro };
})();
