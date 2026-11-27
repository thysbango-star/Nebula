'use strict';
// Nebula — bots you chat with. Chat-first: a roster of characters, one ongoing chat per bot, a Details / Library /
// Computer panel. Everything shown comes from the main process's real state (ws:state); every control calls a real
// action. The roster, chat and panel are patched in place (keyed), so animations never restart and typing never
// loses focus while bots work.
(() => {
  const api = window.nebula;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const SH = window.NB_SHAPES.chars;
  const COLORS = ['#0A0A0C', '#8B5E3C', '#E8483F', '#F08A24', '#F0B429', '#3ECF8E', '#2FBFA0', '#3B93F0', '#5200F4', '#8B5CF6', '#E152B0', '#A3A3A3'];
  let S = null;
  const R = { view: 'chat', bot: null, panel: false, ptab: 'details', task: null, cr: null, stab: 'general' };
  const drafts = {};
  let attach = []; let replyTo = null;

  // ---------------------------------------------------------------- icons
  const P = {
    plus: '<path d="M12 5v14M5 12h14"/>', send: '<path d="M12 19V5M6 11l6-6 6 6"/>', stop: '<rect x="7" y="7" width="10" height="10" rx="2"/>', mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
    clip: '<path d="m20 11-8.5 8.5a5 5 0 0 1-7-7L13 4a3.3 3.3 0 0 1 4.7 4.7L9.2 17.2a1.7 1.7 0 0 1-2.4-2.4L14.5 7"/>', x: '<path d="M6 6l12 12M18 6 6 18"/>',
    more: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>', panel: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M15 4v16"/>',
    computer: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>', reply: '<path d="M10 8 4 13l6 5"/><path d="M4 13h9a7 7 0 0 1 7 7"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>', retry: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
    smile: '<circle cx="12" cy="12" r="9"/><path d="M8.5 14.5a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01"/>', edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/>',
    link: '<path d="M9 15 15 9"/><path d="M11 6.5 13 4.5a4.2 4.2 0 0 1 6 6l-2 2M13 17.5l-2 2a4.2 4.2 0 0 1-6-6l2-2"/>', clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    check: '<path d="m5 12 5 5 9-10"/>', spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M18 6l-2.5 2.5M8.5 15.5 6 18"/>', user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4 4.5-6 8-6s7 2 8 6"/>',
    file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>', bolt: '<path d="M13 3 5 14h6l-1 7 8-11h-6z"/>', pause: '<path d="M8 5v14M16 5v14"/>', play: '<path d="M7 5v14l12-7z"/>',
  };
  const I = (n, a = '') => `<svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${a}>${P[n] || ''}</svg>`;

  // ---------------------------------------------------------------- avatars (eyes are separate so they can move)
  const partsCache = new Map();
  function parts(i) {
    const k = ((i | 0) % SH.length + SH.length) % SH.length;
    if (!partsCache.has(k)) { const c = SH[k]; partsCache.set(k, { d: c.d, cap: c.cap, capc: c.capc, eyes: c.e.split(/(?=M)/).filter((x) => x.trim()) }); }
    return partsCache.get(k);
  }
  const hash = (s) => { let h = 0; for (const ch of String(s)) h = (h * 31 + ch.charCodeAt(0)) | 0; return Math.abs(h); };
  function avHtml(shape, color, size, state = 'idle', key = '') {
    const p = parts(shape);
    return `<span class="av ${state}" style="--s:${size}px;--d:-${(hash(key) % 90) / 10}s"><svg viewBox="-56 -58 112 114"><g class="bd"><path d="${p.d}" fill="${color}"/>${p.cap ? `<path d="${p.cap}" fill="${p.capc}"/>` : ''}<g class="ey">${p.eyes.map((e) => `<path class="e" d="${e}" fill="#fff"/>`).join('')}</g></g><g class="dots" fill="${color}"><circle cx="38" cy="-50" r="5"/><circle cx="51" cy="-50" r="5"/><circle cx="64" cy="-50" r="5"/></g></svg><i class="pip"></i></span>`;
  }
  function stateOf(b) {
    if (!b) return 'idle';
    const k = b.status?.key;
    if (['working', 'research', 'writing', 'browsing', 'collab'].includes(k)) return 'work';
    if (k === 'thinking') return 'think';
    if (k === 'queued') return 'queue';
    if (k === 'approval' || k === 'user') return 'need';
    if (k === 'attention') return 'fail';
    if (['paused', 'archived', 'draft'].includes(k)) return 'sleep';
    const t = S.tasks.filter((x) => x.botId === b.id && x.status === 'COMPLETED').sort((a, c) => (c.finishedAt || 0) - (a.finishedAt || 0))[0];
    if (t && Date.now() - (t.finishedAt || 0) < 90000 && b.unread) return 'done';
    return 'idle';
  }
  const av = (b, size) => (b ? avHtml(b.shape, b.color, size, stateOf(b), b.id) : '');
  /** Change an avatar's state without restarting its animations unless the state really changed. */
  function patchAv(host, b, size) {
    if (!host || !b) return;
    const el = host.querySelector('.av');
    const sig = `${b.shape}|${b.color}|${size}`;
    if (!el || el.dataset.sig !== sig) { host.innerHTML = av(b, size); host.querySelector('.av').dataset.sig = sig; return; }
    const st = stateOf(b); if (!el.classList.contains(st)) el.className = `av ${st}`;
  }

  // ---------------------------------------------------------------- helpers
  const bot = (id) => S.bots.find((b) => b.id === id);
  const task = (id) => S.tasks.find((t) => t.id === id);
  const conv = (id) => S.conversations.find((c) => c.id === id);
  const active = () => S.bots.filter((b) => !b.archived && !b.draft);
  function ago(ts) {
    if (!ts) return ''; const d = (Date.now() - ts) / 1000;
    if (d < 0) { const f = -d; return f < 3600 ? `in ${Math.max(1, Math.round(f / 60))} min` : f < 86400 ? `in ${Math.round(f / 3600)} h` : `in ${Math.round(f / 86400)} d`; }
    if (d < 60) return 'now'; if (d < 3600) return `${Math.round(d / 60)}m`; if (d < 86400) return `${Math.round(d / 3600)}h`;
    return new Date(ts).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
  }
  const clock = (ts) => new Date(ts).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  function toast(msg, bad = false) { document.querySelectorAll('.toast').forEach((t) => t.remove()); const t = document.createElement('div'); t.className = `toast ${bad ? 'bad' : ''}`; t.textContent = msg; document.body.appendChild(t); setTimeout(() => t.remove(), bad ? 5200 : 2400); }
  async function call(ch, arg, ok) {
    const r = await api.invoke(ch, arg).catch((e) => ({ ok: false, error: e.message }));
    if (!r || r.ok === false) { toast(r?.error || 'That didn\'t work.', true); return undefined; }
    if (ok) toast(ok); return r.value === undefined ? true : r.value;
  }
  const plain = (s) => String(s || '').replace(/```[\s\S]*?```/g, ' ').replace(/\|?\s*:?-{2,}:?\s*/g, ' ').replace(/[|#*`>_]+/g, ' ').replace(/\s+/g, ' ').trim();
  const fileExt = (f) => (f.ext || (f.fileName || '').split('.').pop() || '').slice(0, 4);
  const progress = (t) => (t.status === 'COMPLETED' ? 100 : t.plan?.length ? Math.min(100, Math.round(t.steps.filter((s) => s.status === 'DONE').length / t.plan.length * 100)) : null);
  function statusPill(t) {
    if (t.status === 'REQUIRES_INPUT') return `<span class="pill need">${t.input?.kind === 'approval' ? 'Needs your OK' : 'Has a question'}</span>`;
    if (t.status === 'IN_PROGRESS') return '<span class="pill run">Working</span>';
    if (t.status === 'COMPLETED') return '<span class="pill ok">Done</span>';
    if (t.status === 'FAILED') return '<span class="pill bad">Couldn\'t finish</span>';
    if (t.status === 'PAUSED') return `<span class="pill">${t.interrupted ? 'Interrupted' : 'Paused'}</span>`;
    if (t.status === 'QUEUED') return '<span class="pill">Queued</span>';
    return '<span class="pill">Stopped</span>';
  }
  /** What a bot is up to, in a few words (the roster's second line). */
  function lineOf(b) {
    const st = stateOf(b);
    const need = S.tasks.find((t) => t.botId === b.id && t.status === 'REQUIRES_INPUT');
    if (need) return { cls: 'need', text: need.input?.kind === 'approval' ? `Needs your OK · ${need.input.card?.title || ''}` : `Asked: ${need.input?.question || ''}` };
    if (st === 'work' || st === 'think') { const a = (b.mainCid && S.live[b.mainCid]) || S.tasks.find((t) => t.botId === b.id && t.activity)?.activity || (b.current ? `Working on ${b.current.title}` : 'Thinking…'); return { cls: 'act', text: a }; }
    if (st === 'fail') { const t = S.tasks.find((x) => x.botId === b.id && x.status === 'FAILED'); return { cls: 'bad', text: `Couldn't finish${t ? `: ${t.title}` : ''}` }; }
    if (st === 'sleep') return { cls: '', text: 'Paused' };
    if (b.last) return { cls: '', text: `${b.last.role === 'user' ? 'You: ' : ''}${plain(b.last.text)}` };
    return { cls: '', text: b.role || 'New bot' };
  }

  // ---------------------------------------------------------------- markdown (escaped first, cached)
  const mdCache = new Map();
  function md(text) {
    if (mdCache.has(text)) return mdCache.get(text);
    const blocks = []; let s = String(text || '');
    s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, (_m, _l, code) => { blocks.push(`<pre><code>${esc(code.replace(/\n$/, ''))}</code></pre>`); return `\u0000${blocks.length - 1}\u0000`; });
    s = s.replace(/\[Attached file: ([^\]]+)\]\n\u0000(\d+)\u0000/g, (_m, name, i) => { blocks[i] = `<details><summary>📎 ${esc(name)}</summary>${blocks[i]}</details>`; return `\u0000${i}\u0000`; });
    const inline = (x) => esc(x).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a data-act="url" data-url="$2">$1</a>').replace(/(^|\s)(https?:\/\/[^\s<]+)/g, '$1<a data-act="url" data-url="$2">$2</a>');
    const out = []; const lines = s.split('\n'); let i = 0;
    while (i < lines.length) {
      const l = lines[i];
      if (/^\u0000\d+\u0000$/.test(l.trim())) { out.push(blocks[+l.trim().slice(1, -1)]); i++; continue; }
      if (/^#{1,4} /.test(l)) { const n = Math.min(4, l.match(/^#+/)[0].length + 1); out.push(`<h${n}>${inline(l.replace(/^#+ /, ''))}</h${n}>`); i++; continue; }
      if (/^\s*\|.*\|\s*$/.test(l) && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1] || '')) {
        const cells = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
        let h = `<table><tr>${cells(l).map((c) => `<th>${inline(c)}</th>`).join('')}</tr>`; i += 2;
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { h += `<tr>${cells(lines[i]).map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`; i++; }
        out.push(`${h}</table>`); continue;
      }
      if (/^\s*([-*•]|\d+[.)])\s+/.test(l)) { const ord = /^\s*\d/.test(l); let h = ''; while (i < lines.length && /^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) { h += `<li>${inline(lines[i].replace(/^\s*([-*•]|\d+[.)])\s+/, ''))}</li>`; i++; } out.push(ord ? `<ol>${h}</ol>` : `<ul>${h}</ul>`); continue; }
      if (/^>\s?/.test(l)) { let q = ''; while (i < lines.length && /^>\s?/.test(lines[i])) { q += `${lines[i].replace(/^>\s?/, '')} `; i++; } out.push(`<div class="quote">${inline(q)}</div>`); continue; }
      if (!l.trim()) { i++; continue; }
      let p = l; i++;
      while (i < lines.length && lines[i].trim() && !/^(#{1,4} |\s*([-*•]|\d+[.)])\s+|\s*\||>|\u0000)/.test(lines[i])) { p += `\n${lines[i]}`; i++; }
      out.push(`<p>${inline(p).replace(/\n/g, '<br>')}</p>`);
    }
    const html = out.join('').replace(/\u0000(\d+)\u0000/g, (_m, n) => blocks[n]);
    if (mdCache.size > 600) mdCache.clear();
    mdCache.set(text, html); return html;
  }

  // ---------------------------------------------------------------- overlays
  const closeLayer = () => { $('layer').innerHTML = ''; R.task = null; R.settingsOpen = false; R.apprOpen = false; };
  function modal(title, body, foot = '', cls = '') {
    const tabs = body.startsWith('<div class="stabs"');
    $('layer').innerHTML = `<div class="scrim" data-act="scrim"><div class="modal ${cls}"><div class="mhead"><b>${title}</b><button class="ib" data-act="close">${I('x')}</button></div>${tabs ? body : `<div class="mbody">${body}</div>`}${foot ? `<div class="mfoot">${foot}</div>` : ''}</div></div>`;
    R.settingsOpen = tabs;
    const f = $('layer').querySelector('.mbody input:not([type=checkbox]):not([type=color]), .mbody textarea'); if (f && !tabs) setTimeout(() => f.focus(), 40);
  }
  function menu(ev, items) {
    document.querySelectorAll('.menu').forEach((m) => m.remove());
    const r = ev.currentTarget.getBoundingClientRect();
    const el = document.createElement('div'); el.className = 'menu';
    el.innerHTML = items.map((it) => (it === '-' ? '<hr>' : `<button class="${it.danger ? 'danger' : ''}">${esc(it.label)}</button>`)).join('');
    document.body.appendChild(el);
    el.style.left = `${Math.min(innerWidth - el.offsetWidth - 10, Math.max(10, r.right - el.offsetWidth))}px`; el.style.top = `${Math.min(innerHeight - el.offsetHeight - 10, r.bottom + 6)}px`;
    const btns = [...el.querySelectorAll('button')]; let k = 0;
    items.forEach((it) => { if (it !== '-') { const b = btns[k++]; b.onclick = (e) => { e.stopPropagation(); el.remove(); it.fn(); }; } });
    setTimeout(() => document.addEventListener('mousedown', function off(e) { if (!el.contains(e.target)) { el.remove(); document.removeEventListener('mousedown', off); } }), 0);
  }
  function confirmBox(title, text, yes, fn, danger = true) {
    modal(title, `<p style="margin:0;color:var(--dim)">${text}</p>`, `<button class="btn" data-act="close">Cancel</button><button class="btn ${danger ? 'danger' : 'pri'}" id="cfYes">${esc(yes)}</button>`);
    $('cfYes').onclick = () => { closeLayer(); fn(); };
  }

  // ---------------------------------------------------------------- roster (patched per row)
  function renderRoster() {
    const host = $('roster');
    const list = active();
    const pinned = new Set(S.pins.filter((p) => p.kind === 'bot').map((p) => p.id));
    const order = [...list].sort((a, b) => ((b.primary ? 1 : 0) - (a.primary ? 1 : 0)) || ((pinned.has(b.id) ? 1 : 0) - (pinned.has(a.id) ? 1 : 0)) || ((b.last?.at || b.updatedAt || 0) - (a.last?.at || a.updatedAt || 0)));
    const want = order.map((b) => b.id).join(',');
    if (host.dataset.order !== want) {
      const keep = new Map([...host.querySelectorAll('.rrow')].map((el) => [el.dataset.bot, el]));
      host.innerHTML = '';
      if (!order.length) host.innerHTML = '<div class="empty" style="padding:14px">No bots yet. Press + to make one.</div>';
      for (const b of order) host.appendChild(keep.get(b.id) || rowEl(b));
      host.dataset.order = want;
    }
    for (const b of order) patchRow(host.querySelector(`.rrow[data-bot="${b.id}"]`), b);
  }
  function rowEl(b) {
    const el = document.createElement('button'); el.className = 'rrow'; el.dataset.bot = b.id; el.dataset.act = 'bot'; el.dataset.id = b.id;
    el.innerHTML = '<span class="avh"></span><span class="nm"></span><span class="tm"></span><span class="ln"></span><span class="badge" hidden></span>';
    return el;
  }
  function patchRow(el, b) {
    if (!el) return;
    el.classList.toggle('on', R.view === 'chat' && R.bot === b.id);
    patchAv(el.querySelector('.avh'), b, 40);
    const nm = `${esc(b.name)}${b.primary ? ' <span class="star" title="Your primary bot">★</span>' : ''}`;
    const n = el.querySelector('.nm'); if (n.innerHTML !== nm) n.innerHTML = nm;
    const ln = lineOf(b); const l = el.querySelector('.ln'); if (l.textContent !== ln.text) l.textContent = ln.text; l.className = `ln ${ln.cls}`;
    el.title = `${b.name} — ${b.status?.label || ''}${b.status?.detail ? `: ${b.status.detail}` : ''}`;
    const tm = el.querySelector('.tm'); const t = ago(b.last?.at); if (tm.textContent !== t) tm.textContent = t;
    const bd = el.querySelector('.badge'); const u = R.bot === b.id && R.view === 'chat' ? 0 : b.unread; bd.hidden = !u; if (u) bd.textContent = u > 9 ? '9+' : u;
  }
  function renderFoot() {
    const appr = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT').length;
    const html = `${appr ? `<button class="srow warnr" data-act="approvals">${I('bolt')}Waiting for you<span class="ct">${appr}</span></button>` : ''}
      <button class="srow" data-act="settings" data-tab="apps">${I('link')}Connect apps<span class="ct">${(S.mcp || []).filter((s) => s.status === 'connected').length || ''}</span></button>
      <button class="srow" data-act="settings" data-tab="routines">${I('clock')}Routines<span class="ct">${S.routines.filter((r) => r.active).length || ''}</span></button>
      <button class="srow" data-act="settings" data-tab="general"><span class="who">${esc((S.profile.name || 'Y').slice(0, 1).toUpperCase())}</span>${esc(S.profile.name || 'You')}<span class="ct">${I('gear')}</span></button>`;
    if ($('sidefoot').dataset.h !== html) { $('sidefoot').innerHTML = html; $('sidefoot').dataset.h = html; }
  }

  // ---------------------------------------------------------------- main area
  function renderMain() {
    if (R.view === 'create' || !active().length) return renderCreate();
    let b = bot(R.bot);
    if (!b || b.archived || b.draft) { b = S.bots.find((x) => x.primary && !x.archived && !x.draft) || active()[0]; R.bot = b.id; }
    if ($('main').dataset.mount !== `chat:${b.id}`) mountChat(b);
    patchChat(b);
  }

  function mountChat(b) {
    const m = $('main'); m.dataset.mount = `chat:${b.id}`;
    m.innerHTML = `<header class="chead drag"><button class="who nodrag" data-act="panel-tab" data-t="details"><span class="hav"></span><span class="wt"><b></b><span class="sub"></span></span></button><span class="sp"></span>
        <button class="ib comp-ic nodrag" data-act="panel-tab" data-t="computer" title="What it's doing on this PC">${I('computer')}</button>
        <button class="ib nodrag" data-act="toggle-panel" title="Details (Ctrl+Shift+,)">${I('panel')}</button>
        <button class="ib nodrag" data-act="bot-menu" data-id="${b.id}">${I('more')}</button></header>
      <div class="tx" id="tx"><div class="col" id="col"></div></div>
      <div class="composer"><div class="starters" id="starters"></div><div class="in"><div class="chips2" id="chips2"></div><textarea id="composer" rows="1" placeholder="Message ${esc(b.name)}"></textarea>
        <div class="bar2"><button class="ib" data-act="attach" title="Attach text files">${I('clip')}</button><button class="ib" data-act="voice" title="Talk to Nebula">${I('mic')}</button><span class="helpl" id="cmpHint"></span><span class="sp"></span><button class="send" id="sendBtn" data-act="send" title="Send (Enter)">${I('send')}</button></div></div></div>`;
    const c = $('composer'); c.value = drafts[b.id] || ''; grow(c);
    setTimeout(() => c.focus(), 30);
    requestAnimationFrame(() => { if ($('tx')) $('tx').scrollTop = 1e9; });
    if (b.unread) call('ws:read', { botId: b.id });
  }

  function grow(el) { el.style.height = 'auto'; el.style.height = `${Math.min(220, el.scrollHeight)}px`; }

  /** Everything in the bot's ongoing chat, in time order: messages, events, task cards, questions, the working face. */
  function chatItems(b) {
    const c = conv(b.mainCid);
    const msgs = c ? c.messages : [];
    const items = [];
    let prevRole = '';
    const asking = new Set(S.tasks.filter((t) => t.botId === b.id && t.status === 'REQUIRES_INPUT' && t.input?.question).map((t) => t.input.question.trim()));
    for (const m of msgs) {
      if (m.role === 'assistant' && asking.has((m.text || '').trim())) continue; // the question card below shows it
      const grp = (m.role === 'assistant' && prevRole === 'assistant');
      items.push({ key: `m${m.id}`, at: m.at, sig: `${m.text.length}|${hash(m.text)}|${JSON.stringify(m.reactions || {})}|${(m.fileIds || []).length}|${grp}|${R.exp?.[m.id] ? 1 : 0}`, html: () => msgHtml(m, b, grp) });
      prevRole = m.role === 'event' ? prevRole : m.role;
    }
    for (const t of S.tasks.filter((x) => x.botId === b.id && !x.parentId)) {
      const own = t.conversationId === b.mainCid;
      const real = t.steps.filter((s) => s.status !== 'WAITING' && !/^(Waiting for you|Wrote the answer|Saved its job|Created routine|Updated routine|Asked you)/.test(s.label)).length;
      const trivial = own && !t.plan?.length && real === 0 && !(t.fileIds || []).length && t.status !== 'FAILED';
      if (trivial) continue; // a plain answer needs no card
      items.push({ key: `t${t.id}`, at: (t.createdAt || 0) + 1, sig: `${t.status}|${t.steps.length}|${t.plan?.length}|${t.activity}|${hash(t.summary || '')}`, html: () => taskCard(t) });
    }
    items.sort((a, z) => a.at - z.at);
    for (const t of S.tasks.filter((x) => x.botId === b.id && x.status === 'REQUIRES_INPUT' && x.input)) items.push({ key: `q${t.id}`, at: 9e15, sig: hash(JSON.stringify(t.input)), html: () => inputCard(t) });
    const working = !!((b.mainCid && S.live[b.mainCid]) || S.tasks.some((t) => t.botId === b.id && t.conversationId === b.mainCid && t.status === 'IN_PROGRESS'));
    const live = (b.mainCid && S.live[b.mainCid]) || S.tasks.find((t) => t.botId === b.id && t.conversationId === b.mainCid && t.status === 'IN_PROGRESS')?.activity || '';
    if (working) items.push({ key: 'working', at: 9e15 + 1, sig: live, html: () => `<div class="working"><span class="avh"></span><span class="txt">${esc(live || `${b.name} is thinking…`)}</span></div>` });
    if (!msgs.length) items.unshift({ key: 'hero', at: 0, sig: `${b.shape}${b.color}${b.name}`, html: () => `<div class="hero"><span class="avh big"></span><h2>${esc(b.name)}</h2><p>${esc(b.description || b.role || '')}</p></div>` });
    return { items, working };
  }

  function patchChat(b) {
    const col = $('col'); const tx = $('tx'); if (!col) return;
    const nearBottom = tx.scrollHeight - tx.scrollTop - tx.clientHeight < 90;
    patchAv(document.querySelector('.chead .hav'), b, 30);
    const hb = document.querySelector('.chead .wt b'); if (hb.textContent !== b.name) hb.textContent = b.name;
    const sub = document.querySelector('.chead .sub'); const subt = b.status && b.status.key !== 'idle' ? b.status.label : (b.role && b.role !== b.name ? b.role : b.setup ? 'Learning its job' : 'Ready');
    if (sub.textContent !== subt) sub.textContent = subt;
    document.querySelector('.comp-ic').classList.toggle('live', ['work', 'think'].includes(stateOf(b)));
    const { items, working } = chatItems(b);
    const have = new Map([...col.children].map((el) => [el.dataset.key, el]));
    let prev = null;
    for (const it of items) {
      let el = have.get(it.key);
      if (el && el.dataset.sig !== String(it.sig)) {
        if (it.key === 'working') { el.querySelector('.txt').textContent = it.sig || `${b.name} is thinking…`; el.dataset.sig = it.sig; }
        else { const n = nodeOf(it); el.replaceWith(n); el = n; }
      }
      if (!el) el = nodeOf(it);
      have.delete(it.key);
      const want = prev ? prev.nextSibling : col.firstChild;
      if (want !== el) col.insertBefore(el, want);
      if (it.key === 'working') patchAv(el.querySelector('.avh'), b, 28);
      if (it.key === 'hero') patchAv(el.querySelector('.avh'), b, 96);
      prev = el;
    }
    for (const el of have.values()) el.remove();
    if (nearBottom) tx.scrollTop = tx.scrollHeight;
    const send = $('sendBtn'); const mode = working ? 'stop-chat' : 'send';
    if (send.dataset.act !== mode) { send.classList.toggle('stop', working); send.innerHTML = working ? I('stop') : I('send'); send.dataset.act = mode; send.title = working ? 'Stop' : 'Send (Enter)'; }
    const hint = working ? 'Keep typing — a new message redirects it.' : ''; if ($('cmpHint').textContent !== hint) $('cmpHint').textContent = hint;
    const st = $('starters'); const showStarters = !conv(b.mainCid)?.messages.some((m) => m.role === 'user');
    const sh = showStarters ? starters(b).map((s) => `<button data-act="starter" data-t="${esc(s)}">${esc(s)}</button>`).join('') : '';
    if (st.dataset.h !== sh) { st.innerHTML = sh; st.dataset.h = sh; }
    const ch = `${replyTo ? `<span>${I('reply')}Replying: ${esc(replyTo.text.slice(0, 80))}<button data-act="reply-x">✕</button></span>` : ''}${attach.map((a, i) => `<span>📎 ${esc(a.name)}<button data-act="att-x" data-i="${i}">✕</button></span>`).join('')}`;
    if ($('chips2').dataset.h !== ch) { $('chips2').innerHTML = ch; $('chips2').dataset.h = ch; }
  }
  function nodeOf(it) { const w = document.createElement('div'); w.innerHTML = it.html(); const el = w.firstElementChild || document.createElement('div'); el.dataset.key = it.key; el.dataset.sig = it.sig; return el; }

  function starters(b) {
    const t = S.catalogue.templates.find((x) => x.key === b.template);
    if (b.setup) return (t?.setupStarters) || ['Sort my inbox and draft replies', 'Research topics and write me reports', 'Plan my week and remind me', 'Something else…'];
    if (t && t.samples) return t.samples.slice(0, 3);
    return ['What can you do?', 'What do you remember about me?', 'Do this every weekday at 8'];
  }

  function msgHtml(m, b, grp) {
    if (m.role === 'event') return `<div class="ev"><span class="dot">${I(m.kind === 'routine' ? 'clock' : m.kind === 'job' ? 'check' : 'spark')}</span><span>${esc(m.text)}</span><span class="tm">${clock(m.at)}</span></div>`;
    const me = m.role === 'user';
    let text = m.text || ''; let quote = '';
    if (m.replyTo) { const ls = text.split('\n'); let i = 0; while (i < ls.length && ls[i].startsWith('>')) i++; quote = ls.slice(0, i).join(' ').replace(/(^|\s)>\s?/g, ' ').trim(); text = ls.slice(i).join('\n').trim(); }
    const long = text.length > 2200 && !R.exp?.[m.id];
    const files = (m.fileIds || []).map((id) => S.files.find((f) => f.id === id)).filter(Boolean);
    const tools = `<div class="mtools"><button data-act="react-pick" data-mid="${m.id}" title="React">${I('smile')}</button><button data-act="reply" data-mid="${m.id}" title="Reply">${I('reply')}</button><button data-act="copy" data-mid="${m.id}" title="Copy">${I('copy')}</button>${me ? `<button data-act="edit-msg" data-mid="${m.id}" title="Edit and resend">${I('edit')}</button>` : `<button data-act="retry-msg" data-mid="${m.id}" title="Try again">${I('retry')}</button>`}</div>`;
    const body = `${quote ? `<div class="quote" data-act="jump" data-mid="${m.replyTo}">${esc(quote.slice(0, 160))}</div>` : ''}<div class="md ${me ? 'bub' : ''} ${long ? 'long' : ''} ${m.role === 'error' ? 'err' : ''}">${md(text)}</div>
      ${text.length > 2200 ? `<button class="more" data-act="expand" data-mid="${m.id}">${R.exp?.[m.id] ? 'Show less' : 'Show more'}</button>` : ''}
      ${files.length ? `<div class="files">${files.map((f) => `<span class="fcard" data-act="file" data-id="${f.id}"><i>${esc(fileExt(f))}</i>${esc(f.fileName)}</span>`).join('')}</div>` : ''}
      ${(m.sources || []).length ? `<div class="srcs">${m.sources.slice(0, 8).map((s) => `<a data-act="url" data-url="${esc(s.url)}" title="${esc(s.url)}">${esc(s.title || s.url)}</a>`).join('')}</div>` : ''}
      ${m.reactions && Object.keys(m.reactions).length ? `<div class="reacts">${Object.keys(m.reactions).map((e) => `<button data-act="react" data-mid="${m.id}" data-e="${e}">${e}</button>`).join('')}</div>` : ''}`;
    if (me) return `<div class="m me" id="m-${m.id}"><div class="body">${body}</div>${tools}</div>`;
    return `<div class="m ${grp ? 'grp' : ''}" id="m-${m.id}">${grp ? '<span class="avsp"></span>' : avHtml(b.shape, b.color, 32, 'idle', b.id)}<div class="body">${grp ? '' : `<div class="meta"><b>${esc(b.name)}</b>${clock(m.at)}</div>`}${body}</div>${tools}</div>`;
  }

  function taskCard(t) {
    const p = progress(t); const steps = t.steps.slice(-4);
    return `<div class="card tcard" data-act="task" data-id="${t.id}"><div class="hd">${I(t.source === 'routine' ? 'clock' : 'bolt')}<b>${esc(t.title)}</b>${statusPill(t)}</div>
      ${steps.length ? `<ul class="st">${t.steps.length > 4 ? `<li class="DONE"><i>⋯</i><span>${t.steps.length - 4} earlier step${t.steps.length - 4 === 1 ? '' : 's'}</span></li>` : ''}${steps.map((s) => `<li class="${s.status}"><i>${s.status === 'DONE' ? '✓' : s.status === 'WAITING' ? '…' : '!'}</i><span>${esc(s.label)}</span></li>`).join('')}</ul>` : ''}
      ${t.status === 'IN_PROGRESS' && t.activity ? `<div class="helpl act">${esc(t.activity)}</div>` : ''}
      ${t.status === 'FAILED' && t.summary ? `<div class="helpl bad">${esc(plain(t.summary).slice(0, 200))}</div>` : ''}
      ${p != null && t.status === 'IN_PROGRESS' ? `<div class="prog"><i style="width:${p}%"></i></div>` : ''}</div>`;
  }

  function inputCard(t) {
    const b = bot(t.botId); const inp = t.input;
    if (inp.kind === 'approval') {
      const k = inp.card;
      return `<div class="card ask"><div class="hd">${b ? avHtml(b.shape, b.color, 28, 'need', b.id) : ''}<b>${esc(b?.name || 'Your bot')} ${k.title.includes(': ') && k.app && k.title.startsWith(k.app) ? `wants to use ${esc(k.app)} → ${esc(k.title.slice(k.app.length + 2))}` : `wants to ${esc(k.title.charAt(0).toLowerCase() + k.title.slice(1))}`}</b><span class="risk ${k.risk}">${k.risk} risk</span></div>
        ${!k.reversible ? '<div class="warnl">This can\'t be undone.</div>' : ''}
        <dl>${k.why ? `<dt>Why</dt><dd>${esc(k.why)}</dd>` : ''}<dt>App</dt><dd>${esc(k.app)}</dd>${k.destination ? `<dt>Where</dt><dd>${esc(k.destination)}</dd>` : ''}${k.content ? `<dt>Details</dt><dd><pre>${esc(k.content)}</pre></dd>` : ''}${k.consequence ? `<dt>Then</dt><dd>${esc(k.consequence)}</dd>` : ''}<dt>Shared</dt><dd>${esc(k.shares)}</dd></dl>
        <div class="row2"><button class="btn pri" data-act="answer" data-id="${t.id}" data-c="Approve">Allow once</button>${k.always ? `<button class="btn" data-act="answer" data-id="${t.id}" data-c="Always allow" title="Only this action, only for ${esc(b?.name || 'this bot')}">Always allow</button>` : ''}<button class="btn ghost" data-act="edit-approval" data-id="${t.id}">Edit…</button><span class="sp"></span><button class="btn danger" data-act="answer" data-id="${t.id}" data-c="Deny">Deny</button></div></div>`;
    }
    return `<div class="card ask q"><div class="hd">${b ? avHtml(b.shape, b.color, 28, 'need', b.id) : ''}<b>${esc(inp.question)}</b></div>
      ${inp.options?.length ? `<div class="opts">${inp.options.map((o, i) => `<button class="optb" data-act="answer" data-id="${t.id}" data-c="${esc(o)}"><kbd>${String.fromCharCode(65 + i)}</kbd>${esc(o)}</button>`).join('')}</div>` : ''}
      <div class="row2"><input type="text" id="ans-${t.id}" placeholder="${inp.options?.length ? 'Or type your own answer…' : 'Type your answer…'}"><button class="btn pri" data-act="answer-text" data-id="${t.id}">Send</button></div></div>`;
  }

  // ---------------------------------------------------------------- create (3 seconds)
  function renderCreate() {
    const m = $('main');
    if (!R.cr) R.cr = { shape: Math.floor(Math.random() * SH.length), color: COLORS[2 + Math.floor(Math.random() * 9)], name: '' };
    const cr = R.cr;
    if (m.dataset.mount !== 'create') {
      m.dataset.mount = 'create';
      m.innerHTML = `<header class="chead drag"><b style="font-size:15px">${active().length ? 'New bot' : 'Create your first bot'}</b><span class="sp"></span>${active().length ? `<button class="ib nodrag" data-act="cancel-create">${I('x')}</button>` : ''}</header>
        <div class="create"><span class="big" id="crAv"></span><div class="colors" id="crColors"></div><div class="shapes" id="crShapes"></div>
          <input type="text" class="nm" id="crName" maxlength="40" placeholder="Name your bot" autocomplete="off" spellcheck="false">
          <button class="btn brand go2" id="crGo" data-act="create-go" disabled>Get started</button>
          <div class="helpl">You'll tell it its job in the chat — no forms.</div>
          <h4>Or start from a ready-made bot</h4><div class="tpls">${S.catalogue.templates.map((t) => `<button data-act="create-tpl" data-k="${t.key}">${avHtml(t.shape, t.color, 40, 'idle', t.key)}<span><b>${esc(t.name)}</b><small>${esc(t.desc)}</small></span></button>`).join('')}</div></div>`;
      $('crName').value = cr.name;
      setTimeout(() => $('crName')?.focus(), 40);
    }
    $('crAv').innerHTML = avHtml(cr.shape, cr.color, 128, cr.name ? 'need' : 'idle', 'create');
    $('crColors').innerHTML = COLORS.map((c) => `<button class="${c === cr.color ? 'on' : ''}" style="background:${c}" data-act="cr-color" data-c="${c}" title="${c}"></button>`).join('');
    $('crShapes').innerHTML = SH.map((_s, i) => `<button class="${i === cr.shape ? 'on' : ''}" data-act="cr-shape" data-i="${i}">${avHtml(i, cr.color, 32, 'still', `s${i}`)}</button>`).join('');
    $('crGo').disabled = !cr.name.trim();
  }

  // ---------------------------------------------------------------- details panel
  function renderPanel() {
    const p = $('panel'); const b = bot(R.bot);
    const show = R.panel && b && R.view === 'chat' && active().length;
    p.hidden = !show; $('app').classList.toggle('panel', !!show);
    if (!show) return;
    if (p.contains(document.activeElement) && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) return; // don't disturb typing
    const html = `<div class="ptabs">${[['details', 'Details'], ['library', 'Library'], ['computer', 'Computer']].map(([k, l]) => `<button class="${R.ptab === k ? 'on' : ''}" data-act="panel-tab" data-t="${k}">${l}</button>`).join('')}<span class="sp"></span><button class="ib" data-act="toggle-panel">${I('x')}</button></div><div class="pbody" id="pbody">${PANEL[R.ptab](b)}</div>`;
    const sig = `${b.id}|${hash(html)}`;
    if (p.dataset.sig !== sig) { const sc = $('pbody') ? $('pbody').scrollTop : 0; p.innerHTML = html; p.dataset.sig = sig; $('pbody').scrollTop = sc; }
    p.querySelectorAll('.avh[data-size]').forEach((h) => patchAv(h, b, +h.dataset.size));
  }
  const sw = (on, act, data = '') => `<button class="sw ${on ? 'on' : ''}" data-act="${act}" ${data}></button>`;
  const PANEL = {};
  PANEL.details = (b) => {
    const I2 = b.ins || {};
    const job = [['Job', I2.resp], ['Always', I2.do], ['Never', I2.never], ['Sources', I2.approach], ['Check in', I2.escalation], ['Output', I2.output], ['Style', I2.style]].filter(([, v]) => v && String(v).trim());
    const routines = S.routines.filter((r) => r.botId === b.id);
    const recent = S.tasks.filter((t) => t.botId === b.id && !t.parentId).sort((a, z) => z.updatedAt - a.updatedAt).slice(0, 6);
    const pinned = S.pins.some((x) => x.kind === 'bot' && x.id === b.id);
    return `<div class="prof"><span class="avh" data-size="84" data-act="edit-avatar" title="Change look"></span><input class="nmi" id="nmIn" value="${esc(b.name)}" maxlength="40"><div class="role">${esc(b.role || (b.setup ? 'Learning its job' : ''))}</div>
        <div class="row2 c">${b.paused ? `<button class="btn" data-act="bot-act" data-a="resume" data-id="${b.id}">${I('play')}Resume</button>` : `<button class="btn" data-act="bot-act" data-a="pause" data-id="${b.id}">${I('pause')}Pause</button>`}<button class="btn" data-act="bot-act" data-a="primary" data-id="${b.id}" ${b.primary ? 'disabled' : ''}>${b.primary ? '★ Primary' : 'Make primary'}</button><button class="btn ghost" data-act="pin-bot" data-id="${b.id}">${pinned ? 'Unpin' : 'Pin'}</button></div></div>
      <div class="sec"><h5>Its job<span class="sp"></span><a data-act="edit-job">Edit</a></h5>${job.length ? `<div class="jobtxt">${job.map(([k, v]) => `<span class="k">${k}</span>${esc(v)}`).join('')}</div>` : `<div class="empty">Not set yet — tell ${esc(b.name)} in the chat what you want it to do.</div>`}</div>
      <div class="sec"><h5>Routines</h5>${routines.length ? routines.map((r) => `<div class="kvr"><div class="t"><b>${esc(r.name)}</b><small>${esc(r.when)}${r.active && r.nextRun ? ` · next ${ago(r.nextRun)}` : ''}</small></div><button class="ib" data-act="routine-act" data-a="run" data-id="${r.id}" title="Run now">${I('play')}</button>${sw(r.active, 'routine-act', `data-a="toggle" data-id="${r.id}" title="On/off"`)}<button class="ib" data-act="routine-del" data-id="${r.id}" title="Delete">${I('x')}</button></div>`).join('') : '<div class="empty">Ask in the chat — e.g. “do this every weekday at 8”.</div>'}</div>
      <div class="sec"><h5>Recent work</h5>${recent.length ? recent.map((t) => `<div class="kvr click" data-act="task" data-id="${t.id}"><div class="t"><b>${esc(t.title)}</b><small>${ago(t.updatedAt)} · ${esc(t.source || 'chat')}</small></div>${statusPill(t)}</div>`).join('') : '<div class="empty">Nothing yet.</div>'}</div>
      <div class="sec"><h5>Tools it can use</h5>${S.catalogue.capabilities.map((c) => `<div class="kvr"><div class="t"><b>${esc(c.label)}</b><small>${esc(c.desc)}</small></div>${sw(b.caps.includes(c.id), 'cap', `data-c="${c.id}"`)}</div>`).join('')}
        ${(S.mcp || []).map((s) => `<div class="kvr"><div class="t"><b>${esc(s.name)} <span class="tag">app</span></b><small>${s.status === 'connected' ? `${(s.tools || []).length} actions` : esc(s.status || 'not connected')}</small></div>${sw(!!(b.mcp || {})[s.id], 'mcp-bot', `data-s="${s.id}"`)}</div>`).join('')}
        <div class="helpl"><a data-act="settings" data-tab="apps">Connect more apps</a> · Nebula enforces these — the bot can't use anything switched off.</div></div>
      <div class="sec"><h5>When it asks first</h5><div class="seg wrap">${Object.entries(S.catalogue.autonomy).map(([k, a]) => `<button class="${b.autonomy === k ? 'on' : ''}" data-act="autonomy" data-v="${k}" title="${esc(a.desc)}">${esc(a.label.replace(' agent', ''))}</button>`).join('')}</div><div class="helpl">${esc(S.catalogue.autonomy[b.autonomy]?.desc || '')} Irreversible actions always ask.</div></div>
      <div class="sec"><h5>Memory<span class="sp"></span><a data-act="mem-add" data-id="${b.id}">Add</a></h5>${b.memories.length ? b.memories.slice(0, 30).map((mm) => `<div class="kvr"><div class="t wrap"><b>${esc(mm.text)}</b></div><button class="ib" data-act="mem-remove" data-id="${b.id}" data-m="${mm.id}" title="Forget">${I('x')}</button></div>`).join('') : '<div class="empty">Nothing yet. It saves lasting facts as it works — never passwords.</div>'}</div>
      <div class="sec"><h5>Knowledge<span class="sp"></span><a data-act="k-files" data-id="${b.id}">Files</a><a data-act="k-url" data-id="${b.id}">Link</a><a data-act="k-note" data-id="${b.id}">Note</a></h5>${b.knowledge.length ? b.knowledge.map((k) => `<div class="kvr click" data-act="k-preview" data-id="${b.id}" data-k="${k.id}"><div class="t"><b>${esc(k.title)}</b><small>${esc(k.category)} · ${(k.chars / 1000).toFixed(1)}k characters</small></div><button class="ib" data-act="k-remove" data-id="${b.id}" data-k="${k.id}">${I('x')}</button></div>`).join('') : '<div class="empty">Give it files, notes or links it should know.</div>'}</div>
      <div class="sec"><h5>Notifications</h5>${[['done', 'When it finishes'], ['failed', 'When it can\'t finish'], ['approval', 'When it needs you']].map(([k, l]) => `<div class="kvr"><div class="t"><b>${l}</b></div>${sw(b.notify?.[k] !== false, 'notify', `data-k="${k}"`)}</div>`).join('')}<div class="helpl">It also pops out of the notch when it needs you.</div></div>
      <div class="sec"><h5>More</h5><div class="row2"><button class="btn" data-act="bot-act" data-a="export" data-id="${b.id}">Export</button><button class="btn" data-act="bot-act" data-a="duplicate" data-id="${b.id}">Duplicate</button><button class="btn" data-act="bot-act" data-a="archive" data-id="${b.id}">Hide</button><button class="btn danger" data-act="bot-delete" data-id="${b.id}">Delete…</button></div></div>`;
  };
  PANEL.library = (b) => {
    const ids = new Set(S.tasks.filter((t) => t.botId === b.id).map((t) => t.id));
    const files = S.files.filter((f) => ids.has(f.taskId));
    return `<div class="sec first"><h5>Files it made</h5>${files.length ? files.map((f) => `<div class="kvr click" data-act="file" data-id="${f.id}"><span class="fic">${esc(fileExt(f))}</span><div class="t"><b>${esc(f.title || f.fileName)}</b><small>${esc(f.fileName)} · ${ago(f.createdAt)}</small></div><button class="ib" data-act="file-menu" data-id="${f.id}">${I('more')}</button></div>`).join('') : '<div class="empty">Documents, spreadsheets and decks it makes land here.</div>'}</div>
      <div class="sec"><h5>Knowledge</h5>${b.knowledge.length ? b.knowledge.map((k) => `<div class="kvr click" data-act="k-preview" data-id="${b.id}" data-k="${k.id}"><div class="t"><b>${esc(k.title)}</b><small>${esc(k.category)}</small></div></div>`).join('') : '<div class="empty">None.</div>'}</div>
      <button class="btn" data-act="files-folder">Open the files folder</button>`;
  };
  PANEL.computer = (b) => {
    const steps = S.tasks.filter((t) => t.botId === b.id).flatMap((t) => t.steps.map((s) => ({ ...s, t }))).sort((a, z) => (z.at || 0) - (a.at || 0)).slice(0, 14);
    const working = ['work', 'think'].includes(stateOf(b));
    return `<div class="card nowc"><span class="avh" data-size="36"></span><div class="t"><b>${working ? 'Working now' : 'Resting'}</b><div class="helpl">${esc(working ? lineOf(b).text : 'Nothing running')}</div></div></div>
      <div class="note">${esc(b.name)} works on <b>this PC</b> with the tools you switched on. A live screen you can watch and take over is coming next — until then, every action it takes is listed here and in the chat.</div>
      <div class="sec"><h5>What it did</h5>${steps.length ? `<ul class="steps">${steps.map((s) => `<li class="${s.status}"><i>${s.status === 'DONE' ? '✓' : s.status === 'WAITING' ? '…' : '!'}</i><div>${esc(s.label)}<small>${esc(s.detail || '')}${s.at ? ` · ${ago(s.at)}` : ''}</small></div></li>`).join('')}</ul>` : '<div class="empty">No actions yet.</div>'}</div>`;
  };

  // ---------------------------------------------------------------- task drawer
  function taskDrawer(id) {
    const t = task(id); if (!t) { closeLayer(); return; }
    R.task = id;
    const b = bot(t.botId); const c = conv(t.conversationId);
    const result = c ? [...c.messages].reverse().find((m) => m.role === 'assistant' && m.taskId === t.id) : null;
    const files = (t.fileIds || []).map((f) => S.files.find((x) => x.id === f)).filter(Boolean);
    const plan = t.plan || []; const done = t.steps.filter((s) => s.status === 'DONE').length;
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(t.status);
    const keep = $('steerIn') ? $('steerIn').value : '';
    $('layer').innerHTML = `<div class="scrim light" data-act="scrim"></div><aside class="drawer"><div class="mhead">${b ? avHtml(b.shape, b.color, 28, stateOf(b), b.id) : ''}<b>${esc(t.title)}</b><button class="ib" data-act="close">${I('x')}</button></div><div class="mbody" id="drawerBody">
      <div class="row2">${statusPill(t)}<span class="pill">${esc(b ? b.name : 'Removed bot')}</span><span class="pill">from ${esc(t.source || 'chat')}</span>${t.parentId ? `<a class="more" data-act="task" data-id="${t.parentId}">← parent task</a>` : ''}</div>
      ${t.status === 'REQUIRES_INPUT' && t.input ? inputCard(t) : ''}
      <div class="row2">${['IN_PROGRESS', 'QUEUED'].includes(t.status) ? `<button class="btn" data-act="task-act" data-a="pause" data-id="${t.id}">${I('pause')}Pause</button>` : ''}${t.status === 'PAUSED' ? `<button class="btn pri" data-act="task-act" data-a="resume" data-id="${t.id}">${I('play')}Resume</button>` : ''}${!terminal ? `<button class="btn danger" data-act="task-act" data-a="cancel" data-id="${t.id}">Stop</button>` : ''}${['FAILED', 'CANCELLED'].includes(t.status) ? `<button class="btn pri" data-act="task-act" data-a="retry" data-id="${t.id}">${I('retry')}Try again</button>` : ''}${terminal || ['QUEUED', 'PAUSED'].includes(t.status) ? `<button class="btn ghost" data-act="task-del" data-id="${t.id}">Delete</button>` : ''}</div>
      ${!terminal ? `<div class="field"><label>Add an instruction while it works</label><div class="row2"><input type="text" id="steerIn" value="${esc(keep)}" placeholder="e.g. Only use sources from 2026"><button class="btn" data-act="steer" data-id="${t.id}">Add</button></div></div>` : ''}
      ${t.description && t.description !== t.title ? `<div class="field"><label>What it was asked</label><div class="md jobtxt scroll">${md(t.description)}</div></div>` : ''}
      ${plan.length ? `<div class="field"><label>Plan · ${done}/${plan.length}</label><ul class="steps">${plan.map((p, i) => `<li class="${i < done ? 'DONE' : 'PLAN'}"><i>${i < done ? '✓' : i + 1}</i><div>${esc(p)}</div></li>`).join('')}</ul></div>` : ''}
      <div class="field"><label>What it did</label>${t.steps.length ? `<ul class="steps">${t.steps.map((s) => `<li class="${s.status}"><i>${s.status === 'DONE' ? '✓' : s.status === 'WAITING' ? '…' : '!'}</i><div>${esc(s.label)}${s.verified ? ' <span class="pill ok">verified</span>' : ''}<small>${esc(s.detail || '')}${s.at ? ` · ${clock(s.at)}` : ''}</small></div></li>`).join('')}</ul>` : '<div class="empty">No actions yet.</div>'}</div>
      ${(t.children || []).length ? `<div class="field"><label>Handed to other bots</label>${t.children.map((x) => task(x)).filter(Boolean).map((x) => `<div class="kvr click" data-act="task" data-id="${x.id}"><div class="t"><b>${esc(x.title)}</b><small>${esc(bot(x.botId)?.name || '')}</small></div>${statusPill(x)}</div>`).join('')}</div>` : ''}
      ${files.length ? `<div class="field"><label>Files</label>${files.map((f) => `<span class="fcard" data-act="file" data-id="${f.id}"><i>${esc(fileExt(f))}</i>${esc(f.fileName)}</span>`).join('')}</div>` : ''}
      ${t.summary ? `<div class="field"><label>${t.status === 'FAILED' ? 'What went wrong' : 'Result'}</label><div class="md jobtxt">${md(t.summary)}</div></div>` : ''}
      ${result && result.text !== t.summary ? `<div class="field"><label>Full answer</label><div class="md jobtxt scroll">${md(result.text)}</div></div>` : ''}
      <div class="helpl">Created ${new Date(t.createdAt).toLocaleString('en-GB')}${t.finishedAt ? ` · took ${Math.max(1, Math.round((t.finishedAt - (t.startedAt || t.createdAt)) / 1000))} s` : ''}${t.hasCheckpoint ? ' · checkpoint saved' : ''}</div></div></aside>`;
  }

  // ---------------------------------------------------------------- settings (one modal, tabs)
  const STABS = [['general', 'General', 'gear'], ['apps', 'Connect apps', 'link'], ['routines', 'Routines', 'clock'], ['skills', 'Skills', 'spark'], ['activity', 'Activity', 'bolt'], ['usage', 'Usage', 'check'], ['hidden', 'Hidden bots', 'user']];
  function openSettings(tab) {
    R.stab = tab || R.stab || 'general';
    const body = `<div class="stabs"><nav>${STABS.map(([k, l, i]) => `<button class="${R.stab === k ? 'on' : ''}" data-act="stab" data-t="${k}">${I(i)}${l}</button>`).join('')}</nav><div class="mbody" id="sbody">${SET[R.stab]()}</div></div>`;
    modal('Settings', body, '', 'xl');
  }
  function refreshSettings() {
    const b = $('sbody'); if (!b) return;
    if ($('layer').contains(document.activeElement) && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) return;
    const h = SET[R.stab](); const sig = String(hash(h));
    if (b.dataset.h !== sig) { const sc = b.scrollTop; b.innerHTML = h; b.dataset.h = sig; b.scrollTop = sc; }
  }
  const SET = {};
  SET.general = () => `<div class="field"><label>Appearance</label><div class="seg">${[['system', 'Match Windows'], ['light', 'Light'], ['dark', 'Dark']].map(([k, l]) => `<button class="${(S.settings.theme || 'system') === k ? 'on' : ''}" data-act="set" data-k="theme" data-v="${k}">${l}</button>`).join('')}</div></div>
    <div class="two"><div class="field"><label>Your name</label><input type="text" id="pfName" value="${esc(S.profile.name || '')}"></div><div class="field"><label>What you do</label><input type="text" id="pfWork" value="${esc(S.profile.work || '')}"></div></div>
    <button class="btn" data-act="pf-save">Save</button>
    <div class="field top"><label>Desktop notifications</label><div class="kvr"><div class="t"><b>Notify me when bots finish, fail or need me</b><small>Only when this window isn't in front. Each bot has its own switches too.</small></div>${sw(S.settings.notifyOn, 'set', 'data-k="notifyOn" data-v="toggle"')}</div></div>
    <div class="field"><label>Suggestions from your primary bot</label><div class="seg">${['off', 'low', 'balanced', 'high'].map((k) => `<button class="${S.settings.proactivity === k ? 'on' : ''}" data-act="set" data-k="proactivity" data-v="${k}">${k[0].toUpperCase() + k.slice(1)}</button>`).join('')}</div><div class="help">Only from real events: failures, waiting approvals, interrupted work, due dates.</div></div>
    <div class="field"><label>Welcome</label><div class="row2"><button class="btn" data-act="replay">Replay the intro</button><button class="btn" data-act="redo-onb">Answer the welcome questions again</button><button class="btn" data-act="nebula-settings">AI key & voice</button></div></div>`;
  SET.routines = () => (S.routines.length ? S.routines.map((r) => { const b = bot(r.botId); const h = r.history || []; return `<div class="kvr pad">${b ? avHtml(b.shape, b.color, 30, 'idle', b.id) : ''}<div class="t"><b>${esc(r.name)}</b><small>${esc(b?.name || '')} · ${esc(r.when)}${r.active && r.nextRun ? ` · next ${ago(r.nextRun)}` : ''}${h[0] ? ` · last ${ago(h[0].at)}` : ''}</small></div><button class="btn" data-act="routine-act" data-a="run" data-id="${r.id}">Run now</button>${sw(r.active, 'routine-act', `data-a="toggle" data-id="${r.id}"`)}<button class="ib" data-act="routine-del" data-id="${r.id}">${I('x')}</button></div>`; }).join('') + '<div class="helpl">Routines run on this PC while Nebula is running (also in the tray). Runs missed by more than 6 hours are skipped, never run late. To make one, ask a bot: “do this every weekday at 8”.</div>' : '<div class="empty">No routines yet. Ask any bot in its chat — “check my news every morning at 7” — and it sets one up.</div>');
  SET.skills = () => `<div class="row2"><button class="btn brand" data-act="skill-ai">${I('spark')}Create with AI</button><button class="btn" data-act="skill-new">Write one</button></div>
    ${S.skills.length ? S.skills.map((s) => `<div class="kvr click" data-act="skill-edit" data-id="${s.id}"><div class="t"><b>${esc(s.name)} <span class="tag">v${s.version}</span></b><small>${esc(s.description)}</small></div><span class="helpl">${S.bots.filter((b) => b.skills.includes(s.id)).map((b) => esc(b.name)).join(', ')}</span></div>`).join('') : '<div class="empty">No skills yet.</div>'}
    <div class="field top"><label>Starter skills</label><div class="row2">${S.catalogue.skillTemplates.map((t) => `<button class="btn" data-act="skill-tpl" data-i="${t.i}">${I('plus')}${esc(t.name)}</button>`).join('')}</div></div>`;
  SET.activity = () => { if (!S.activity.length) return '<div class="empty">Nothing yet.</div>'; let out = ''; let day = ''; for (const e of S.activity.slice(0, 200)) { const d = new Date(e.at).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' }); if (d !== day) { day = d; out += `<div class="day">${esc(d)}</div>`; } const b = bot(e.botId); out += `<div class="evr ${e.taskId ? 'link' : ''}" ${e.taskId ? `data-act="task" data-id="${e.taskId}"` : ''}><time>${clock(e.at)}</time>${b ? avHtml(b.shape, b.color, 22, 'still', b.id) : '<span></span>'}<div><span class="k">${esc(e.kind)}</span>${esc(e.text)}</div></div>`; } return `<div class="tl">${out}</div>`; };
  SET.usage = () => { const ts = S.tasks; const done = ts.filter((t) => t.status === 'COMPLETED').length; const fail = ts.filter((t) => t.status === 'FAILED').length; const secs = Math.round(ts.reduce((a, t) => a + (t.finishedAt && t.startedAt ? t.finishedAt - t.startedAt : 0), 0) / 1000);
    return `<div class="three"><div class="card stat"><span>Tasks done</span><b>${done}</b></div><div class="card stat"><span>Success rate</span><b>${done + fail ? `${Math.round(done / (done + fail) * 100)}%` : '—'}</b></div><div class="card stat"><span>Time working</span><b>${secs > 5400 ? `${(secs / 3600).toFixed(1)} h` : secs > 90 ? `${Math.round(secs / 60)} min` : `${secs} s`}</b></div></div>
      <table class="t"><tr><th>Bot</th><th>Tasks</th><th>Done</th><th>Failed</th></tr>${S.bots.map((b) => { const m = ts.filter((t) => t.botId === b.id); return `<tr><td>${esc(b.name)}</td><td>${m.length}</td><td>${m.filter((t) => t.status === 'COMPLETED').length}</td><td>${m.filter((t) => t.status === 'FAILED').length}</td></tr>`; }).join('')}</table>
      <div class="note">Nebula uses your own AI key, so there's no weekly allowance to run out of. “Time saved” isn't shown — there's no honest way to measure it.</div>`; };
  SET.hidden = () => { const h = S.bots.filter((b) => b.archived || b.draft); return h.length ? h.map((b) => `<div class="kvr">${avHtml(b.shape, b.color, 30, 'sleep', b.id)}<div class="t"><b>${esc(b.name)}</b><small>${b.draft ? 'Draft' : 'Hidden'}</small></div><button class="btn" data-act="bot-act" data-a="unarchive" data-id="${b.id}">Show in sidebar</button></div>`).join('') : '<div class="empty">No hidden bots.</div>'; };
  // Connect apps (MCP)
  const MCP_PRESETS = [
    { label: 'Web browser (Playwright)', cfg: { name: 'Browser', type: 'stdio', command: 'npx', args: ['-y', '@playwright/mcp@latest'] }, note: 'Lets a bot open pages, click, type and read them in a real browser window. Needs Node.js.' },
    { label: 'Files in a folder', folder: true, cfg: { name: 'Files', type: 'stdio', command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem'] }, note: 'Read and write files — only inside the folder you pick. Needs Node.js.' },
    { label: 'GitHub', cfg: { name: 'GitHub', type: 'http', url: 'https://api.githubcopilot.com/mcp/', headers: { Authorization: 'Bearer <YOUR_GITHUB_TOKEN>' } }, note: 'Make a token at github.com → Settings → Developer settings → Personal access tokens, and paste it after “Bearer ”.' },
  ];
  const PLACEHOLDER = /<[^>]+>|your[_-]?|xxx|_here\b|^\s*$|\$\{/i;
  SET.apps = () => `<div class="row2"><button class="btn brand" data-act="mcp-add">${I('plus')}Add an app (MCP server)</button><button class="btn" data-act="url" data-url="https://mcp.so">Browse mcp.so</button></div>
    ${(S.mcp || []).map((s) => { const tools = s.tools || []; const open = R.mcpOpen === s.id; return `<div class="card mcpc"><div class="hd"><div class="t"><b>${esc(s.name)}</b><div class="helpl ell">${s.type === 'http' ? esc(s.url) : esc(`${s.command} ${(s.args || []).join(' ')}`)}</div></div>${s.status === 'connected' ? '<span class="pill ok">Connected</span>' : s.status === 'connecting' ? '<span class="pill run">Connecting…</span>' : s.status === 'error' ? '<span class="pill bad">Problem</span>' : `<span class="pill">${s.enabled === false ? 'Off' : 'Not connected'}</span>`}</div>
      ${s.status === 'error' && s.error ? `<div class="note warn">${esc(s.error)}</div>` : ''}
      <div class="helpl">${tools.length} actions · used by ${(s.botsUsing || []).map((id) => esc(bot(id)?.name || '')).join(', ') || 'no bots yet (switch it on in a bot\'s Details)'}</div>
      ${open ? `<div class="tools">${tools.map((t) => { const c = (s.toolCfg || {})[t.name] || { on: true, risk: t.risk }; return `<div class="kvr"><div class="t"><b>${esc(t.title || t.name)}</b><small>${esc((t.description || '').slice(0, 120))}</small></div><select data-act-change="mcp-risk" data-id="${s.id}" data-t="${esc(t.name)}">${['none', 'low', 'medium', 'high'].map((r) => `<option value="${r}" ${(c.risk || t.risk) === r ? 'selected' : ''}>${{ none: 'Read-only', low: 'Low risk', medium: 'Ask (medium)', high: 'Always ask' }[r]}</option>`).join('')}</select>${sw(c.on !== false, 'mcp-tool', `data-id="${s.id}" data-t="${esc(t.name)}"`)}</div>`; }).join('')}</div>` : ''}
      <div class="row2">${tools.length ? `<button class="btn" data-act="mcp-open" data-id="${s.id}">${open ? 'Hide actions' : 'Choose actions'}</button>` : ''}<button class="btn" data-act="mcp-do" data-a="connect" data-id="${s.id}">${I('retry')}Reconnect</button><button class="btn" data-act="mcp-edit" data-id="${s.id}">Edit</button><button class="btn" data-act="mcp-do" data-a="toggle" data-id="${s.id}">${s.enabled === false ? 'Turn on' : 'Turn off'}</button><button class="btn danger" data-act="mcp-remove" data-id="${s.id}">Remove</button></div></div>`; }).join('')}
    <div class="field top"><label>Quick add</label><div class="row2">${MCP_PRESETS.map((p, i) => `<button class="btn" data-act="mcp-preset" data-i="${i}">${I('plus')}${esc(p.label)}</button>`).join('')}</div></div>
    <div class="note">Built in for every bot (switch per bot in Details): web search, files (Word, Excel, PowerPoint, PDF), apps and links on this PC, music, timers, clipboard, the auto-clicker, and working with other bots. One-click sign-in for Gmail, Calendar and Slack is coming with your Nebula account — until then, most of those apps have an MCP server on mcp.so.</div>`;

  // ---------------------------------------------------------------- palette (Ctrl+K)
  function openPalette() {
    $('layer').innerHTML = '<div class="scrim top" data-act="scrim"><div class="palette"><input id="palIn" placeholder="Search bots, work and actions…" autocomplete="off"><div class="res" id="palRes"></div></div></div>';
    const draw = () => {
      const q = $('palIn').value.toLowerCase().trim();
      const it = [];
      for (const b of active()) if (!q || b.name.toLowerCase().includes(q) || (b.role || '').toLowerCase().includes(q)) it.push({ g: 'Bots', html: `${avHtml(b.shape, b.color, 22, 'still', b.id)}${esc(b.name)}<small>${esc(b.role || '')}</small>`, fn: () => openBot(b.id) });
      for (const t of S.tasks.filter((x) => !x.parentId).sort((a, z) => z.updatedAt - a.updatedAt)) if (q && (t.title.toLowerCase().includes(q) || (t.summary || '').toLowerCase().includes(q)) && it.length < 40) it.push({ g: 'Work', html: `${I('bolt')}${esc(t.title)}<small>${esc(bot(t.botId)?.name || '')}</small>`, fn: () => taskDrawer(t.id) });
      if (q) for (const c of S.conversations) for (const m of c.messages) if (m.role !== 'event' && m.text.toLowerCase().includes(q) && it.length < 60) it.push({ g: 'Messages', html: `${I('reply')}${esc(m.text.replace(/\s+/g, ' ').slice(0, 70))}<small>${esc(bot(c.botId)?.name || '')}</small>`, fn: () => { openBot(c.botId); setTimeout(() => A.jump({ mid: m.id }), 160); } });
      for (const [l, fn] of [['New bot', () => A['new-bot']()], ['Connect apps', () => openSettings('apps')], ['Routines', () => openSettings('routines')], ['Skills', () => openSettings('skills')], ['Activity', () => openSettings('activity')], ['Settings', () => openSettings('general')], ['Replay the intro', () => A.replay()]]) if (!q || l.toLowerCase().includes(q)) it.push({ g: 'Actions', html: `${I('spark')}${l}`, fn });
      let g = ''; $('palRes').innerHTML = it.map((x, i) => `${x.g !== g ? `<div class="grp">${(g = x.g)}</div>` : ''}<button class="it ${i === 0 ? 'on' : ''}" data-i="${i}">${x.html}</button>`).join('') || '<div class="empty">Nothing found.</div>';
      $('palRes').querySelectorAll('.it').forEach((el) => (el.onclick = (e) => { e.stopPropagation(); closeLayer(); it[+el.dataset.i].fn(); }));
      R.pal = it; R.palI = 0;
    };
    $('palIn').oninput = draw;
    $('palIn').onkeydown = (e) => {
      const els = [...$('palRes').querySelectorAll('.it')];
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); R.palI = Math.max(0, Math.min(els.length - 1, R.palI + (e.key === 'ArrowDown' ? 1 : -1))); els.forEach((x, i) => x.classList.toggle('on', i === R.palI)); els[R.palI]?.scrollIntoView({ block: 'nearest' }); }
      if (e.key === 'Enter' && R.pal[R.palI]) { const f = R.pal[R.palI].fn; closeLayer(); f(); }
    };
    draw(); setTimeout(() => $('palIn').focus(), 20);
  }

  // ---------------------------------------------------------------- navigation
  function openBot(id) {
    if (!bot(id)) return;
    R.view = 'chat'; R.bot = id; replyTo = null; attach = [];
    render();
  }

  let rq = 0;
  function render() {
    if (!S) return;
    cancelAnimationFrame(rq);
    rq = requestAnimationFrame(() => {
      applyTheme();
      renderRoster(); renderFoot(); renderMain(); renderPanel();
      const typing = $('layer').contains(document.activeElement) && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
      if (R.task && $('layer').querySelector('.drawer') && !typing) { const sc = $('drawerBody')?.scrollTop || 0; taskDrawer(R.task); if ($('drawerBody')) $('drawerBody').scrollTop = sc; }
      if (R.settingsOpen) refreshSettings();
      if (R.apprOpen && !S.tasks.some((t) => t.status === 'REQUIRES_INPUT')) closeLayer();
      const b = bot(R.bot); if (b && b.unread && R.view === 'chat' && document.hasFocus()) call('ws:read', { botId: b.id });
    });
  }
  function applyTheme() { const t = S.settings.theme || 'system'; if (t === 'system') document.documentElement.removeAttribute('data-theme'); else if (document.documentElement.dataset.theme !== t) document.documentElement.dataset.theme = t; }

  // ---------------------------------------------------------------- actions
  const A = {};
  A.bot = (d) => openBot(d.id);
  A.close = () => closeLayer();
  A.scrim = (_d, e) => { if (e.target.classList.contains('scrim')) closeLayer(); };
  A.url = (d) => api.send('open:url', d.url);
  A['new-bot'] = () => { R.view = 'create'; R.cr = null; $('main').dataset.mount = ''; render(); };
  A['cancel-create'] = () => { R.view = 'chat'; render(); };
  A['cr-color'] = (d) => { R.cr.color = d.c; renderCreate(); };
  A['cr-shape'] = (d) => { R.cr.shape = +d.i; renderCreate(); };
  A['create-go'] = async () => { const cr = R.cr; if (!cr || !cr.name.trim()) return; $('crGo').disabled = true; const r = await call('ws:quick-create', { name: cr.name.trim(), shape: cr.shape, color: cr.color }); if (r) { R.view = 'chat'; R.bot = r.id; R.cr = null; await refresh(); } else $('crGo').disabled = false; };
  A['create-tpl'] = async (d) => { const r = await call('ws:quick-create', { template: d.k }); if (r) { R.view = 'chat'; R.bot = r.id; R.cr = null; await refresh(); } };
  A['toggle-panel'] = () => { R.panel = !R.panel; render(); };
  A['panel-tab'] = (d) => { R.panel = true; R.ptab = d.t; render(); };
  A.starter = (d) => { const c = $('composer'); if (d.t === 'Something else…') { c.focus(); return; } c.value = d.t; A.send(); };
  A.send = async () => {
    const el = $('composer'); if (!el) return;
    let text = el.value.trim(); if (!text && !attach.length) return;
    const b = bot(R.bot);
    if (replyTo) text = `${replyTo.text.split('\n').slice(0, 4).map((l) => `> ${l.slice(0, 200)}`).join('\n')}\n\n${text}`;
    for (const a of attach) text += `\n\n[Attached file: ${a.name}]\n\`\`\`\n${a.text}\n\`\`\``;
    const rt = replyTo; el.value = ''; drafts[b.id] = ''; replyTo = null; attach = []; grow(el);
    await call('ws:chat', { botId: b.id, cid: b.mainCid, text, replyTo: rt?.id });
    if ($('tx')) $('tx').scrollTop = 1e9;
  };
  A['stop-chat'] = () => { const b = bot(R.bot); if (b?.mainCid) call('ws:conv-act', { cid: b.mainCid, act: 'stop' }); };
  A.voice = () => { api.send('ws:voice'); toast('Nebula is listening.'); };
  A.attach = () => {
    const inp = document.createElement('input'); inp.type = 'file'; inp.multiple = true;
    inp.onchange = () => { for (const f of inp.files) { if (f.size > 300 * 1024) { toast(`${f.name} is over 300 KB.`, true); continue; } if (!/^text\/|json|xml|csv|javascript/.test(f.type) && !/\.(txt|md|csv|tsv|json|html?|xml|log|ya?ml|js|ts|py|java|kt|c|cpp|cs|go|rs|sql|css)$/i.test(f.name)) { toast(`${f.name}: only text files can be attached for now.`, true); continue; } const r = new FileReader(); r.onload = () => { attach.push({ name: f.name, text: String(r.result).slice(0, 300000) }); render(); }; r.readAsText(f); } };
    inp.click();
  };
  A['att-x'] = (d) => { attach.splice(+d.i, 1); render(); };
  const msgOf = (mid) => conv(bot(R.bot)?.mainCid)?.messages.find((m) => m.id === mid);
  A.reply = (d) => { const m = msgOf(d.mid); if (m) { replyTo = { id: m.id, text: m.text.replace(/^>.*\n?/gm, '').trim() }; render(); setTimeout(() => $('composer')?.focus(), 30); } };
  A['reply-x'] = () => { replyTo = null; render(); };
  A.copy = (d) => { const m = msgOf(d.mid); if (m) navigator.clipboard.writeText(m.text).then(() => toast('Copied.'), () => toast('Couldn\'t copy.', true)); };
  A['react-pick'] = (d, e) => menu(e, ['👍', '❤️', '🎉', '😂', '😮', '✅'].map((em) => ({ label: em, fn: () => call('ws:conv-act', { cid: bot(R.bot).mainCid, act: 'react', arg: { mid: d.mid, emoji: em } }) })));
  A.react = (d) => call('ws:conv-act', { cid: bot(R.bot).mainCid, act: 'react', arg: { mid: d.mid, emoji: d.e } });
  A['edit-msg'] = (d) => { const m = msgOf(d.mid); if (!m) return; confirmBox('Edit and resend?', 'Messages after this one are removed from the chat, then your message goes back in the box.', 'Edit', async () => { await call('ws:conv-act', { cid: bot(R.bot).mainCid, act: 'truncate', arg: m.id }); const c = $('composer'); c.value = m.text; grow(c); c.focus(); }, false); };
  A['retry-msg'] = async (d) => { const b = bot(R.bot); const c = conv(b.mainCid); if (!c) return; const i = c.messages.findIndex((m) => m.id === d.mid); const u = [...c.messages.slice(0, i)].reverse().find((m) => m.role === 'user'); if (!u) return; await call('ws:conv-act', { cid: c.id, act: 'truncate', arg: u.id }); await call('ws:chat', { botId: b.id, cid: c.id, text: u.text }); };
  A.jump = (d) => { const el = document.getElementById(`m-${d.mid}`); if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); el.animate([{ background: 'rgba(122,60,255,.14)' }, { background: 'transparent' }], 1400); } };
  A.expand = (d) => { R.exp = R.exp || {}; R.exp[d.mid] = !R.exp[d.mid]; render(); };
  A.file = (d) => call('ws:file', { id: d.id, act: 'open' });
  A['file-menu'] = (d, e) => menu(e, [{ label: 'Open', fn: () => call('ws:file', { id: d.id, act: 'open' }) }, { label: 'Show in folder', fn: () => call('ws:file', { id: d.id, act: 'show' }) }, '-', { label: 'Delete file', danger: true, fn: () => confirmBox('Delete this file?', 'It\'s removed from your PC.', 'Delete', () => call('ws:file', { id: d.id, act: 'delete' })) }]);
  A['files-folder'] = () => api.send('ws:files-folder');
  A.task = (d) => taskDrawer(d.id);
  A['task-act'] = (d) => call('ws:task-act', { id: d.id, act: d.a });
  A['task-del'] = (d) => confirmBox('Delete this task?', 'Its record is removed. Files it made stay.', 'Delete', () => call('ws:task-act', { id: d.id, act: 'delete' }));
  A.steer = async (d) => { const v = $('steerIn').value.trim(); if (!v) return; $('steerIn').value = ''; await call('ws:task-act', { id: d.id, act: 'steer', arg: v }, 'Added — it takes it into account at its next step.'); };
  A.answer = async (d, e) => { e.currentTarget.closest('.ask')?.classList.add('sent'); await call('ws:answer', { taskId: d.id, choices: [d.c] }); if (/^Deny/.test(d.c)) toast('Denied — it won\'t try that again.'); };
  A['answer-text'] = async (d) => { const v = $(`ans-${d.id}`).value.trim(); if (v) await call('ws:answer', { taskId: d.id, choices: [v] }); };
  A['edit-approval'] = (d) => {
    const t = task(d.id); if (!t?.input?.card) return;
    modal('Edit before allowing', `<p class="helpl">Change exactly what it will do, then allow the edited version.</p><textarea id="apEdit" rows="12" class="mono">${esc(JSON.stringify(t.input.card.args, null, 2))}</textarea>`, '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="apGo">Allow edited</button>');
    $('apGo').onclick = async () => { let a; try { a = JSON.parse($('apEdit').value); } catch { toast('That isn\'t valid JSON.', true); return; } closeLayer(); await call('ws:answer', { taskId: d.id, choices: ['Approve'], edited: a }); };
  };
  A.approvals = () => { const ap = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT'); if (!ap.length) { toast('Nothing is waiting for you.'); return; } if (ap.length === 1) { openBot(ap[0].botId); return; } modal(`Waiting for you · ${ap.length}`, ap.map(inputCard).join('')); R.apprOpen = true; };
  A['bot-menu'] = (d, e) => { const b = bot(d.id); menu(e, [{ label: 'Details', fn: () => A['panel-tab']({ t: 'details' }) }, { label: S.pins.some((p) => p.kind === 'bot' && p.id === b.id) ? 'Unpin' : 'Pin to top', fn: () => call('ws:pin', { kind: 'bot', id: b.id }) }, { label: b.primary ? 'Primary bot ✓' : 'Make primary bot', fn: () => call('ws:bot-act', { id: b.id, act: 'primary' }) }, { label: b.paused ? 'Resume' : 'Pause', fn: () => call('ws:bot-act', { id: b.id, act: b.paused ? 'resume' : 'pause' }) }, { label: 'Export', fn: () => A['bot-act']({ id: b.id, a: 'export' }) }, '-', { label: 'Hide from sidebar', fn: () => A['bot-act']({ id: b.id, a: 'archive' }) }, { label: 'Delete…', danger: true, fn: () => A['bot-delete']({ id: b.id }) }]); };
  A['bot-act'] = async (d) => { const r = await call('ws:bot-act', { id: d.id, act: d.a }); if (!r) return; if (d.a === 'duplicate') { toast('Duplicated.'); if (typeof r === 'string') openBot(r); } if (d.a === 'export') toast('Exported.'); if (d.a === 'archive') { toast('Hidden — find it in Settings → Hidden bots.'); R.bot = null; render(); } if (d.a === 'unarchive') toast('Back in the sidebar.'); };
  A['pin-bot'] = (d) => call('ws:pin', { kind: 'bot', id: d.id });
  A['bot-delete'] = (d) => {
    const b = bot(d.id);
    modal(`Delete ${esc(b.name)}?`, `<p class="dim">This removes the bot, its memory (${b.memories.length}), knowledge (${b.knowledge.length}) and routines. It can't be undone — export it first if you might want it back. Hiding keeps everything.</p><div class="opt" data-act="toggle-self"><span class="box"></span><div><b>Also delete its chats and work</b><small>Its tasks, conversations and the files it made.</small></div></div><div class="field"><label>Type its name to confirm</label><input type="text" id="delName" placeholder="${esc(b.name)}"></div>`, '<button class="btn" data-act="close">Cancel</button><button class="btn danger" id="delGo">Delete forever</button>');
    $('delGo').onclick = async () => { if ($('delName').value.trim() !== b.name) { toast('The name doesn\'t match.', true); return; } const work = $('layer').querySelector('.opt').classList.contains('on'); closeLayer(); await call('ws:bot-act', { id: b.id, act: 'delete', arg: { work } }, `${b.name} was deleted.`); R.bot = null; render(); };
  };
  A['toggle-self'] = (_d, e) => e.currentTarget.classList.toggle('on');
  const stripBot = (b) => { const o = JSON.parse(JSON.stringify(b)); for (const k of ['status', 'current', 'counts', 'mainCid', 'last', 'unread']) delete o[k]; return o; };
  const saveBot = (patch) => call('ws:bot-save', { ...stripBot(bot(R.bot)), ...patch });
  A.cap = (d) => { const b = bot(R.bot); saveBot({ caps: b.caps.includes(d.c) ? b.caps.filter((x) => x !== d.c) : [...b.caps, d.c] }); };
  A['mcp-bot'] = (d) => { const b = bot(R.bot); const m = { ...(b.mcp || {}) }; if (m[d.s]) delete m[d.s]; else m[d.s] = true; saveBot({ mcp: m }); };
  A.autonomy = (d) => saveBot({ autonomy: d.v });
  A.notify = (d) => { const b = bot(R.bot); saveBot({ notify: { ...(b.notify || {}), [d.k]: b.notify?.[d.k] === false } }); };
  A['edit-job'] = () => {
    const b = bot(R.bot); const I2 = b.ins || {};
    const F = [['role', 'Job title', b.role], ['resp', 'Its job', I2.resp], ['do', 'Always', I2.do], ['never', 'Never', I2.never], ['approach', 'Sources and apps', I2.approach], ['escalation', 'When to ask you', I2.escalation], ['output', 'Output', I2.output], ['style', 'Style', I2.style]];
    modal(`${esc(b.name)}'s job`, `<p class="helpl">Or just tell ${esc(b.name)} in the chat — it updates this itself.</p>${F.map(([k, l, v]) => `<div class="field"><label>${l}</label>${k === 'role' ? `<input type="text" id="jb-${k}" value="${esc(v || '')}">` : `<textarea id="jb-${k}" rows="2">${esc(v || '')}</textarea>`}</div>`).join('')}`, '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="jbGo">Save</button>');
    $('jbGo').onclick = async () => { const ins = { ...(b.ins || {}) }; for (const [k] of F.slice(1)) ins[k] = $(`jb-${k}`).value; await saveBot({ role: $('jb-role').value, ins, setup: false }); closeLayer(); toast('Saved.'); };
  };
  A['edit-avatar'] = () => {
    const b = bot(R.bot); let sh = b.shape; let co = b.color;
    const draw = () => {
      $('eaAv').innerHTML = avHtml(sh, co, 110, 'idle', 'ea');
      $('eaC').innerHTML = COLORS.map((c) => `<button class="${c === co ? 'on' : ''}" style="background:${c}" data-c="${c}"></button>`).join('') + `<input type="color" value="${co}" id="eaPick" title="Any colour">`;
      $('eaS').innerHTML = SH.map((_x, i) => `<button class="${i === sh ? 'on' : ''}" data-i="${i}">${avHtml(i, co, 30, 'still', `x${i}`)}</button>`).join('');
      $('eaC').querySelectorAll('button').forEach((x) => (x.onclick = (e) => { e.stopPropagation(); co = x.dataset.c; draw(); }));
      $('eaPick').oninput = (e) => { co = e.target.value; $('eaAv').innerHTML = avHtml(sh, co, 110, 'idle', 'ea'); };
      $('eaS').querySelectorAll('button').forEach((x) => (x.onclick = (e) => { e.stopPropagation(); sh = +x.dataset.i; draw(); }));
    };
    modal('Change look', '<div class="create inmodal"><span class="big" id="eaAv"></span><div class="colors" id="eaC"></div><div class="shapes" id="eaS"></div></div>', '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="eaGo">Save</button>');
    draw(); $('eaGo').onclick = async () => { await saveBot({ shape: sh, color: co }); closeLayer(); };
  };
  // knowledge + memory
  A['k-note'] = (d) => { modal('Add a note', '<div class="two"><div class="field"><label>Title</label><input type="text" id="knT" placeholder="House style"></div><div class="field"><label>Category</label><input type="text" id="knC" value="General"></div></div><div class="field"><label>Text</label><textarea id="knX" rows="10" placeholder="Anything it should know."></textarea></div>', '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="knGo">Add</button>'); $('knGo').onclick = async () => { if (await call('ws:knowledge', { botId: d.id, act: 'note', title: $('knT').value, text: $('knX').value, category: $('knC').value }, 'Learned.')) closeLayer(); }; };
  A['k-url'] = (d) => { modal('Add a link', '<div class="field"><label>Web address</label><input type="url" id="kuU" placeholder="https://…"></div><div class="helpl">Nebula reads the page once and saves its text. Pages behind a login can\'t be read.</div>', '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="kuGo">Read it</button>'); $('kuGo').onclick = async () => { $('kuGo').textContent = 'Reading…'; if (await call('ws:knowledge', { botId: d.id, act: 'url', url: $('kuU').value, category: 'Web' }, 'Learned that page.')) closeLayer(); else $('kuGo').textContent = 'Read it'; }; };
  A['k-files'] = async (d) => { const r = await call('ws:knowledge', { botId: d.id, act: 'files', category: 'Files' }); if (r && r.added) toast(`Learned ${r.added} file${r.added > 1 ? 's' : ''}.`); if (r && r.errors && r.errors.length) toast(r.errors[0], true); };
  A['k-remove'] = (d) => confirmBox('Remove this?', 'The bot will no longer know it.', 'Remove', () => call('ws:knowledge', { botId: d.id, act: 'remove', kid: d.k }));
  A['k-preview'] = async (d) => { const t = await call('ws:knowledge', { botId: d.id, act: 'preview', kid: d.k }); if (typeof t === 'string') modal('Preview', `<pre class="pre">${esc(t)}</pre>`, '', 'xl'); };
  A['mem-add'] = (d) => { modal('Remember', '<textarea id="meIn" rows="3" placeholder="Something it should always remember"></textarea>', '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="meGo">Save</button>'); $('meGo').onclick = async () => { await call('ws:memory', { botId: d.id, act: 'add', text: $('meIn').value }); closeLayer(); }; };
  A['mem-remove'] = (d) => call('ws:memory', { botId: d.id, act: 'remove', mid: d.m });
  // routines
  A['routine-act'] = async (d) => { const r = await call('ws:routine-act', { id: d.id, act: d.a }); if (d.a === 'run') toast(r ? 'Running now.' : 'Not started — it may still be running.'); };
  A['routine-del'] = (d) => confirmBox('Delete this routine?', 'It stops running. Past results stay.', 'Delete', () => call('ws:routine-act', { id: d.id, act: 'delete' }));
  // settings
  A.settings = (d) => openSettings(d.tab);
  A.stab = (d) => { R.stab = d.t; $('layer').querySelectorAll('.stabs nav button').forEach((b) => b.classList.toggle('on', b.dataset.t === d.t)); $('sbody').innerHTML = SET[d.t](); $('sbody').dataset.h = ''; $('sbody').scrollTop = 0; };
  A.set = (d) => call('ws:settings', { [d.k]: d.v === 'toggle' ? !S.settings[d.k] : d.v });
  A['pf-save'] = () => call('ws:profile', { name: $('pfName').value.trim(), work: $('pfWork').value.trim() }, 'Saved.');
  A.replay = () => { closeLayer(); window.NBIntro.play(() => {}); };
  A['redo-onb'] = () => { closeLayer(); $('app').hidden = true; window.NBOnboarding.start(S.profile.name); };
  A['nebula-settings'] = () => api.send('ws:open-nebula', 'settings');
  // skills
  A['skill-tpl'] = (d) => call('ws:skill-template', { i: +d.i }, 'Added to your skills.');
  A['skill-new'] = () => skillModal({});
  A['skill-edit'] = (d) => skillModal(S.skills.find((s) => s.id === d.id));
  A['skill-ai'] = () => { modal('Create a skill with AI', '<div class="field"><label>Describe the skill</label><textarea id="saiIn" rows="5" placeholder="e.g. Every Friday, collect what each bot finished this week and write a one-page report."></textarea></div>', '<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="saiGo">Draft it</button>'); $('saiGo').onclick = async () => { $('saiGo').textContent = 'Drafting…'; const r = await call('ws:skill-ai', { text: $('saiIn').value }); if (r && typeof r === 'object') skillModal({ ...r, steps: r.steps || [], tools: (r.tools || []).filter((t) => S.catalogue.tools[t]) }); else $('saiGo').textContent = 'Draft it'; }; };
  function skillModal(s) {
    modal(s.id ? esc(s.name) : 'New skill', `<div class="two"><div class="field"><label>Name</label><input type="text" id="skName" value="${esc(s.name || '')}"></div><div class="field"><label>Category</label><input type="text" id="skCat" value="${esc(s.category || 'General')}"></div></div><div class="field"><label>Description</label><input type="text" id="skDesc" value="${esc(s.description || '')}"></div><div class="field"><label>Instructions</label><textarea id="skIns" rows="4">${esc(s.instructions || '')}</textarea></div><div class="field"><label>Steps (one per line)</label><textarea id="skSteps" rows="4">${esc((s.steps || []).join('\n'))}</textarea></div>${s.id ? `<div class="field"><label>Bots that use it</label><div class="row2">${active().map((b) => `<button class="btn ${b.skills.includes(s.id) ? 'pri' : ''}" data-act="sk-assign" data-bot="${b.id}" data-id="${s.id}">${esc(b.name)}</button>`).join('')}</div></div>` : ''}<div class="helpl">Never put passwords or keys in a skill — Nebula refuses to save them.</div>`,
      `${s.id ? '<button class="btn danger" id="skDel">Delete</button><span class="sp"></span>' : ''}<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="skGo">Save</button>`);
    $('skGo').onclick = async () => { const id = await call('ws:skill-save', { skill: { id: s.id, name: $('skName').value, category: $('skCat').value, description: $('skDesc').value, instructions: $('skIns').value, steps: $('skSteps').value.split('\n').map((x) => x.trim()).filter(Boolean), tools: s.tools || [] } }, 'Saved.'); if (id) openSettings('skills'); };
    if ($('skDel')) $('skDel').onclick = () => confirmBox('Delete this skill?', 'It\'s removed from every bot.', 'Delete', async () => { await call('ws:skill-act', { id: s.id, act: 'delete' }); openSettings('skills'); });
  }
  A['sk-assign'] = async (d, e) => { const b = bot(d.bot); const el = e.currentTarget; await call('ws:skill-act', { id: d.id, act: b.skills.includes(d.id) ? 'unassign' : 'assign', arg: d.bot }); el.classList.toggle('pri'); };
  // MCP
  A['mcp-open'] = (d) => { R.mcpOpen = R.mcpOpen === d.id ? null : d.id; refreshSettings(); };
  A['mcp-do'] = async (d) => { const r = await call('ws:mcp', { act: d.a, id: d.id }); if (d.a === 'connect' && typeof r === 'number') toast(`Connected — ${r} actions.`); };
  A['mcp-remove'] = (d) => { const s = S.mcp.find((x) => x.id === d.id); confirmBox(`Remove ${esc(s.name)}?`, 'Bots lose access and its saved keys are deleted.', 'Remove', () => call('ws:mcp', { act: 'remove', id: d.id })); };
  A['mcp-tool'] = (d) => { const s = S.mcp.find((x) => x.id === d.id); const c = (s.toolCfg || {})[d.t] || {}; call('ws:mcp', { act: 'tool', id: d.id, tool: d.t, patch: { on: c.on === false } }); };
  A['mcp-add'] = async () => {
    const chk = await call('ws:mcp-check');
    modal('Add an app (MCP server)', `<div class="field"><label>Paste the server's config</label><textarea id="mxPaste" rows="9" class="mono" placeholder='{ "mcpServers": { "github": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"], "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "<YOUR_TOKEN>" } } } }'></textarea><div class="help">On mcp.so open a server and copy its “Server Config”. A server URL (https://…) works too.</div></div>
      <div class="row2">${MCP_PRESETS.map((p, i) => `<button class="btn" data-act="mcp-preset" data-i="${i}">${esc(p.label)}</button>`).join('')}</div>
      ${chk && typeof chk === 'object' ? `<div class="kvr"><div class="t"><b>Node.js (for npx servers)</b></div><span class="pill ${chk.npx ? 'ok' : 'need'}">${chk.npx ? 'Installed' : 'Not found'}</span>${chk.npx ? '' : '<button class="btn" data-act="url" data-url="https://nodejs.org">Get it</button>'}</div><div class="kvr"><div class="t"><b>uv (for uvx servers)</b></div><span class="pill ${chk.uvx ? 'ok' : ''}">${chk.uvx ? 'Installed' : 'Not found'}</span></div>` : ''}`,
      '<button class="btn" data-act="close">Cancel</button><button class="btn" id="mxManual">Fill in a form</button><button class="btn pri" id="mxNext">Next</button>');
    $('mxManual').onclick = () => mcpForm([{ name: '', type: 'stdio', command: 'npx', args: [], env: {} }]);
    $('mxNext').onclick = async () => { const r = await call('ws:mcp-parse', { text: $('mxPaste').value }); if (Array.isArray(r)) mcpForm(r); };
  };
  A['mcp-preset'] = async (d) => { const p = MCP_PRESETS[+d.i]; const cfg = JSON.parse(JSON.stringify(p.cfg)); if (p.folder) { const f = await call('ws:mcp-folder'); if (typeof f !== 'string') return; cfg.args.push(f); cfg.name = `Files (${f.split(/[\\/]/).pop()})`; } mcpForm([{ ...cfg, note: p.note }]); };
  A['mcp-edit'] = (d) => { const s = S.mcp.find((x) => x.id === d.id); mcpForm([{ name: s.name, type: s.type, command: s.command, args: s.args, url: s.url, env: s.env, headers: s.headers }], s.id); };
  function mcpForm(list, editing) {
    R.mx = list.map((c) => ({ ...c, args: [...(c.args || [])], env: { ...(c.env || {}) }, headers: { ...(c.headers || {}) } })); R.mxEdit = editing || null;
    const kv = (i, kind, o) => Object.entries(o).map(([k, v]) => `<div class="row2 kv"><input type="text" data-mxk="${i}.${kind}" value="${esc(k)}" placeholder="NAME"><input type="password" data-mxv="${i}.${kind}" value="${esc(v)}" placeholder="value" class="${PLACEHOLDER.test(v) ? 'warnb' : ''}"></div>`).join('');
    const body = R.mx.map((c, i) => `<div class="card mcpc"><div class="two"><div class="field"><label>Name</label><input type="text" data-mx="${i}.name" value="${esc(c.name)}"></div><div class="field"><label>How it connects</label><select data-mx="${i}.type"><option value="stdio" ${c.type === 'stdio' ? 'selected' : ''}>Runs on this PC (command)</option><option value="http" ${c.type === 'http' ? 'selected' : ''}>Online server (URL)</option></select></div></div>
      ${c.type === 'http' ? `<div class="field"><label>Server URL</label><input type="url" data-mx="${i}.url" value="${esc(c.url || '')}"></div><div class="field"><label>Headers (stored encrypted)</label>${kv(i, 'headers', c.headers)}<a class="more" data-act="mx-addkv" data-i="${i}" data-kind="headers">+ Add header</a></div>` : `<div class="two cmd"><div class="field"><label>Command</label><input type="text" data-mx="${i}.command" value="${esc(c.command || '')}"></div><div class="field"><label>Arguments (one per line)</label><textarea data-mx="${i}.args" rows="3" class="mono">${esc((c.args || []).join('\n'))}</textarea></div></div><div class="field"><label>Environment (keys — stored encrypted)</label>${kv(i, 'env', c.env)}<a class="more" data-act="mx-addkv" data-i="${i}" data-kind="env">+ Add variable</a></div>`}
      ${[...Object.values(c.env || {}), ...Object.values(c.headers || {})].some((v) => PLACEHOLDER.test(v) && v !== '••••••••') ? '<div class="note warn">Fill in the highlighted values (the config had placeholders like &lt;YOUR_TOKEN&gt;).</div>' : ''}${c.note ? `<div class="helpl">${esc(c.note)}</div>` : ''}</div>`).join('');
    modal(editing ? `Edit ${esc(list[0].name)}` : 'Add an app', `${body}${editing ? '' : `<div class="field"><label>Let this bot use it now</label><select id="mxBot"><option value="">Decide later</option>${active().map((b) => `<option value="${b.id}" ${b.id === R.bot ? 'selected' : ''}>${esc(b.name)}</option>`).join('')}</select></div>`}<div class="helpl">Only add servers you trust — they run with your permissions. Servers that need “Sign in with…” aren't supported yet; use ones that take a key.</div>`,
      `<button class="btn" data-act="close">Cancel</button><button class="btn pri" id="mxGo">${editing ? 'Save & reconnect' : 'Add & connect'}</button>`, 'xl');
    $('mxGo').onclick = async () => {
      mxRead();
      for (const c of R.mx) { if (c.type === 'http' && !/^https?:\/\//.test(c.url || '')) { toast('Add the server URL.', true); return; } if (c.type === 'stdio' && !c.command) { toast('Add the command.', true); return; } }
      $('mxGo').textContent = 'Connecting…'; $('mxGo').disabled = true;
      const pick = $('mxBot') ? $('mxBot').value : '';
      let last = null; for (const c of R.mx) last = editing ? await call('ws:mcp', { act: 'update', id: editing, ...c }) : await call('ws:mcp', { act: 'add', ...c, bot: pick || null });
      const snap = await api.invoke('ws:init'); S = snap; const s = typeof last === 'string' && snap.mcp.find((x) => x.id === last);
      openSettings('apps');
      if (s && s.status === 'connected') toast(`${s.name} connected — ${s.tools.length} actions.`); else if (s) toast(`${s.name}: ${s.error || 'couldn\'t connect'}`, true);
    };
  }
  function mxRead() {
    for (const el of $('layer').querySelectorAll('[data-mx]')) { const [i, f] = el.dataset.mx.split('.'); R.mx[i][f] = f === 'args' ? el.value.split('\n').map((x) => x.trim()).filter(Boolean) : el.value.trim(); }
    for (const c of R.mx) { c.env = {}; c.headers = {}; }
    const ks = [...$('layer').querySelectorAll('[data-mxk]')]; const vs = [...$('layer').querySelectorAll('[data-mxv]')];
    ks.forEach((k, j) => { const [i, kind] = k.dataset.mxk.split('.'); if (k.value.trim()) R.mx[i][kind][k.value.trim()] = vs[j].value; });
  }
  A['mx-addkv'] = (d) => { mxRead(); R.mx[+d.i][d.kind][`NEW_${Object.keys(R.mx[+d.i][d.kind]).length + 1}`] = ''; mcpForm(R.mx, R.mxEdit); };

  // ---------------------------------------------------------------- events
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]'); if (!el) return;
    if (!document.getElementById('app').contains(el) && !$('layer').contains(el)) return; // onboarding has its own handlers
    const f = A[el.dataset.act]; if (!f) return;
    if (el.dataset.act !== 'scrim') e.stopPropagation();
    f(el.dataset, { currentTarget: el, target: e.target });
  });
  document.addEventListener('change', (e) => {
    const el = e.target;
    if (el.dataset.actChange === 'mcp-risk') call('ws:mcp', { act: 'tool', id: el.dataset.id, tool: el.dataset.t, patch: { risk: el.value } });
  });
  document.addEventListener('input', (e) => {
    const el = e.target;
    if (el.id === 'composer') { drafts[R.bot] = el.value; grow(el); }
    if (el.id === 'crName' && R.cr) { R.cr.name = el.value; $('crGo').disabled = !el.value.trim(); const a = $('crAv').querySelector('.av'); const st = el.value ? 'need' : 'idle'; if (!a.classList.contains(st)) a.className = `av ${st}`; }
  });
  document.addEventListener('focusout', (e) => { if (e.target.id === 'nmIn') { const v = e.target.value.trim(); const b = bot(R.bot); if (v && b && v !== b.name) saveBot({ name: v }); } });
  document.addEventListener('keydown', (e) => {
    if ($('app').hidden) return;
    const t = e.target; const mod = e.ctrlKey || e.metaKey;
    if (t.id === 'composer' && e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (t.value.trim()) A.send(); else if ($('sendBtn').dataset.act === 'stop-chat') A['stop-chat'](); return; }
    if (t.id === 'crName' && e.key === 'Enter') { A['create-go'](); return; }
    if (t.id === 'nmIn' && e.key === 'Enter') { t.blur(); return; }
    if (t.id === 'steerIn' && e.key === 'Enter') { A.steer({ id: R.task }); return; }
    if (t.id?.startsWith('ans-') && e.key === 'Enter') { A['answer-text']({ id: t.id.slice(4) }); return; }
    // A/B/C… picks a lettered option on the newest question when you're not typing
    if (!mod && !e.altKey && /^[a-hA-H]$/.test(e.key) && !/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) && !$('layer').innerHTML) { const qs = [...document.querySelectorAll('#col .ask.q')]; const q = qs[qs.length - 1]; const b = q && q.querySelectorAll('.optb')[e.key.toUpperCase().charCodeAt(0) - 65]; if (b) { e.preventDefault(); b.click(); return; } }
    if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); openPalette(); return; }
    if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); A['new-bot'](); return; }
    if (mod && e.key === ',') { e.preventDefault(); if (e.shiftKey) A['toggle-panel'](); else openSettings('general'); return; }
    if (mod && e.key.toLowerCase() === 'i') { e.preventDefault(); $('composer')?.focus(); return; }
    if (mod && /^[1-9]$/.test(e.key)) { const el = [...document.querySelectorAll('.rrow')][+e.key - 1]; if (el) { e.preventDefault(); openBot(el.dataset.bot); } return; }
    if (e.altKey && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { const list = [...document.querySelectorAll('.rrow')].map((x) => x.dataset.bot); const i = list.indexOf(R.bot); const n = list[Math.max(0, Math.min(list.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)))]; if (n) { e.preventDefault(); openBot(n); } return; }
    if (e.key === 'Escape') { if ($('layer').innerHTML) closeLayer(); else if (R.view === 'create' && active().length) A['cancel-create'](); }
  });
  $('paletteOpen').addEventListener('click', () => openPalette());

  // ---------------------------------------------------------------- state
  async function refresh() { S = await api.invoke('ws:init'); render(); }
  api.on('ws:state', (s) => { S = s; if (!$('app').hidden) render(); });
  function openTarget(o) { if (!o || !S) return; if (o.taskId) { const t = task(o.taskId); if (t) openBot(t.botId); } else if (o.botId) openBot(o.botId); else if (o.view === 'approvals') A.approvals(); }
  api.on('ws:open', openTarget);
  function showApp() {
    $('app').hidden = false; $('brandMark').innerHTML = window.NB.logoSvg('#fff', '#5200F4');
    if (!R.bot) R.bot = (S.bots.find((b) => b.primary && !b.archived) || active()[0] || {}).id || null;
    render();
  }
  window.addEventListener('nb-intro-done', () => { api.invoke('ws:init').then((s) => { S = s; showApp(); }); });
  setInterval(() => { if (S && !$('app').hidden && !document.hidden) renderRoster(); }, 30000); // keep "5m" times fresh
  // Idle life: now and then one resting character blinks or glances. Short transitions instead of endless
  // keyframes, so a quiet window does no work between them.
  setInterval(() => {
    if (document.hidden || $('app').hidden || document.body.classList.contains('lite')) return;
    const all = [...document.querySelectorAll('.av.idle, .av.queue')];
    if (!all.length) return;
    for (let n = 0; n < Math.min(2, all.length); n++) {
      const el = all[Math.floor(Math.random() * all.length)];
      const r = el.getBoundingClientRect(); if (!r.width || r.bottom < 0 || r.top > innerHeight) continue;
      if (Math.random() < 0.6) { el.classList.add('bl'); setTimeout(() => el.classList.remove('bl'), 140); if (Math.random() < 0.25) setTimeout(() => { el.classList.add('bl'); setTimeout(() => el.classList.remove('bl'), 120); }, 260); }
      else { const c = Math.random() < 0.5 ? 'lk-l' : 'lk-r'; el.classList.add(c); setTimeout(() => el.classList.remove(c), 1400 + Math.random() * 1200); }
    }
  }, 1700);
  window.__nb = { get S() { return S; }, R, render };
  api.invoke('ws:init').then((s) => {
    S = s; applyTheme();
    if (!S.profile.onboarded) { window.NBOnboarding.start(S.profile.name || ''); return; }
    showApp();
    const q = new URLSearchParams(location.search).get('open');
    if (q) try { openTarget(JSON.parse(q)); } catch { /* ignore a bad link */ }
  });
})();
