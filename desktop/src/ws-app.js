'use strict';
// Nebula Workspace — the window. Everything shown comes from the main process's real state (ws:state); every
// button calls a real action there. Nothing here invents progress, statuses, ratings or connections.
(() => {
  const api = window.nebula;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const SHAPES = window.NB_SHAPES.chars;
  const PALETTE = ['#7A3CFF', '#5200F4', '#2F2562', '#CA1D4B', '#FD715F', '#FACB34', '#10AA6E', '#2DBFCD', '#E8E6F0'];
  let S = null; // the latest snapshot
  const R = { view: 'home', bot: null, tab: 'chat', cid: null, task: null, team: null, skill: null, f: {} };
  const draft = {}; // composer drafts per conversation
  let replyTo = null; let attach = [];

  // ---------------------------------------------------------------- icons
  const P = {
    home: '<path d="M3 11.5 12 4l9 7.5"/><path d="M5 10v10h14V10"/>', bots: '<rect x="4" y="7" width="16" height="12" rx="4"/><path d="M12 3v4M9 13h.01M15 13h.01"/>',
    teams: '<circle cx="9" cy="8" r="3.2"/><circle cx="17" cy="9.5" r="2.4"/><path d="M3.5 19c.6-3.2 3-5 5.5-5s4.9 1.8 5.5 5M14.5 15c2.8-.6 5.2.7 6 3.6"/>',
    tasks: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="m8 12 3 3 5-6"/>', activity: '<path d="M3 12h4l3 7 4-14 3 7h4"/>',
    routines: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>', skills: '<path d="M12 3 4 7l8 4 8-4-8-4z"/><path d="m4 12 8 4 8-4M4 17l8 4 8-4"/>',
    library: '<path d="M5 4h4v16H5zM10 4h4v16h-4z"/><path d="m15 5 3.8-1 3 15.6-3.8.9z"/>', market: '<path d="M4 9h16l-1 11H5z"/><path d="M8 9V7a4 4 0 0 1 8 0v2"/>',
    connect: '<path d="M9 15 15 9"/><path d="M11 6.5 13 4.5a4.2 4.2 0 0 1 6 6l-2 2M13 17.5l-2 2a4.2 4.2 0 0 1-6-6l2-2"/>', analytics: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>', send: '<path d="M5 12h14M13 6l6 6-6 6"/>', stop: '<rect x="7" y="7" width="10" height="10" rx="2"/>', mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
    clip: '<path d="m20 11-8.5 8.5a5 5 0 0 1-7-7L13 4a3.3 3.3 0 0 1 4.7 4.7L9.2 17.2a1.7 1.7 0 0 1-2.4-2.4L14.5 7"/>', bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 21h4"/>',
    more: '<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>', reply: '<path d="M10 8 4 13l6 5"/><path d="M4 13h9a7 7 0 0 1 7 7"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>', edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
    retry: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>', branch: '<circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="8" r="2"/><path d="M6 7v10M18 10c0 4-6 3-12 7"/>',
    smile: '<circle cx="12" cy="12" r="9"/><path d="M8.5 14.5a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01"/>', spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M18 6l-2.5 2.5M8.5 15.5 6 18"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>', pause: '<path d="M8 5v14M16 5v14"/>', play: '<path d="M7 5v14l12-7z"/>', file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
    pin: '<path d="M9 4h6l-1 6 4 3H6l4-3z"/><path d="M12 13v8"/>', computer: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>', search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  };
  const I = (n, s = '') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${s}>${P[n] || ''}</svg>`;

  // ---------------------------------------------------------------- small helpers
  const bot = (id) => S.bots.find((b) => b.id === id);
  const task = (id) => S.tasks.find((t) => t.id === id);
  const conv = (id) => S.conversations.find((c) => c.id === id);
  const live = (b) => ['working', 'thinking', 'research', 'writing', 'browsing', 'collab'].includes(b?.status?.key);
  function av(b, size = 32, dot = true) {
    if (!b) return `<span class="av" style="width:${size}px;height:${size}px"></span>`;
    return `<span class="av ${b.status ? b.status.key : ''} ${live(b) ? 'live' : ''}" style="width:${size}px;height:${size}px">${window.NB.charSvg(b.shape, b.color)}${dot ? '<i class="dot"></i>' : ''}</span>`;
  }
  const pill = (k, label) => `<span class="status ${esc(k)}">${esc(label)}</span>`;
  const TS = { IN_PROGRESS: 'Running', REQUIRES_INPUT: 'Waiting for you', COMPLETED: 'Completed', FAILED: 'Failed', CANCELLED: 'Cancelled', QUEUED: 'Queued', PAUSED: 'Paused' };
  function tStatus(t) {
    if (t.status === 'REQUIRES_INPUT' && t.input?.kind === 'approval') return pill('REQUIRES_INPUT', 'Awaiting approval');
    if (t.status === 'IN_PROGRESS' && !t.plan?.length && !t.steps?.length) return pill('IN_PROGRESS', 'Planning');
    if (t.status === 'PAUSED' && t.interrupted) return pill('PAUSED', 'Interrupted');
    if (t.status === 'IN_PROGRESS' && /Waiting for/.test(t.activity || '')) return pill('IN_PROGRESS', 'Delegated');
    return pill(t.status, TS[t.status] || t.status);
  }
  function ago(ts) {
    if (!ts) return '';
    const d = (Date.now() - ts) / 1000;
    if (d < 0) { const f = -d; return f < 3600 ? `in ${Math.max(1, Math.round(f / 60))} min` : f < 86400 ? `in ${Math.round(f / 3600)} h` : `in ${Math.round(f / 86400)} d`; }
    return d < 45 ? 'just now' : d < 3600 ? `${Math.round(d / 60)} min ago` : d < 86400 ? `${Math.round(d / 3600)} h ago` : d < 7 * 86400 ? `${Math.round(d / 86400)} d ago` : new Date(ts).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
  }
  const clock = (ts) => new Date(ts).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  const greet = () => { const h = new Date().getHours(); return h < 5 ? 'Up late' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'; };
  function toast(msg, bad = false) { const t = document.createElement('div'); t.className = `toast ${bad ? 'bad' : ''}`; t.textContent = msg; document.body.appendChild(t); setTimeout(() => t.remove(), bad ? 5200 : 2600); }
  async function call(ch, arg, okMsg) {
    const r = await api.invoke(ch, arg).catch((e) => ({ ok: false, error: e.message }));
    if (!r || r.ok === false) { toast(r?.error || 'That didn\'t work.', true); return undefined; }
    if (okMsg) toast(okMsg);
    return r.value;
  }
  const progress = (t) => { if (t.status === 'COMPLETED') return 100; if (!t.plan?.length) return null; const d = t.steps.filter((s) => s.status === 'DONE').length; return Math.min(100, Math.round(d / t.plan.length * 100)); };
  const fileExt = (f) => (f.ext || (f.fileName || '').split('.').pop() || '').slice(0, 4);

  // ---------------------------------------------------------------- markdown (safe: everything escaped first)
  const mdCache = new Map();
  function md(text) {
    const key = text; if (mdCache.has(key)) return mdCache.get(key);
    const blocks = []; let s = String(text || '');
    s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, (_m, lang, code) => { blocks.push(`<pre><code>${esc(code.replace(/\n$/, ''))}</code></pre>`); return `\u0000${blocks.length - 1}\u0000`; });
    s = s.replace(/\[Attached file: ([^\]]+)\]\n\u0000(\d+)\u0000/g, (_m, name, i) => { blocks[i] = `<details class="fchip"><summary><i>${esc(name.split('.').pop())}</i>${esc(name)}</summary>${blocks[i]}</details>`; return `\u0000${i}\u0000`; });
    const inline = (x) => esc(x).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a data-act="url" data-url="$2">$1</a>').replace(/(^|\s)(https?:\/\/[^\s<]+)/g, '$1<a data-act="url" data-url="$2">$2</a>');
    const out = []; const lines = s.split('\n'); let i = 0;
    while (i < lines.length) {
      const l = lines[i];
      if (/^\u0000\d+\u0000$/.test(l.trim())) { out.push(blocks[+l.trim().slice(1, -1)]); i++; continue; }
      if (/^#{1,4} /.test(l)) { const n = l.match(/^#+/)[0].length; out.push(`<h${n + 1}>${inline(l.replace(/^#+ /, ''))}</h${n + 1}>`); i++; continue; }
      if (/^\s*\|.*\|\s*$/.test(l) && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1] || '')) {
        const cells = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
        let h = `<table><tr>${cells(l).map((c) => `<th>${inline(c)}</th>`).join('')}</tr>`; i += 2;
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { h += `<tr>${cells(lines[i]).map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`; i++; }
        out.push(`${h}</table>`); continue;
      }
      if (/^\s*([-*•]|\d+[.)])\s+/.test(l)) {
        const ordered = /^\s*\d/.test(l); let h = '';
        while (i < lines.length && /^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) { h += `<li>${inline(lines[i].replace(/^\s*([-*•]|\d+[.)])\s+/, ''))}</li>`; i++; }
        out.push(ordered ? `<ol>${h}</ol>` : `<ul>${h}</ul>`); continue;
      }
      if (/^>\s?/.test(l)) { let q = ''; while (i < lines.length && /^>\s?/.test(lines[i])) { q += `${lines[i].replace(/^>\s?/, '')} `; i++; } out.push(`<div class="quote">${inline(q)}</div>`); continue; }
      if (!l.trim()) { i++; continue; }
      let p = l; i++;
      while (i < lines.length && lines[i].trim() && !/^(#{1,4} |\s*([-*•]|\d+[.)])\s+|\s*\||>|\u0000)/.test(lines[i])) { p += `\n${lines[i]}`; i++; }
      out.push(`<p>${inline(p).replace(/\n/g, '<br>')}</p>`);
    }
    const html = out.join('').replace(/\u0000(\d+)\u0000/g, (_m, n) => blocks[n]);
    if (mdCache.size > 800) mdCache.clear();
    mdCache.set(key, html); return html;
  }

  // ---------------------------------------------------------------- overlays
  function closeLayer() { $('layer').innerHTML = ''; }
  function modal(title, body, foot = '', cls = '') {
    $('layer').innerHTML = `<div class="scrim" data-act="scrim"><div class="modal ${cls}" role="dialog"><div class="mhead"><b>${title}</b><button class="ico" data-act="close">${I('x')}</button></div>${body.startsWith('<div class="wiz"') ? body : `<div class="mbody">${body}</div>`}${foot ? `<div class="mfoot">${foot}</div>` : ''}</div></div>`;
    const f = $('layer').querySelector('input,textarea'); if (f) setTimeout(() => f.focus(), 40);
  }
  function drawer(html) { $('layer').innerHTML = `<div class="scrim" data-act="scrim" style="background:rgba(4,3,8,.35)"></div><aside class="drawer">${html}</aside>`; }
  function menu(ev, items) {
    const r = ev.currentTarget ? ev.currentTarget.getBoundingClientRect() : { left: ev.clientX, bottom: ev.clientY, right: ev.clientX };
    const el = document.createElement('div'); el.className = 'menu';
    el.innerHTML = items.map((it) => (it === '-' ? '<hr>' : `<button class="${it.danger ? 'danger' : ''}">${esc(it.label)}</button>`)).join('');
    document.body.appendChild(el);
    const w = el.offsetWidth; el.style.left = `${Math.min(innerWidth - w - 10, Math.max(10, r.right - w))}px`; el.style.top = `${Math.min(innerHeight - el.offsetHeight - 10, r.bottom + 6)}px`;
    const btns = [...el.querySelectorAll('button')]; let k = 0;
    items.forEach((it) => { if (it !== '-') { const b = btns[k++]; b.onclick = () => { el.remove(); it.fn(); }; } });
    setTimeout(() => document.addEventListener('mousedown', function off(e) { if (!el.contains(e.target)) { el.remove(); document.removeEventListener('mousedown', off); } }), 0);
  }
  function confirmBox(title, text, yes, fn, danger = true) {
    modal(title, `<p style="margin:0;color:var(--dim)">${text}</p>`, `<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn ${danger ? 'danger' : 'pri'}" id="cfYes">${esc(yes)}</button>`);
    $('cfYes').onclick = () => { closeLayer(); fn(); };
  }

  // ---------------------------------------------------------------- navigation + frame
  const NAV = [['home', 'Home'], ['bots', 'My Bots'], ['teams', 'Teams'], ['tasks', 'Tasks'], ['activity', 'Activity'], ['routines', 'Routines'], ['skills', 'Skills'], ['library', 'Library'], ['market', 'Marketplace'], ['connect', 'Connect Apps'], ['analytics', 'Analytics'], ['settings', 'Settings']];
  function go(view, extra = {}) {
    Object.assign(R, { view, ...extra });
    if (view !== 'bot') { R.bot = extra.bot || null; }
    $('scroll').scrollTop = 0;
    render(true);
  }
  function openBot(id, tab = 'chat', cid = null) {
    const b = bot(id); if (!b) return;
    const cs = S.conversations.filter((c) => c.botId === id && !c.archived && !c.taskId).sort((a, b2) => (b2.pinned - a.pinned) || b2.updatedAt - a.updatedAt);
    Object.assign(R, { view: 'bot', bot: id, tab, cid: cid || (tab === 'chat' ? (cs[0]?.id || null) : R.cid) });
    $('scroll').scrollTop = 0; render(true);
  }

  function frame() {
    const needs = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT').length;
    $('nav').innerHTML = NAV.map(([k, l]) => `<button class="${R.view === k || (k === 'bots' && R.view === 'bot') ? 'on' : ''}" data-act="nav" data-v="${k}" title="${l}">${I({ bots: 'bots', market: 'market', connect: 'connect' }[k] || k)}<span>${l}</span>${k === 'tasks' && needs ? `<i class="ct">${needs}</i>` : ''}</button>`).join('');
    const prim = S.bots.find((b) => b.primary && !b.archived);
    const pinned = S.pins.filter((p) => p.kind === 'bot').map((p) => bot(p.id)).filter((b) => b && !b.archived && b !== prim);
    const recent = S.conversations.filter((c) => c.messages.length && !c.archived && !c.taskId).sort((a, b) => (b.pinned - a.pinned) || b.updatedAt - a.updatedAt).slice(0, 8);
    $('sideList').innerHTML = `${prim ? `<div class="lbl">Chief of Staff</div><button class="mini" data-act="bot" data-id="${prim.id}">${av(prim, 24)}<span>${esc(prim.name)}</span></button>` : ''}
      ${pinned.length ? `<div class="lbl">Pinned</div>${pinned.map((b) => `<button class="mini" data-act="bot" data-id="${b.id}">${av(b, 24)}<span>${esc(b.name)}</span></button>`).join('')}` : ''}
      <div class="lbl">My Bots</div>${S.bots.filter((b) => !b.archived && !b.primary && !pinned.includes(b)).slice(0, 8).map((b) => `<button class="mini" data-act="bot" data-id="${b.id}">${av(b, 24)}<span>${esc(b.name)}</span></button>`).join('') || '<div class="helpline" style="padding:0 10px">No bots yet</div>'}
      ${recent.length ? `<div class="lbl">Recent chats</div>${recent.map((c) => `<button class="mini" data-act="chat" data-cid="${c.id}">${av(bot(c.botId), 20, false)}<span>${esc(c.title)}</span></button>`).join('')}` : ''}`;
    const name = S.profile.name || 'You';
    $('who').innerHTML = `<i>${esc(name.slice(0, 1).toUpperCase())}</i><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(name)}</span>`;
    const appr = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT' && t.input?.kind === 'approval').length;
    $('topActs').innerHTML = `${appr ? `<button class="pillbtn warn" data-act="approvals">${I('bell')}${appr} waiting for approval</button>` : ''}<button class="pillbtn" data-act="voice" title="Talk to Nebula">${I('mic')}Voice</button><button class="pillbtn pri" data-act="assign">${I('plus')}Assign a task</button>`;
  }

  // Re-render without losing what you're typing, where you've scrolled, or an open dropdown.
  let rq = 0;
  function render(force) {
    if (!S) return;
    const ae = document.activeElement;
    if (!force && ae && $('view').contains(ae) && (ae.tagName === 'SELECT' || (/^(INPUT|TEXTAREA)$/.test(ae.tagName) && !ae.dataset.keep))) { clearTimeout(render.t); render.t = setTimeout(() => render(), 500); return; }
    cancelAnimationFrame(rq);
    rq = requestAnimationFrame(() => {
      frame();
      const keep = {};
      for (const el of $('view').querySelectorAll('[data-keep]')) keep[el.dataset.keep] = { v: el.value, s: el.selectionStart, e: el.selectionEnd, f: document.activeElement === el, st: el.scrollTop };
      const sc = {}; for (const el of $('view').querySelectorAll('[data-keep-scroll]')) sc[el.dataset.keepScroll] = { t: el.scrollTop, bottom: el.scrollHeight - el.scrollTop - el.clientHeight < 40 };
      const [title, html] = VIEWS[R.view] ? VIEWS[R.view]() : ['', ''];
      $('title').innerHTML = title;
      $('view').innerHTML = html;
      for (const el of $('view').querySelectorAll('[data-keep]')) { const k = keep[el.dataset.keep]; if (k && !force) { el.value = k.v; if (k.f) { el.focus(); try { el.setSelectionRange(k.s, k.e); } catch { /* */ } } } else if (k && force && el.dataset.keep.startsWith('draft:')) el.value = k.v; }
      for (const el of $('view').querySelectorAll('[data-keep-scroll]')) { const k = sc[el.dataset.keepScroll]; if (!k || k.bottom || force) el.scrollTop = el.scrollHeight; else el.scrollTop = k.t; }
      for (const el of $('view').querySelectorAll('textarea[data-grow]')) grow(el);
      if (R.view === 'bot' && R.tab === 'chat') { const c = $('composer'); if (c && draft[R.cid] && !c.value) { c.value = draft[R.cid]; grow(c); } }
    });
  }
  function grow(el) { el.style.height = 'auto'; el.style.height = `${Math.min(180, el.scrollHeight)}px`; }

  // ---------------------------------------------------------------- views
  const VIEWS = {};

  VIEWS.home = () => {
    const name = S.profile.name || '';
    const working = S.bots.filter(live);
    const appr = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT');
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const doneToday = S.tasks.filter((t) => t.status === 'COMPLETED' && (t.finishedAt || t.updatedAt) >= today.getTime());
    const nextR = S.routines.filter((r) => r.active && r.nextRun).sort((a, b) => a.nextRun - b.nextRun)[0];
    const prim = S.bots.find((b) => b.primary && !b.archived) || S.bots.find((b) => !b.archived && !b.draft);
    const running = S.tasks.filter((t) => ['IN_PROGRESS', 'QUEUED', 'PAUSED'].includes(t.status) && !t.parentId);
    const done = S.tasks.filter((t) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(t.status) && !t.parentId).sort((a, b) => (b.finishedAt || b.updatedAt) - (a.finishedAt || a.updatedAt)).slice(0, 6);
    const fails24 = S.tasks.filter((t) => t.status === 'FAILED' && Date.now() - (t.finishedAt || t.updatedAt) < 86400000).length;
    const html = `
      <div class="hero"><div><h2>${greet()}${name ? `, <em>${esc(name)}</em>` : ''}</h2><p>${S.bots.filter((b) => !b.archived).length ? `${working.length ? `${working.length} bot${working.length > 1 ? 's' : ''} working right now` : 'All your bots are idle'}${appr.length ? ` · ${appr.length} waiting on you` : ''}.` : 'Your AI workforce starts here. Create a bot, give it a job, and it works while you\'re away.'}</p></div></div>
      <div class="chips" style="margin-bottom:18px">
        ${[['create', 'plus', 'Create a bot'], ['assign', 'tasks', 'Assign a task'], ['team-new', 'teams', 'Create a team'], ['routine-new', 'routines', 'Schedule a routine'], ['skill-new', 'skills', 'Teach a skill'], ['nav:connect', 'connect', 'Connect an app'], ['voice', 'mic', 'Start voice'], ['approvals', 'bell', 'Review approvals'], ['computer', 'computer', 'Open a bot\'s computer'], ['nav:tasks', 'activity', 'Task history']].map(([a, i, l]) => `<button class="qa" data-act="${a}">${I(i)}${l}</button>`).join('')}
      </div>
      <div class="grid g2" style="margin-bottom:14px">
        ${prim ? `<div class="card primary">${av(prim, 84)}<div style="min-width:0">
            <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap"><h4>${esc(prim.name)}</h4>${pill(prim.status.key, prim.status.label)}${prim.primary ? '<span class="tag">Chief of Staff</span>' : ''}</div>
            <div class="meta">${esc(prim.role)}${prim.current ? ` · on “${esc(prim.current.title)}”` : prim.status.detail ? ` · ${esc(prim.status.detail)}` : ''}</div>
            <div class="ask"><input data-keep="homeAsk" id="homeAsk" placeholder="Give ${esc(prim.name)} a task, or ask anything…" autocomplete="off"><button class="pillbtn" data-act="home-chat" title="Chat instead">${I('reply')}Chat</button><button class="pillbtn pri" data-act="home-task">${I('send')}Assign</button></div>
            <div class="helpline" style="margin-top:8px">Assign = a tracked task that runs in the background. Chat = a conversation (it becomes a task if it needs tools).</div>
          </div></div>`
          : `<div class="card primary">${`<span class="av" style="width:84px;height:84px">${window.NB.charSvg(0, '#7A3CFF')}</span>`}<div><h4>No bots yet</h4><div class="meta">Create one from scratch or add a ready-made one.</div><div class="chips" style="margin-top:14px"><button class="pillbtn pri" data-act="create">${I('plus')}Create a bot</button><button class="pillbtn" data-act="nav" data-v="market">Browse the Marketplace</button></div></div></div>`}
        <div class="card"><h3>Needs you<span class="sp"></span>${appr.length ? `<a data-act="approvals">Review all</a>` : ''}</h3>${appr.length ? `<div class="rows">${appr.slice(0, 4).map(taskRow).join('')}</div>` : '<div class="empty">Nothing waiting. When a bot needs an approval or an answer, it shows up here.</div>'}</div>
      </div>
      <div class="grid g4" style="margin-bottom:14px">
        <div class="card stat"><span>Working now</span><b>${working.length}</b><small>${S.tasks.filter((t) => t.status === 'QUEUED').length} queued</small></div>
        <div class="card stat"><span>Waiting for you</span><b style="${appr.length ? 'color:var(--warn)' : ''}">${appr.length}</b><small>${appr.filter((t) => t.input?.kind === 'approval').length} approvals</small></div>
        <div class="card stat"><span>Completed today</span><b>${doneToday.length}</b><small>${S.tasks.filter((t) => t.status === 'COMPLETED').length} all time</small></div>
        <div class="card stat"><span>Next routine</span><b style="font-size:20px;line-height:1.2">${nextR ? esc(ago(nextR.nextRun)) : '—'}</b><small>${nextR ? esc(nextR.name) : 'None scheduled'}</small></div>
      </div>
      <div class="grid g2" style="margin-bottom:14px">
        <div class="card"><h3>In progress<span class="sp"></span><a data-act="nav" data-v="tasks">All tasks</a></h3>${running.length ? `<div class="rows">${running.slice(0, 6).map(taskRow).join('')}</div>` : '<div class="empty">No work running. Assign a task and it appears here with its real progress.</div>'}</div>
        <div class="card"><h3>Suggested<span class="sp"></span><a data-act="nav" data-v="settings">Proactivity: ${esc(S.settings.proactivity)}</a></h3>${S.suggestions.length ? S.suggestions.map((g) => `<div class="sugg">${I('spark')}<div>${esc(g.text)}</div><div class="acts"><button class="pillbtn" data-act="sugg" data-key="${esc(g.key)}">Do it</button><button class="ico" data-act="dismiss" data-key="${esc(g.key)}" title="Dismiss">${I('x')}</button></div></div>`).join('') : `<div class="empty">${S.settings.proactivity === 'off' ? 'Suggestions are off.' : 'Nothing to suggest right now.'}</div>`}</div>
      </div>
      <div class="grid g3" style="margin-bottom:14px">
        <div class="card"><h3>Recently finished</h3>${done.length ? `<div class="rows">${done.map(taskRow).join('')}</div>` : '<div class="empty">Finished work will be listed here.</div>'}</div>
        <div class="card"><h3>Recent activity<span class="sp"></span><a data-act="nav" data-v="activity">Activity</a></h3>${S.activity.length ? `<div class="tl">${S.activity.slice(0, 7).map(evRow).join('')}</div>` : '<div class="empty">Every action your bots take is logged here.</div>'}</div>
        <div class="card"><h3>Upcoming routines<span class="sp"></span><a data-act="routine-new">New</a></h3>${S.routines.filter((r) => r.active && r.nextRun).length ? `<div class="rows">${S.routines.filter((r) => r.active && r.nextRun).sort((a, b) => a.nextRun - b.nextRun).slice(0, 5).map((r) => `<div class="row" data-act="nav" data-v="routines">${av(bot(r.botId), 30)}<div class="tx"><b>${esc(r.name)}</b><small>${esc(r.when)}</small></div><span class="when">${esc(ago(r.nextRun))}</span></div>`).join('')}</div>` : '<div class="empty">No routines yet. Example: “Every weekday at 7:00, prepare my briefing.”</div>'}</div>
      </div>
      <div class="grid g3">
        <div class="card"><h3>Your bots<span class="sp"></span><a data-act="nav" data-v="bots">All</a></h3>${S.bots.filter((b) => !b.archived).length ? `<div class="rows">${S.bots.filter((b) => !b.archived).slice(0, 6).map((b) => `<div class="row" data-act="bot" data-id="${b.id}">${av(b, 30)}<div class="tx"><b>${esc(b.name)}</b><small>${esc(b.current ? b.current.title : b.role)}</small></div>${pill(b.status.key, b.status.label)}</div>`).join('')}</div>` : '<div class="empty">No bots yet.</div>'}</div>
        <div class="card"><h3>Recent files</h3>${S.files.length ? `<div class="rows">${S.files.slice(0, 5).map(fileRow).join('')}</div>` : '<div class="empty">Documents, spreadsheets and decks your bots make show up here.</div>'}</div>
        <div class="card"><h3>Agent health</h3>
          <div class="kv"><span>AI connection</span><b style="color:${S.keyOk ? 'var(--ok)' : 'var(--bad)'}">${S.keyOk ? 'Key set' : 'No AI key — add one in Nebula settings'}</b></div>
          <div class="kv"><span>Failed in the last 24 h</span><b style="color:${fails24 ? 'var(--bad)' : 'var(--text)'}">${fails24}</b></div>
          <div class="kv"><span>Routines active</span><b>${S.routines.filter((r) => r.active).length}</b></div>
          <div class="kv"><span>Connected apps</span><b>${S.catalogue.capabilities.length} built in · <a class="linkbtn" data-act="nav" data-v="connect">see all</a></b></div>
          <div class="kv" style="border:0"><span>Runs while</span><b>Nebula is open (or in the tray)</b></div>
        </div>
      </div>`;
    return ['Home', html];
  };

  const plain = (s) => String(s || '').replace(/```[\s\S]*?```/g, ' ').replace(/\|?\s*:?-{2,}:?\s*/g, ' ').replace(/[|#*`>_]+/g, ' ').replace(/\s+/g, ' ').trim();
  function taskRow(t) {
    const b = bot(t.botId); const p = progress(t);
    return `<div class="row" data-act="task" data-id="${t.id}">${av(b, 30)}<div class="tx"><b>${esc(t.title)}</b><small>${esc(b ? b.name : 'Removed bot')} · ${esc(plain(t.activity || (t.status === 'REQUIRES_INPUT' ? t.input?.question : t.summary) || TS[t.status] || ''))}</small>${p != null && t.status === 'IN_PROGRESS' ? `<div class="prog"><i style="width:${p}%"></i></div>` : ''}</div>${tStatus(t)}</div>`;
  }
  function fileRow(f) { return `<div class="row" data-act="file" data-id="${f.id}"><span class="av" style="width:30px;height:30px;display:grid;place-items:center;border-radius:8px;background:rgba(139,92,255,.16);color:var(--accent2);font-size:10px;font-weight:800;text-transform:uppercase">${esc(fileExt(f))}</span><div class="tx"><b>${esc(f.title || f.fileName)}</b><small>${esc(f.fileName)} · ${ago(f.createdAt)}</small></div></div>`; }
  const KIND = { task: 'Task', step: 'Action', error: 'Error', approval: 'Approval', approved: 'Approved', denied: 'Denied', done: 'Done', failed: 'Failed', cancelled: 'Stopped', waiting: 'Question', paused: 'Paused', resumed: 'Resumed', knowledge: 'Knowledge', memory: 'Memory', bot: 'Bot', edit: 'Settings', routine: 'Routine', skill: 'Skill', team: 'Team', steer: 'Instruction' };
  function evRow(e) { const b = bot(e.botId); return `<div class="ev ${e.taskId ? 'link' : ''}" ${e.taskId ? `data-act="task" data-id="${e.taskId}"` : ''}><time>${clock(e.at)}</time>${b ? av(b, 24, false) : '<span class="av" style="width:24px;height:24px"></span>'}<div><span class="k">${KIND[e.kind] || e.kind}</span>${esc(e.text)}</div></div>`; }

  VIEWS.bots = () => {
    const f = R.f.bots || 'active';
    const list = S.bots.filter((b) => (f === 'archived' ? b.archived : f === 'drafts' ? b.draft && !b.archived : !b.archived && !b.draft));
    return ['My Bots', `<div class="toolbar"><div class="seg">${[['active', 'Active'], ['drafts', 'Drafts'], ['archived', 'Archived']].map(([k, l]) => `<button class="${f === k ? 'on' : ''}" data-act="filter" data-k="bots" data-v="${k}">${l} · ${S.bots.filter((b) => (k === 'archived' ? b.archived : k === 'drafts' ? b.draft && !b.archived : !b.archived && !b.draft)).length}</button>`).join('')}</div><span style="flex:1"></span><button class="pillbtn" data-act="import-bot">Import</button><button class="pillbtn" data-act="nav" data-v="market">Marketplace</button><button class="pillbtn pri" data-act="create">${I('plus')}Create a bot</button></div>
      <div class="bots">${list.map((b) => `<div class="bot" style="--c:${b.color}" data-act="${b.draft ? 'edit-draft' : 'bot'}" data-id="${b.id}">${b.primary ? '<span class="crown">Chief of Staff</span>' : ''}${av(b, 58)}<div><h4>${esc(b.name)}</h4></div><div class="role">${esc(b.role)}</div><div>${pill(b.status.key, b.status.label)}</div><div class="cur">${b.current ? `On: ${esc(b.current.title)}` : esc(b.description || '')}</div><div class="nums"><span>${b.counts.done} done</span><span>${b.counts.open} open</span>${b.counts.failed ? `<span style="color:var(--bad)">${b.counts.failed} failed</span>` : ''}</div></div>`).join('')}${f === 'active' ? `<button class="bot add" data-act="create">${I('plus')}Create a bot</button>` : ''}</div>
      ${!list.length && f !== 'active' ? '<div class="empty">Nothing here.</div>' : ''}`];
  };

  // ---------------------------------------------------------------- a bot's workspace
  const TABS = [['chat', 'Chat'], ['tasks', 'Tasks'], ['computer', 'Computer'], ['files', 'Files'], ['skills', 'Skills'], ['routines', 'Routines'], ['knowledge', 'Knowledge'], ['memory', 'Memory'], ['activity', 'Activity'], ['settings', 'Settings']];
  VIEWS.bot = () => {
    const b = bot(R.bot); if (!b) { R.view = 'bots'; return VIEWS.bots(); }
    const head = `<div class="bhead">${av(b, 66)}<div style="min-width:0"><h2>${esc(b.name)}${b.primary ? '<span class="tag">Chief of Staff</span>' : ''}</h2><div class="sub">${esc(b.role)} ${pill(b.status.key, b.status.label)}${b.current ? `<span>on “${esc(b.current.title)}”</span>` : ''}<span class="helpline">@${esc(b.handle)} · ${esc(S.catalogue.autonomy[b.autonomy]?.label || '')}</span></div></div>
      <div class="acts"><button class="pillbtn pri" data-act="assign" data-bot="${b.id}">${I('plus')}Assign task</button>${b.archived ? `<button class="pillbtn" data-act="bot-act" data-a="unarchive" data-id="${b.id}">Restore</button>` : b.paused ? `<button class="pillbtn" data-act="bot-act" data-a="resume" data-id="${b.id}">${I('play')}Resume</button>` : `<button class="pillbtn" data-act="bot-act" data-a="pause" data-id="${b.id}">${I('pause')}Pause</button>`}<button class="ico" data-act="bot-menu" data-id="${b.id}">${I('more')}</button></div></div>
      <div class="tabs">${TABS.map(([k, l]) => `<button class="${R.tab === k ? 'on' : ''}" data-act="tab" data-t="${k}">${l}${k === 'knowledge' ? ` · ${b.knowledge.length}` : k === 'memory' ? ` · ${b.memories.length}` : ''}</button>`).join('')}</div>`;
    return [`<span class="crumb">My Bots /</span> ${esc(b.name)}`, head + (BOTTABS[R.tab] || BOTTABS.chat)(b)];
  };
  const BOTTABS = {};

  BOTTABS.chat = (b) => {
    const q = (R.f.chatq || '').toLowerCase();
    const cs = S.conversations.filter((c) => c.botId === b.id && !c.taskId && (R.f.chatArch ? c.archived : !c.archived) && (!q || c.title.toLowerCase().includes(q) || c.messages.some((m) => m.text.toLowerCase().includes(q)))).sort((a, c2) => (c2.pinned - a.pinned) || c2.updatedAt - a.updatedAt);
    const c = conv(R.cid);
    const active = c && S.tasks.find((t) => t.conversationId === c.id && ['IN_PROGRESS', 'REQUIRES_INPUT'].includes(t.status));
    const busy = c && (active?.status === 'IN_PROGRESS' || runningChat(c));
    const msgs = c ? c.messages : [];
    return `<div class="chat">
      <div class="clist"><button class="pillbtn pri" data-act="new-chat" data-id="${b.id}" style="justify-content:center">${I('plus')}New chat</button>
        <input type="search" placeholder="Search chats" data-keep="chatq" id="chatq" value="${esc(R.f.chatq || '')}">
        <div class="items">${cs.map((x) => `<button class="citem ${x.id === R.cid ? 'on' : ''}" data-act="pick-chat" data-cid="${x.id}">${x.pinned ? `<span class="pin">${I('pin', 'width="12" height="12"')}</span>` : ''}<span>${esc(x.title)}</span><small class="helpline">${ago(x.updatedAt)}</small></button>`).join('') || `<div class="empty">${q ? 'No chats match.' : 'No chats yet.'}</div>`}</div>
        <button class="linkbtn" style="text-align:left;padding:4px" data-act="filter" data-k="chatArch" data-v="${R.f.chatArch ? '' : '1'}">${R.f.chatArch ? '← Back to chats' : 'Archived chats'}</button></div>
      <div class="cpane">
        <div class="chead">${av(b, 26)}<b>${esc(c ? c.title : `New chat with ${b.name}`)}</b>${c ? `<button class="ico" data-act="conv-menu" data-cid="${c.id}">${I('more')}</button>` : ''}</div>
        <div class="msgs" data-keep-scroll="msgs-${R.cid}" id="msgs">
          ${!msgs.length ? `<div style="margin:auto;text-align:center;max-width:420px;color:var(--dim)">${av(b, 64, false)}<p style="font-size:15px;margin:14px 0 6px;color:var(--text);font-weight:700">${esc(b.welcome || `Hi, I'm ${b.name}.`)}</p><p style="margin:0 0 14px">${esc(b.description || '')}</p><div class="chips" style="justify-content:center">${starters(b).map((s) => `<button class="qa" data-act="starter" data-t="${esc(s)}">${esc(s)}</button>`).join('')}</div></div>` : ''}
          ${msgs.map((m) => msgHtml(m, b, c)).join('')}
          ${c ? inlineCards(c) : ''}
          ${busy ? `<div class="typing"><i></i>${esc(activityFor(c) || `${b.name} is working…`)}</div>` : ''}
        </div>
        <div class="composer">
          ${replyTo ? `<div class="replying">${I('reply', 'width="14" height="14"')}Replying to: <span style="flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${esc(replyTo.text.slice(0, 120))}</span><button class="ico" data-act="reply-x" style="width:24px;height:24px">${I('x')}</button></div>` : ''}
          ${attach.length ? `<div class="atts">${attach.map((a, i) => `<span class="fchip"><i>${esc(a.name.split('.').pop())}</i>${esc(a.name)}<button data-act="att-x" data-i="${i}">${I('x', 'width="12" height="12"')}</button></span>`).join('')}</div>` : ''}
          <div class="box"><button class="ico" data-act="attach" title="Attach text files">${I('clip')}</button><textarea id="composer" data-grow data-keep="draft:${R.cid}" rows="1" placeholder="Message ${esc(b.name)}…  (Enter to send, Shift+Enter for a new line)"></textarea>
          ${busy ? `<button class="sendb stop" data-act="stop-chat" title="Stop">${I('stop')}</button>` : ''}<button class="sendb" data-act="send" title="Send">${I('send')}</button></div>
        </div>
      </div></div>`;
  };
  function runningChat(c) { return S.tasks.some((t) => t.conversationId === c.id && t.status === 'IN_PROGRESS') || !!activityFor(c); }
  function activityFor(c) { return (S.live || {})[c.id] || null; }
  function starters(b) { const t = S.catalogue.templates.find((x) => x.key === b.template); return (t?.samples || ['What can you do?', 'Plan my week', 'Research a topic for me']).slice(0, 3); }
  function msgHtml(m, b, c) {
    const me = m.role === 'user'; const err = m.role === 'error';
    let text = m.text || ''; let quote = '';
    if (m.replyTo) { const lines = text.split('\n'); let i = 0; while (i < lines.length && lines[i].startsWith('>')) i++; quote = lines.slice(0, i).join(' ').replace(/^>\s?/gm, ''); text = lines.slice(i).join('\n').trim(); }
    const long = text.length > 1800;
    const t = m.taskId && task(m.taskId);
    const files = (m.fileIds || []).map((id) => S.files.find((f) => f.id === id)).filter(Boolean);
    return `<div class="msg ${me ? 'me' : ''} ${err ? 'err' : ''}" id="m-${m.id}">${me ? '' : av(b, 28, false)}<div style="min-width:0">
      ${quote ? `<div class="quote" data-act="jump" data-mid="${m.replyTo}">${esc(quote.slice(0, 160))}</div>` : ''}
      <div class="bub ${long && !R.f[`x${m.id}`] ? 'long' : ''}">${md(text)}
      ${files.map((f) => `<span class="fchip" data-act="file" data-id="${f.id}"><i>${esc(fileExt(f))}</i>${esc(f.fileName)}</span>`).join('')}
      ${(m.sources || []).length ? `<div class="srcs">${m.sources.slice(0, 8).map((s) => `<a data-act="url" data-url="${esc(s.url)}" title="${esc(s.url)}">${esc(s.title || s.url)}</a>`).join('')}</div>` : ''}
      ${t && !me ? `<div class="tcard" data-act="task" data-id="${t.id}"><div style="display:flex;gap:8px;align-items:center"><b style="flex:1">${esc(t.title)}</b>${tStatus(t)}</div><div class="helpline">${t.steps.length} step${t.steps.length === 1 ? '' : 's'} · open the task for the full log</div></div>` : ''}</div>
      ${long ? `<button class="more" data-act="expand" data-mid="${m.id}">${R.f[`x${m.id}`] ? 'Show less' : 'Show more'}</button>` : ''}
      ${m.reactions && Object.keys(m.reactions).length ? `<div class="reacts">${Object.keys(m.reactions).map((e) => `<button data-act="react" data-mid="${m.id}" data-e="${e}">${e}</button>`).join('')}</div>` : ''}
      <div class="meta2">${clock(m.at)}</div></div>
      <div class="mtools"><button data-act="react-pick" data-mid="${m.id}" title="React">${I('smile')}</button><button data-act="reply" data-mid="${m.id}" title="Reply">${I('reply')}</button><button data-act="copy" data-mid="${m.id}" title="Copy">${I('copy')}</button>${me ? `<button data-act="edit-msg" data-mid="${m.id}" title="Edit and resend">${I('edit')}</button>` : `<button data-act="retry-msg" data-mid="${m.id}" title="Retry">${I('retry')}</button>`}<button data-act="branch" data-mid="${m.id}" title="Branch from here">${I('branch')}</button></div></div>`;
  }
  function inlineCards(c) {
    const t = S.tasks.find((x) => x.conversationId === c.id && x.status === 'REQUIRES_INPUT' && x.input);
    return t ? `<div style="max-width:86%">${inputCard(t)}</div>` : '';
  }
  function inputCard(t) {
    const b = bot(t.botId); const inp = t.input;
    if (inp.kind === 'approval') {
      const k = inp.card;
      return `<div class="appr"><div class="t">${av(b, 26, false)}<b>${esc(b?.name || 'A bot')} wants to: ${esc(k.title)}</b><span class="risk ${k.risk}">${k.risk} risk</span></div>
        <dl>${k.why ? `<dt>Why</dt><dd>${esc(k.why)}</dd>` : ''}<dt>App</dt><dd>${esc(k.app)}</dd>${k.destination ? `<dt>Destination</dt><dd>${esc(k.destination)}</dd>` : ''}${k.content ? `<dt>Content</dt><dd><pre>${esc(k.content)}</pre></dd>` : ''}<dt>What happens</dt><dd>${esc(k.consequence)}</dd><dt>Reversible</dt><dd>${k.reversible ? 'Yes' : '<b style="color:var(--bad)">No</b>'}</dd><dt>Shared</dt><dd>${esc(k.shares)}</dd><dt>Task</dt><dd><a class="linkbtn" data-act="task" data-id="${t.id}">${esc(t.title)}</a></dd></dl>
        <div class="btns"><button class="pillbtn pri" data-act="answer" data-id="${t.id}" data-c="Approve">Approve once</button>${k.always ? `<button class="pillbtn" data-act="answer" data-id="${t.id}" data-c="Always allow" title="Only this action, only for ${esc(b?.name || 'this bot')}">Always allow</button>` : ''}<button class="pillbtn" data-act="edit-approval" data-id="${t.id}">Edit</button><button class="pillbtn danger" data-act="answer" data-id="${t.id}" data-c="Deny">Deny</button><button class="pillbtn" data-act="task-act" data-a="cancel" data-id="${t.id}">Cancel task</button></div></div>`;
    }
    return `<div class="appr" style="border-color:rgba(139,92,255,.35);background:rgba(139,92,255,.07)"><div class="t">${av(b, 26, false)}<b>${esc(inp.question)}</b></div>
      ${inp.options?.length ? `<div class="btns">${inp.options.map((o) => `<button class="pillbtn" data-act="answer" data-id="${t.id}" data-c="${esc(o)}">${esc(o)}</button>`).join('')}</div>` : ''}
      <div class="ask" style="margin-top:10px"><input id="ans-${t.id}" data-keep="ans-${t.id}" placeholder="Type an answer…"><button class="pillbtn pri" data-act="answer-text" data-id="${t.id}">Send</button></div></div>`;
  }

  BOTTABS.tasks = (b) => taskList(S.tasks.filter((t) => t.botId === b.id), b);
  BOTTABS.files = (b) => { const ids = new Set(S.tasks.filter((t) => t.botId === b.id).map((t) => t.id)); const fs = S.files.filter((f) => ids.has(f.taskId)); return `<div class="card">${fs.length ? `<div class="rows">${fs.map(fileRow).join('')}</div>` : `<div class="empty">${esc(b.name)} hasn't made any files yet.</div>`}<div style="margin-top:12px"><button class="pillbtn" data-act="files-folder">Open the Nebula Files folder</button></div></div>`; };
  BOTTABS.computer = (b) => {
    const steps = S.tasks.filter((t) => t.botId === b.id).flatMap((t) => t.steps.map((s) => ({ ...s, task: t }))).sort((a, c) => (c.at || 0) - (a.at || 0)).slice(0, 14);
    const caps = S.catalogue.capabilities.filter((c) => b.caps.includes(c.id));
    return `<div class="grid g2"><div class="card"><h3>${I('computer', 'width="16" height="16"')} Cloud computer</h3>
        <div class="kv"><span>Status</span><b style="color:var(--faint)">Offline — not available</b></div>
        <p style="color:var(--dim);margin:12px 0">Nebula doesn't have a cloud computer service yet, so there is no remote screen to watch or take control of. When one is connected, its live screen, tabs, files and terminal will show here.</p>
        <div class="note">Today, ${esc(b.name)} works on <b>this PC</b> through Nebula's own tools — only the ones you allow, and with approval where its autonomy level requires it.</div>
        <h3 style="margin-top:6px">What it can use on this PC</h3>${caps.length ? caps.map((c) => `<div class="kv"><span>${esc(c.label)}</span><b>${esc(c.desc)}</b></div>`).join('') : '<div class="empty">Nothing — it can only think, search its knowledge and remember. Turn tools on in Settings → Tools.</div>'}
        <div style="margin-top:12px"><button class="pillbtn" data-act="tab" data-t="settings">Change tools</button> <button class="pillbtn" data-act="files-folder">Open its workspace folder</button></div></div>
      <div class="card"><h3>Recent actions</h3>${steps.length ? `<ul class="steps">${steps.map((s) => `<li class="${s.status}"><i>${s.status === 'DONE' ? '✓' : s.status === 'WAITING' ? '…' : '!'}</i><div>${esc(s.label)}<small>${esc(s.detail || '')} · ${esc(s.task.title)} · ${ago(s.at)}</small></div></li>`).join('')}</ul>` : '<div class="empty">No actions yet.</div>'}</div></div>`;
  };
  BOTTABS.skills = (b) => {
    const mine = b.skills.map((id) => S.skills.find((s) => s.id === id)).filter(Boolean); const rest = S.skills.filter((s) => !b.skills.includes(s.id));
    return `<div class="grid g2"><div class="card"><h3>${esc(b.name)}'s skills</h3>${mine.length ? mine.map((s) => `<div class="row"><div class="tx"><b>${esc(s.name)} <span class="tag">v${s.version}</span></b><small>${esc(s.description)}</small></div><button class="pillbtn" data-act="skill-run" data-id="${s.id}" data-bot="${b.id}">Run</button><button class="ico" data-act="skill-unassign" data-id="${s.id}" data-bot="${b.id}" title="Remove">${I('x')}</button></div>`).join('') : '<div class="empty">No skills yet.</div>'}</div>
      <div class="card"><h3>Add from your library<span class="sp"></span><a data-act="skill-new">New skill</a></h3>${rest.length ? rest.map((s) => `<div class="row"><div class="tx"><b>${esc(s.name)}</b><small>${esc(s.category)} · ${esc(s.description)}</small></div><button class="pillbtn" data-act="skill-assign" data-id="${s.id}" data-bot="${b.id}">Add</button></div>`).join('') : `<div class="empty">Your skill library is empty. <a class="linkbtn" data-act="nav" data-v="skills">Open Skills</a></div>`}</div></div>`;
  };
  BOTTABS.routines = (b) => `<div class="toolbar"><span style="flex:1"></span><button class="pillbtn pri" data-act="routine-new" data-bot="${b.id}">${I('plus')}New routine</button></div>${routineCards(S.routines.filter((r) => r.botId === b.id))}`;
  BOTTABS.knowledge = (b) => {
    const q = (R.f.kq || '').toLowerCase(); const cat = R.f.kcat || '';
    const cats = [...new Set(b.knowledge.map((k) => k.category))];
    const list = b.knowledge.filter((k) => (!q || k.title.toLowerCase().includes(q)) && (!cat || k.category === cat));
    return `<div class="toolbar"><input type="search" placeholder="Search knowledge" data-keep="kq" id="kq" value="${esc(R.f.kq || '')}"><select data-act-change="kcat"><option value="">All categories</option>${cats.map((c) => `<option ${c === cat ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select><span style="flex:1"></span><button class="pillbtn" data-act="k-files" data-id="${b.id}">Upload files</button><button class="pillbtn" data-act="k-url" data-id="${b.id}">Add a link</button><button class="pillbtn pri" data-act="k-note" data-id="${b.id}">${I('plus')}Add a note</button></div>
      <div class="note">${esc(b.name)} reads the most relevant passages for each request, and can search all of it. Text files, notes and web pages work today; paste the text of PDFs and Word files as a note.</div>
      ${list.map((k) => `<div class="kn"><div><b>${esc(k.title)}</b><div><span class="tag">${esc(k.category)}</span><small>${k.kind} · ${(k.chars / 1000).toFixed(1)}k characters · ${ago(k.addedAt)}</small></div></div><div style="display:flex;gap:6px"><button class="pillbtn" data-act="k-preview" data-id="${b.id}" data-k="${k.id}">Preview</button><button class="pillbtn" data-act="k-cat" data-id="${b.id}" data-k="${k.id}">Category</button><button class="ico" data-act="k-remove" data-id="${b.id}" data-k="${k.id}" title="Remove">${I('x')}</button></div></div>`).join('') || `<div class="empty">${b.knowledge.length ? 'Nothing matches.' : 'No knowledge yet.'}</div>`}`;
  };
  BOTTABS.memory = (b) => `<div class="toolbar"><div class="ask" style="flex:1;margin:0"><input id="memIn" data-keep="memIn" placeholder="Add something ${esc(b.name)} should always remember…"><button class="pillbtn pri" data-act="mem-add" data-id="${b.id}">Remember</button></div><button class="pillbtn" data-act="mem-toggle" data-id="${b.id}">${b.memoryOn === false ? 'Turn memory on' : 'Turn memory off'}</button><button class="pillbtn danger" data-act="mem-clear" data-id="${b.id}">Clear all</button></div>
    ${b.memoryOn === false ? '<div class="note warn">Memory is off: the bot won\'t save or use memories.</div>' : ''}
    <div class="card">${b.memories.length ? b.memories.map((m) => `<div class="row" style="cursor:default"><div class="tx"><b style="white-space:normal">${esc(m.text)}</b><small>${m.by === 'bot' ? 'Saved by the bot' : 'Added by you'} · ${ago(m.at)}</small></div><button class="ico" data-act="mem-edit" data-id="${b.id}" data-m="${m.id}" title="Edit">${I('edit')}</button><button class="ico" data-act="mem-remove" data-id="${b.id}" data-m="${m.id}" title="Forget">${I('x')}</button></div>`).join('') : '<div class="empty">No memories yet. Bots save lasting facts (never passwords or secrets) as they work.</div>'}</div>`;
  BOTTABS.activity = (b) => `<div class="card">${timeline(S.activity.filter((e) => e.botId === b.id))}</div>`;
  BOTTABS.settings = (b) => {
    if (!R.edit || R.edit.id !== b.id) R.edit = JSON.parse(JSON.stringify(b));
    const e = R.edit;
    const allowedBots = S.bots.filter((x) => x.id !== b.id && !x.archived);
    return `<div class="grid" style="grid-template-columns:200px 1fr;align-items:start">
      <div class="card" style="position:sticky;top:0;padding:10px">${[['General', 'g'], ['Instructions', 'i'], ['Personality', 'p'], ['Tools & apps', 't'], ['Autonomy', 'a'], ['Notifications', 'n'], ['Collaboration', 'c'], ['Privacy', 'pr'], ['Usage', 'u'], ['Advanced', 'ad']].map(([l, k]) => `<button class="citem" data-act="jump-sec" data-s="${k}">${l}</button>`).join('')}</div>
      <div>
        <div class="card" id="sec-g"><h3>General</h3>${identityForm(e)}</div>
        <div class="card" id="sec-i" style="margin-top:14px"><h3>Role & instructions</h3>${roleForm(e)}${instructionsForm(e)}</div>
        <div class="card" id="sec-p" style="margin-top:14px"><h3>Personality</h3>${personalityForm(e)}</div>
        <div class="card" id="sec-t" style="margin-top:14px"><h3>Tools & applications</h3>${toolsForm(e)}</div>
        <div class="card" id="sec-a" style="margin-top:14px"><h3>Autonomy</h3>${autonomyForm(e)}<div class="kv"><span>“Always allow” choices</span><b>${Object.keys(b.alwaysAllow || {}).map((k) => S.catalogue.tools[k]?.label || k).join(', ') || 'None'} ${Object.keys(b.alwaysAllow || {}).length ? `<a class="linkbtn" data-act="bot-act" data-a="forget-allow" data-id="${b.id}">Reset</a>` : ''}</b></div></div>
        <div class="card" id="sec-n" style="margin-top:14px"><h3>Notifications</h3>${[['done', 'When a task is finished'], ['failed', 'When a task fails'], ['approval', 'When it needs approval or an answer']].map(([k, l]) => `<div class="opt ${e.notify?.[k] !== false ? 'on' : ''}" data-act="ed-toggle" data-p="notify.${k}"><span class="box"></span><div><b>${l}</b></div></div>`).join('')}<div class="helpline">Shown as Windows/Linux notifications when this window isn't in front. Workspace-wide switch in Settings.</div></div>
        <div class="card" id="sec-c" style="margin-top:14px"><h3>Collaboration</h3><div class="helpline" style="margin-bottom:10px">Needs “Work with other bots” in Tools. Pick which bots it may hand work to (none picked = any of your bots). Delegation is limited to 2 levels and 6 hand-offs per task.</div>${allowedBots.map((x) => `<div class="opt ${(e.collab?.allowed || []).includes(x.id) ? 'on' : ''}" data-act="ed-allow" data-id="${x.id}"><span class="box"></span>${av(x, 24, false)}<div><b>${esc(x.name)}</b><small>${esc(x.role)}</small></div></div>`).join('') || '<div class="empty">No other bots.</div>'}</div>
        <div class="card" id="sec-pr" style="margin-top:14px"><h3>Privacy</h3><div class="kv"><span>Where its data lives</span><b>On this PC only (Nebula's app data folder)</b></div><div class="kv"><span>What leaves this PC</span><b>Your messages, its instructions and relevant knowledge go to your AI provider (Groq) to generate answers</b></div><div class="kv"><span>Exports</span><b>Never include memories or “always allow” choices</b></div><div class="kv" style="border:0"><span>Memory</span><b>${b.memories.length} saved · <a class="linkbtn" data-act="tab" data-t="memory">manage</a></b></div></div>
        <div class="card" id="sec-u" style="margin-top:14px"><h3>Usage</h3>${usageFor(b)}</div>
        <div class="card" id="sec-ad" style="margin-top:14px"><h3>Advanced</h3><div class="field"><label>Bot ID (handle)</label><input type="text" data-b="handle" value="${esc(e.handle)}"></div><div class="kv"><span>Internal id</span><b>${esc(b.id)}</b></div><div class="kv"><span>Created</span><b>${new Date(b.createdAt).toLocaleString('en-GB')}</b></div><div class="chips" style="margin-top:12px"><button class="pillbtn" data-act="bot-act" data-a="export" data-id="${b.id}">Export configuration</button><button class="pillbtn" data-act="bot-act" data-a="duplicate" data-id="${b.id}">Duplicate</button><button class="pillbtn" data-act="bot-act" data-a="archive" data-id="${b.id}">Archive</button><button class="pillbtn danger" data-act="bot-delete" data-id="${b.id}">Delete…</button></div></div>
        <div style="position:sticky;bottom:0;display:flex;gap:8px;justify-content:flex-end;padding:14px 0;background:linear-gradient(transparent,var(--bg) 40%)"><button class="pillbtn" data-act="ed-reset">Discard changes</button><button class="pillbtn pri" data-act="ed-save">Save changes</button></div>
      </div></div>`;
  };
  function usageFor(b) {
    const ts = S.tasks.filter((t) => t.botId === b.id); const done = ts.filter((t) => t.status === 'COMPLETED').length; const fin = ts.filter((t) => ['COMPLETED', 'FAILED'].includes(t.status)).length;
    const mins = Math.round(ts.reduce((a, t) => a + (t.finishedAt && t.startedAt ? t.finishedAt - t.startedAt : 0), 0) / 60000);
    return `<div class="grid g4"><div class="stat"><span>Tasks</span><b>${ts.length}</b></div><div class="stat"><span>Success rate</span><b>${fin ? Math.round(done / fin * 100) : 0}%</b></div><div class="stat"><span>Time working</span><b>${mins}<small> min</small></b></div><div class="stat"><span>Files made</span><b>${S.files.filter((f) => ts.some((t) => t.id === f.taskId)).length}</b></div></div>`;
  }

  // ---------------------------------------------------------------- bot forms (shared by the wizard and Settings)
  const val = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o);
  const ROLES = ['Personal assistant', 'Researcher', 'Chief of Staff', 'Project manager', 'Developer', 'Marketing specialist', 'Financial research assistant', 'Customer support agent', 'Social media manager', 'Writer', 'Designer', 'Analyst', 'Operations assistant', 'Scheduler'];
  function identityForm(e) {
    return `<div class="field"><label>Avatar</label><div class="avpick">${SHAPES.map((s, i) => `<button class="${e.shape === i ? 'on' : ''}" data-act="ed-set" data-p="shape" data-v="${i}" data-num="1">${window.NB.charSvg(i, e.color)}</button>`).join('')}</div></div>
      <div class="field"><label>Accent colour</label><div class="swatches">${PALETTE.map((c) => `<button class="${e.color === c ? 'on' : ''}" style="background:${c}" data-act="ed-set" data-p="color" data-v="${c}"></button>`).join('')}<input type="color" value="${esc(e.color)}" data-b="color" title="Any colour"><button class="pillbtn" data-act="ed-surprise">Surprise me</button></div></div>
      <div class="two"><div class="field"><label>Bot name</label><input type="text" data-b="name" maxlength="40" value="${esc(e.name)}" placeholder="e.g. Ada"></div><div class="field"><label>Bot ID</label><input type="text" data-b="handle" maxlength="24" value="${esc(e.handle)}" placeholder="ada"></div></div>
      <div class="field"><label>Short description</label><input type="text" data-b="description" maxlength="160" value="${esc(e.description)}" placeholder="What is this bot for?"></div>
      <div class="two"><div class="field"><label>Welcome message</label><input type="text" data-b="welcome" maxlength="200" value="${esc(e.welcome)}" placeholder="Shown at the top of a new chat"></div><div class="field"><label>Status note (optional)</label><input type="text" data-b="statusNote" maxlength="60" value="${esc(e.statusNote)}" placeholder="e.g. Focused on Q1 research"></div></div>`;
  }
  function roleForm(e) {
    const custom = !ROLES.includes(e.role);
    return `<div class="field"><label>What is this bot's job?</label><div class="chips">${ROLES.map((r) => `<button class="qa ${e.role === r ? 'on' : ''}" data-act="ed-set" data-p="role" data-v="${esc(r)}">${esc(r)}</button>`).join('')}<button class="qa ${custom ? 'on' : ''}" data-act="ed-custom-role">Custom role</button></div>${custom ? `<input type="text" style="margin-top:10px" data-b="role" value="${esc(e.role)}" placeholder="Describe the role">` : ''}</div>`;
  }
  const INS = [['resp', 'Responsible for', 'What it owns'], ['do', 'What it should do', 'Habits and must-dos'], ['never', 'What it must never do', 'Hard limits'], ['approach', 'How it approaches work', 'Method, order, depth'], ['communication', 'How it communicates', 'Updates, questions, tone'], ['style', 'Writing style', 'Voice, length, spelling'], ['boundaries', 'Decisions it may make alone', 'Decision-making boundaries'], ['priorities', 'Priorities', 'What matters most'], ['escalation', 'When to ask you', 'Escalation rules'], ['output', 'Output formats', 'Reports, tables, files'], ['quality', 'Quality bar', 'Checks before finishing']];
  function instructionsForm(e) {
    return `<div class="field" style="margin-top:6px"><label>Describe how you want this bot to behave</label><textarea id="insDesc" data-keep="insDesc" placeholder="e.g. “Research things for my design studio. Be brief, use British spelling, always give sources, never pay for anything, and ask me before contacting anyone.”"></textarea><div style="margin-top:8px"><button class="pillbtn" data-act="ins-ai">${I('spark')}Write the instructions with AI</button> <span class="helpline">You can edit everything it writes.</span></div></div>
      <div class="two">${INS.map(([k, l, h]) => `<div class="field"><label>${l}</label><textarea data-b="ins.${k}" rows="2" placeholder="${h}">${esc(val(e, `ins.${k}`) || '')}</textarea></div>`).join('')}<div class="field"><label>Anything else</label><textarea data-b="ins.notes" rows="2">${esc(e.ins?.notes || '')}</textarea></div></div>`;
  }
  const SLAB = { formality: ['Casual', 'Formal'], verbosity: ['Brief', 'Detailed'], creativity: ['Conventional', 'Inventive'], initiative: ['Waits', 'Takes initiative'], proactiveness: ['Only what\'s asked', 'Points things out'], confidence: ['Hedges', 'Decisive'], caution: ['Fast', 'Careful'] };
  function personalityForm(e) {
    return `<div class="field"><label>Personality</label><div class="chips">${[...S.catalogue.personality, 'Custom'].map((p) => `<button class="qa ${e.personality.preset === p ? 'on' : ''}" data-act="ed-set" data-p="personality.preset" data-v="${p}">${p}</button>`).join('')}</div></div>
      ${e.personality.preset === 'Custom' ? `<div class="field"><label>Describe the personality</label><textarea data-b="personality.custom" rows="2">${esc(e.personality.custom || '')}</textarea></div>` : ''}
      ${S.catalogue.sliders.map((k) => `<div class="slider"><span>${SLAB[k][0]}</span><input type="range" min="0" max="1" step="0.05" value="${e.personality[k] ?? 0.5}" data-b="personality.${k}" data-num="1"><span>${SLAB[k][1]}</span></div>`).join('')}
      <div class="helpline">Personality changes how it talks — never what it's allowed to do.</div>`;
  }
  function toolsForm(e) {
    return S.catalogue.capabilities.map((c) => { const risks = [...new Set(c.tools.map((t) => S.catalogue.tools[t]?.risk))].filter((r) => r && r !== 'none'); return `<div class="opt ${e.caps.includes(c.id) ? 'on' : ''}" data-act="ed-cap" data-c="${c.id}"><span class="box"></span><div style="flex:1"><b>${esc(c.label)} ${risks.map((r) => `<span class="risk ${r}">${r}</span>`).join(' ')}</b><small>${esc(c.desc)}</small></div></div>`; }).join('')
      + '<div class="helpline">Always on: planning, asking you questions, searching its own knowledge and saving memories. Email, calendar, Slack and other apps aren\'t available yet — see Connect Apps.</div>';
  }
  function autonomyForm(e) {
    return Object.entries(S.catalogue.autonomy).map(([k, a]) => `<div class="opt ${e.autonomy === k ? 'on' : ''}" data-act="ed-set" data-p="autonomy" data-v="${k}"><span class="box"></span><div><b>${esc(a.label)}</b><small>${esc(a.desc)} Asks before: ${a.approve.map((r) => `${r}-risk`).join(', ')} actions.</small></div></div>`).join('')
      + '<div class="helpline">Every level still respects its tool permissions, and irreversible actions (like overwriting a file) always need your OK.</div>';
  }

  // ---------------------------------------------------------------- create-a-bot wizard
  const WSTEPS = ['Identity', 'Role', 'Instructions', 'Personality', 'Knowledge', 'Skills', 'Tools & apps', 'Computer', 'Autonomy', 'Review'];
  let WZ = null;
  function wizard(fromTpl) {
    const base = { id: null, name: '', handle: '', shape: Math.floor(Math.random() * 10), color: PALETTE[Math.floor(Math.random() * 7)], description: '', welcome: '', statusNote: '', role: 'Personal assistant',
      ins: { resp: '', do: '', never: '', approach: '', communication: '', style: '', boundaries: '', priorities: '', escalation: 'If something is unclear or risky, ask me before going on.', output: '', quality: '', notes: '' },
      personality: { preset: 'Friendly', formality: 0.5, verbosity: 0.5, creativity: 0.5, initiative: 0.5, proactiveness: 0.5, confidence: 0.5, caution: 0.5, custom: '' }, caps: ['web', 'files'], skills: [], autonomy: 'supervised', collab: { allowed: [] }, notify: { done: true, failed: true, approval: true } };
    WZ = { step: fromTpl === 'pick' ? -1 : 0, d: base, first: '' };
    R.edit = WZ.d; drawWizard();
  }
  function drawWizard() {
    const d = WZ.d; R.edit = d;
    const st = WZ.step;
    let body = '';
    if (st === -1) body = `<h3>Start from a template</h3><p class="lead">Ready-made bots from the Marketplace. You can change everything afterwards.</p><div class="tpl">${S.catalogue.templates.map((t) => `<button data-act="wz-tpl" data-k="${t.key}"><span class="av" style="width:36px;height:36px">${window.NB.charSvg(t.shape, t.color)}</span><b>${esc(t.name)}</b><small>${esc(t.desc)}</small></button>`).join('')}</div>`;
    if (st === 0) body = `<h3>Identity</h3><p class="lead">Give your bot a face and a name.</p>${identityForm(d)}`;
    if (st === 1) body = `<h3>Role</h3><p class="lead">What is this bot's job?</p>${roleForm(d)}`;
    if (st === 2) body = `<h3>Instructions</h3><p class="lead">What it's responsible for, what it should and shouldn't do, and how it works.</p>${instructionsForm(d)}`;
    if (st === 3) body = `<h3>Personality</h3><p class="lead">How it sounds.</p>${personalityForm(d)}`;
    if (st === 4) {
      const b = d.id && bot(d.id); const ks = b ? b.knowledge : [];
      body = `<h3>Knowledge</h3><p class="lead">Files, notes and links it should know. It reads the most relevant parts for each task.</p><div class="note">Your bot is saved as a draft so knowledge can be attached to it.</div>
        <div class="chips" style="margin-bottom:14px"><button class="pillbtn" data-act="k-files" data-id="${d.id}">Upload files</button><button class="pillbtn" data-act="k-url" data-id="${d.id}">Add a link</button><button class="pillbtn" data-act="k-note" data-id="${d.id}">Add a note</button></div>
        ${ks.map((k) => `<div class="kn"><div><b>${esc(k.title)}</b><div><span class="tag">${esc(k.category)}</span><small>${(k.chars / 1000).toFixed(1)}k characters</small></div></div><button class="ico" data-act="k-remove" data-id="${d.id}" data-k="${k.id}">${I('x')}</button></div>`).join('') || '<div class="empty">Nothing yet — this step is optional.</div>'}`;
    }
    if (st === 5) body = `<h3>Skills</h3><p class="lead">Reusable workflows it can follow.</p>${S.skills.length ? S.skills.map((s) => `<div class="opt ${d.skills.includes(s.id) ? 'on' : ''}" data-act="wz-skill" data-id="${s.id}"><span class="box"></span><div><b>${esc(s.name)}</b><small>${esc(s.description)}</small></div></div>`).join('') : '<div class="empty">No skills in your library yet.</div>'}<div class="chips" style="margin-top:10px">${S.catalogue.skillTemplates.slice(0, 6).map((t) => `<button class="qa" data-act="wz-skill-tpl" data-i="${t.i}">${I('plus')}${esc(t.name)}</button>`).join('')}</div>`;
    if (st === 6) body = `<h3>Tools & applications</h3><p class="lead">What it may use. It can't use anything you leave off — that's enforced by Nebula, not just asked of the AI.</p>${toolsForm(d)}`;
    if (st === 7) body = `<h3>Computer</h3><p class="lead">Where it works.</p><div class="kv"><span>Cloud computer</span><b style="color:var(--faint)">Not available yet</b></div><div class="kv"><span>This PC</span><b>Through the tools you picked: ${S.catalogue.capabilities.filter((c) => d.caps.includes(c.id)).map((c) => c.label).join(', ') || 'none'}</b></div><div class="kv"><span>Workspace folder</span><b>Nebula Files (shared with Nebula)</b></div><div class="kv"><span>Browser</span><b>Opens links in your browser when allowed — it can't click or read pages yet</b></div><p class="helpline" style="margin-top:12px">When a cloud computer service is connected, you'll set its browser, files, terminal and execution permissions here.</p>`;
    if (st === 8) body = `<h3>Autonomy</h3><p class="lead">How independently it works.</p>${autonomyForm(d)}`;
    if (st === 9) body = `<h3>Review</h3><p class="lead">Check everything, then create your bot.</p><div style="display:flex;gap:16px;align-items:center;margin-bottom:16px"><span class="av" style="width:70px;height:70px">${window.NB.charSvg(d.shape, d.color)}</span><div><b style="font-size:20px">${esc(d.name || 'Unnamed bot')}</b><div class="helpline">@${esc(d.handle || slug(d.name))} · ${esc(d.role)}</div></div></div>
      <dl class="sumgrid"><dt>Description</dt><dd>${esc(d.description || '—')}</dd><dt>Responsible for</dt><dd>${esc(d.ins.resp || '—')}</dd><dt>Never</dt><dd>${esc(d.ins.never || '—')}</dd><dt>Personality</dt><dd>${esc(d.personality.preset)}</dd><dt>Knowledge</dt><dd>${d.id && bot(d.id) ? bot(d.id).knowledge.length : 0} item(s)</dd><dt>Skills</dt><dd>${d.skills.map((id) => S.skills.find((s) => s.id === id)?.name).filter(Boolean).join(', ') || '—'}</dd><dt>Tools</dt><dd>${S.catalogue.capabilities.filter((c) => d.caps.includes(c.id)).map((c) => c.label).join(', ') || 'None'}</dd><dt>Autonomy</dt><dd>${esc(S.catalogue.autonomy[d.autonomy].label)}</dd></dl>
      <div class="field" style="margin-top:18px"><label>Start its first task now (optional)</label><input type="text" id="wzFirst" value="${esc(WZ.first)}" placeholder="e.g. Research the three best note-taking apps for students"></div>`;
    const steps = `<div class="wsteps">${st === -1 ? '' : WSTEPS.map((s, i) => `<button class="${i === st ? 'on' : i < st ? 'done' : ''}" data-act="wz-go" data-i="${i}"><i>${i < st ? '✓' : i + 1}</i>${s}</button>`).join('')}</div>`;
    const foot = st === -1 ? '<button class="pillbtn" data-act="wz-scratch">Start from scratch instead</button>'
      : `<button class="pillbtn" data-act="wz-draft">Save as draft</button><span class="sp"></span>${st > 0 ? '<button class="pillbtn" data-act="wz-back">Back</button>' : ''}${st < 9 ? '<button class="pillbtn pri" data-act="wz-next">Next</button>' : '<button class="pillbtn pri" data-act="wz-create">Create bot</button>'}`;
    const prev = $('layer').querySelector('.wbody'); const top = prev ? prev.scrollTop : 0; const same = WZ.lastStep === st; WZ.lastStep = st;
    modal(st === -1 ? 'Create a bot' : `Create a bot · ${WSTEPS[st]}`, `<div class="wiz ${st === -1 ? 'one' : ''}">${steps}<div class="wbody">${body}</div></div>`, foot, 'xl');
    if (same) $('layer').querySelector('.wbody').scrollTop = top;
  }
  const slug = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'bot';
  async function wzEnsureDraft() {
    if (WZ.d.id) return WZ.d.id;
    if (!WZ.d.name.trim()) WZ.d.name = 'New bot';
    const id = await call('ws:bot-save', { ...WZ.d, draft: true }); if (id) WZ.d.id = id; return id;
  }

  // ---------------------------------------------------------------- other views
  function taskList(list, b) {
    const f = R.f.tstat || 'all'; const q = (R.f.tq || '').toLowerCase(); const fb = R.f.tbot || '';
    const groups = { all: () => true, needs: (t) => t.status === 'REQUIRES_INPUT', active: (t) => ['IN_PROGRESS', 'QUEUED', 'PAUSED'].includes(t.status), done: (t) => t.status === 'COMPLETED', failed: (t) => ['FAILED', 'CANCELLED'].includes(t.status) };
    const shown = list.filter((t) => groups[f](t) && (!q || t.title.toLowerCase().includes(q) || (t.summary || '').toLowerCase().includes(q)) && (!fb || t.botId === fb)).sort((a, c) => (c.updatedAt - a.updatedAt));
    return `<div class="toolbar"><div class="seg">${[['all', 'All'], ['needs', 'Needs you'], ['active', 'Active'], ['done', 'Done'], ['failed', 'Failed']].map(([k, l]) => `<button class="${f === k ? 'on' : ''}" data-act="filter" data-k="tstat" data-v="${k}">${l} · ${list.filter(groups[k]).length}</button>`).join('')}</div><input type="search" placeholder="Search tasks" data-keep="tq" id="tq" value="${esc(R.f.tq || '')}">${b ? '' : `<select data-act-change="tbot"><option value="">All bots</option>${S.bots.map((x) => `<option value="${x.id}" ${fb === x.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select>`}<span style="flex:1"></span><button class="pillbtn pri" data-act="assign" ${b ? `data-bot="${b.id}"` : ''}>${I('plus')}New task</button></div>
      <div class="card">${shown.length ? `<div class="rows">${shown.slice(0, 200).map((t) => `${taskRow(t).replace('</div><span class="status', `</div><span class="when">${ago(t.updatedAt)}</span><span class="status`)}${(t.children || []).length ? `<div style="margin-left:42px">${t.children.map((cid) => task(cid)).filter(Boolean).map(taskRow).join('')}</div>` : ''}`).join('')}</div>` : `<div class="empty">${list.length ? 'Nothing matches.' : 'No tasks yet.'}</div>`}</div>`;
  }
  VIEWS.tasks = () => ['Tasks', taskList(S.tasks.filter((t) => !t.parentId))];

  function timeline(list) {
    if (!list.length) return '<div class="empty">No activity yet.</div>';
    let out = ''; let day = '';
    for (const e of list.slice(0, 250)) { const d = new Date(e.at).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' }); if (d !== day) { day = d; out += `<div class="day">${esc(d)}</div>`; } out += evRow(e); }
    return `<div class="tl">${out}</div>`;
  }
  VIEWS.activity = () => {
    const fb = R.f.abot || ''; const fk = R.f.akind || '';
    const kinds = { '': 'Everything', task: 'Tasks', step: 'Actions', approval: 'Approvals', error: 'Errors', done: 'Finished', failed: 'Failed', knowledge: 'Knowledge', routine: 'Routines' };
    const list = S.activity.filter((e) => (!fb || e.botId === fb) && (!fk || e.kind === fk || (fk === 'approval' && ['approval', 'approved', 'denied'].includes(e.kind))));
    return ['Activity', `<div class="toolbar"><select data-act-change="abot"><option value="">All bots</option>${S.bots.map((x) => `<option value="${x.id}" ${fb === x.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select><select data-act-change="akind">${Object.entries(kinds).map(([k, l]) => `<option value="${k}" ${fk === k ? 'selected' : ''}>${l}</option>`).join('')}</select><span class="helpline">Every real action, approval and result — newest first. Private reasoning isn't shown.</span></div><div class="card">${timeline(list)}</div>`];
  };

  function routineCards(list) {
    if (!list.length) return '<div class="card"><div class="empty">No routines yet. A routine gives a bot the same job on a schedule — like “Every weekday at 07:00, prepare my briefing.” They run while Nebula is open or in the tray.</div></div>';
    return `<div class="grid g2">${list.map((r) => { const b = bot(r.botId); const h = r.history || []; return `<div class="card"><div style="display:flex;gap:12px;align-items:center">${av(b, 36)}<div style="flex:1;min-width:0"><b style="font-size:15px">${esc(r.name)}</b><div class="helpline">${esc(b ? b.name : 'Removed bot')} · ${esc(r.when)}</div></div>${r.active ? pill('ok', 'Active') : pill('paused', 'Off')}</div>
      <p style="color:var(--dim);margin:12px 0;font-size:13px">${esc(r.instructions.slice(0, 220))}</p>
      <div class="kv"><span>Next run</span><b>${r.active && r.nextRun ? `${new Date(r.nextRun).toLocaleString('en-GB', { weekday: 'short', hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })} (${ago(r.nextRun)})` : r.schedule.type === 'task' ? 'When a task finishes' : '—'}</b></div>
      <div class="kv"><span>Last run</span><b>${h[0] ? `${ago(h[0].at)} · ${h[0].taskId && task(h[0].taskId) ? TS[task(h[0].taskId).status] : h[0].status || '—'}` : 'Never'}</b></div>
      <div class="kv"><span>Approvals</span><b>${r.approval === 'always' ? 'Ask before every action' : 'Follow the bot\'s autonomy'}</b></div>
      ${h.length ? `<details style="margin-top:8px"><summary class="linkbtn">History (${h.length})</summary><div class="rows">${h.slice(0, 12).map((x) => { const t = x.taskId && task(x.taskId); return `<div class="row" ${t ? `data-act="task" data-id="${t.id}"` : ''}><div class="tx"><b>${new Date(x.at).toLocaleString('en-GB')}</b><small>${esc(x.trigger)}${t ? ` · ${esc(t.summary || '')}` : ''}${t && t.finishedAt && t.startedAt ? ` · ${Math.max(1, Math.round((t.finishedAt - t.startedAt) / 1000))} s` : ''}</small></div>${t ? tStatus(t) : pill('CANCELLED', x.status || '—')}</div>`; }).join('')}</div></details>` : ''}
      <div class="chips" style="margin-top:12px"><button class="pillbtn pri" data-act="routine-act" data-a="run" data-id="${r.id}">${I('play')}Run now</button><button class="pillbtn" data-act="routine-act" data-a="toggle" data-id="${r.id}">${r.active ? 'Turn off' : 'Turn on'}</button><button class="pillbtn" data-act="routine-edit" data-id="${r.id}">Edit</button><button class="pillbtn" data-act="routine-act" data-a="duplicate" data-id="${r.id}">Duplicate</button><button class="pillbtn danger" data-act="routine-del" data-id="${r.id}">Delete</button></div></div>`; }).join('')}</div>`;
  }
  VIEWS.routines = () => ['Routines', `<div class="toolbar"><span class="helpline">Routines run on this PC while Nebula is running (it keeps going in the tray when you close the window). Runs missed by more than 6 hours are skipped and logged, never run late. Max 48 runs a day each; intervals of 15 minutes or more.</span><span style="flex:1"></span><button class="pillbtn pri" data-act="routine-new">${I('plus')}New routine</button></div>${routineCards(S.routines)}`];

  VIEWS.teams = () => {
    if (R.team) {
      const tm = S.teams.find((x) => x.id === R.team); if (!tm) { R.team = null; return VIEWS.teams(); }
      const ts = S.tasks.filter((t) => t.teamId === tm.id && !t.parentId);
      return [`<span class="crumb">Teams /</span> ${esc(tm.name)}`, `<div class="hero"><div><h2>${esc(tm.name)}</h2><p>${esc(tm.purpose || '')}</p></div><div class="chips"><button class="pillbtn" data-act="team-edit" data-id="${tm.id}">Edit</button><button class="pillbtn danger" data-act="team-del" data-id="${tm.id}">Delete</button></div></div>
        <div class="grid g2"><div class="card"><h3>Give the team a task</h3><div class="ask" style="margin:0"><input id="teamTask" data-keep="teamTask" placeholder="e.g. Plan a launch campaign for my app"><button class="pillbtn pri" data-act="team-task" data-id="${tm.id}">${I('send')}Start</button></div><div class="helpline" style="margin-top:8px">${esc(bot(tm.coordinator)?.name || 'The coordinator')} receives it, hands parts to the others and combines the results. Mode: ${esc(tm.mode)}.</div>
          <h3 style="margin-top:18px">Team tasks</h3>${ts.length ? `<div class="rows">${ts.map((t) => taskRow(t) + (t.children || []).map((c) => task(c)).filter(Boolean).map((c) => `<div style="margin-left:36px">${taskRow(c)}</div>`).join('')).join('')}</div>` : '<div class="empty">No team tasks yet.</div>'}</div>
        <div class="card"><h3>Members</h3><div class="rows">${tm.members.map((id) => bot(id)).filter(Boolean).map((b) => `<div class="row" data-act="bot" data-id="${b.id}">${av(b, 30)}<div class="tx"><b>${esc(b.name)} ${b.id === tm.coordinator ? '<span class="tag">Coordinator</span>' : ''}</b><small>${esc(b.role)}</small></div>${pill(b.status.key, b.status.label)}</div>`).join('')}</div>${tm.instructions ? `<h3 style="margin-top:16px">Team instructions</h3><p style="color:var(--dim);margin:0">${esc(tm.instructions)}</p>` : ''}</div></div>`];
    }
    return ['Teams', `<div class="toolbar"><span class="helpline">A team is a group of bots with a coordinator who hands out the work and combines the results.</span><span style="flex:1"></span><button class="pillbtn pri" data-act="team-new">${I('plus')}Create a team</button></div>
      ${S.teams.length ? `<div class="bots">${S.teams.map((tm) => `<div class="bot" data-act="team" data-id="${tm.id}"><div style="display:flex">${tm.members.map((id) => bot(id)).filter(Boolean).slice(0, 5).map((b, i) => `<span style="margin-left:${i ? -12 : 0}px">${av(b, 44, false)}</span>`).join('')}</div><h4>${esc(tm.name)}</h4><div class="role">${tm.members.length} bots · ${esc(tm.mode)}</div><div class="cur">${esc(tm.purpose || '')}</div></div>`).join('')}</div>` : '<div class="card"><div class="empty">No teams yet. Examples: a Marketing Team (Researcher → Strategist → Writer → Reviewer) or a Personal Office led by your Chief of Staff.</div></div>'}`];
  };

  VIEWS.skills = () => {
    const q = (R.f.sq || '').toLowerCase();
    const list = S.skills.filter((s) => !q || `${s.name} ${s.description} ${s.category}`.toLowerCase().includes(q));
    return ['Skills', `<div class="toolbar"><input type="search" placeholder="Search skills" data-keep="sq" id="sq" value="${esc(R.f.sq || '')}"><span style="flex:1"></span><button class="pillbtn" data-act="skill-import">Import</button><button class="pillbtn" data-act="skill-teach">Teach a skill</button><button class="pillbtn" data-act="skill-ai">${I('spark')}Create with AI</button><button class="pillbtn pri" data-act="skill-new">${I('plus')}New skill</button></div>
      ${list.length ? `<div class="bots">${list.map((s) => { const users = S.bots.filter((b) => b.skills.includes(s.id)); return `<div class="bot" data-act="skill-edit" data-id="${s.id}"><div><span class="tag">${esc(s.category)}</span><span class="tag">v${s.version}</span></div><h4>${esc(s.name)}</h4><div class="cur" style="white-space:normal">${esc(s.description)}</div><div style="display:flex">${users.slice(0, 6).map((b, i) => `<span style="margin-left:${i ? -8 : 0}px">${av(b, 26, false)}</span>`).join('') || '<span class="helpline">No bots use it yet</span>'}</div></div>`; }).join('')}</div>` : `<div class="card"><div class="empty">${S.skills.length ? 'Nothing matches.' : 'Your skill library is empty. Write one, create one with AI, or add one from the Marketplace.'}</div></div>`}
      <h3 style="margin:24px 0 10px;font-size:13px;color:var(--dim)">Starter skills</h3><div class="chips">${S.catalogue.skillTemplates.map((t) => `<button class="qa" data-act="skill-tpl" data-i="${t.i}">${I('plus')}${esc(t.name)}</button>`).join('')}</div>`];
  };

  VIEWS.library = () => {
    const tab = R.f.lib || 'files'; const q = (R.f.lq || '').toLowerCase();
    const fs = S.files.filter((f) => !q || `${f.title} ${f.fileName}`.toLowerCase().includes(q));
    const ks = S.bots.flatMap((b) => b.knowledge.map((k) => ({ ...k, b }))).filter((k) => !q || k.title.toLowerCase().includes(q));
    const pins = S.pins.map((p) => ({ ...p, item: p.kind === 'bot' ? bot(p.id) : p.kind === 'file' ? S.files.find((f) => f.id === p.id) : p.kind === 'chat' ? conv(p.id) : null })).filter((p) => p.item);
    const isPinned = (kind, id) => S.pins.some((p) => p.kind === kind && p.id === id);
    let body = '';
    if (tab === 'files') body = fs.length ? `<div class="rows">${fs.map((f) => `<div class="row" data-act="file" data-id="${f.id}"><span class="av" style="width:30px;height:30px;display:grid;place-items:center;border-radius:8px;background:rgba(139,92,255,.16);color:var(--accent2);font-size:10px;font-weight:800;text-transform:uppercase">${esc(fileExt(f))}</span><div class="tx"><b>${esc(f.title || f.fileName)}</b><small>${esc(f.fileName)} · ${esc(bot(task(f.taskId)?.botId)?.name || '')} · ${ago(f.createdAt)}</small></div><button class="ico" data-act="pin" data-k="file" data-id="${f.id}" title="${isPinned('file', f.id) ? 'Unpin' : 'Pin'}" style="${isPinned('file', f.id) ? 'color:var(--accent2)' : ''}">${I('pin')}</button><button class="ico" data-act="file-menu" data-id="${f.id}">${I('more')}</button></div>`).join('')}</div>` : '<div class="empty">No files yet.</div>';
    if (tab === 'knowledge') body = ks.length ? ks.map((k) => `<div class="kn"><div><b>${esc(k.title)}</b><div><span class="tag">${esc(k.b.name)}</span><span class="tag">${esc(k.category)}</span><small>${(k.chars / 1000).toFixed(1)}k characters</small></div></div><button class="pillbtn" data-act="k-preview" data-id="${k.b.id}" data-k="${k.id}">Preview</button></div>`).join('') : '<div class="empty">No knowledge yet.</div>';
    if (tab === 'pinned') body = pins.length ? `<div class="rows">${pins.map((p) => p.kind === 'bot' ? `<div class="row" data-act="bot" data-id="${p.id}">${av(p.item, 30)}<div class="tx"><b>${esc(p.item.name)}</b><small>Bot</small></div></div>` : p.kind === 'file' ? fileRow(p.item) : `<div class="row" data-act="chat" data-cid="${p.id}"><div class="tx"><b>${esc(p.item.title)}</b><small>Chat</small></div></div>`).join('')}</div>` : '<div class="empty">Pin bots, files and chats to find them fast.</div>';
    if (tab === 'chats') { const cs = S.conversations.filter((c) => c.messages.length && (!q || c.title.toLowerCase().includes(q))).sort((a, b) => b.updatedAt - a.updatedAt); body = cs.length ? `<div class="rows">${cs.map((c) => `<div class="row" data-act="chat" data-cid="${c.id}">${av(bot(c.botId), 30, false)}<div class="tx"><b>${esc(c.title)}</b><small>${esc(bot(c.botId)?.name || '')} · ${c.messages.length} messages${c.taskId ? ' · task' : ''}</small></div><span class="when">${ago(c.updatedAt)}</span><button class="ico" data-act="pin" data-k="chat" data-id="${c.id}" style="${isPinned('chat', c.id) ? 'color:var(--accent2)' : ''}">${I('pin')}</button></div>`).join('')}</div>` : '<div class="empty">No conversations yet.</div>'; }
    return ['Library', `<div class="toolbar"><div class="seg">${[['files', `Files · ${S.files.length}`], ['knowledge', `Knowledge · ${ks.length}`], ['chats', 'Conversations'], ['pinned', `Pinned · ${pins.length}`]].map(([k, l]) => `<button class="${tab === k ? 'on' : ''}" data-act="filter" data-k="lib" data-v="${k}">${l}</button>`).join('')}</div><input type="search" placeholder="Search the library" data-keep="lq" id="lq" value="${esc(R.f.lq || '')}"><span style="flex:1"></span><button class="pillbtn" data-act="files-folder">Open Files folder</button></div><div class="card">${body}</div>`];
  };

  VIEWS.market = () => {
    const cat = R.f.mcat || '';
    const cats = [...new Set(S.catalogue.templates.map((t) => t.cat))];
    const list = S.catalogue.templates.filter((t) => !cat || t.cat === cat);
    return ['Marketplace', `<div class="hero"><div><h2>Ready-made <em>bots</em> and skills</h2><p>Made by Nebula. Add one, then change anything — name, instructions, tools, autonomy. No ratings or reviews yet; none will be shown until they're real.</p></div></div>
      <div class="toolbar"><div class="seg"><button class="${!cat ? 'on' : ''}" data-act="filter" data-k="mcat" data-v="">All</button>${cats.map((c) => `<button class="${cat === c ? 'on' : ''}" data-act="filter" data-k="mcat" data-v="${esc(c)}">${esc(c)}</button>`).join('')}</div></div>
      <div class="bots">${list.map((t) => { const has = S.bots.filter((b) => b.template === t.key && !b.archived).length; return `<div class="bot" style="--c:${t.color};cursor:default"><span class="av" style="width:52px;height:52px">${window.NB.charSvg(t.shape, t.color)}</span><h4>${esc(t.name)}</h4><div class="role">${esc(t.cat)} · by Nebula · v1</div><div class="cur" style="white-space:normal;min-height:54px">${esc(t.desc)}</div><div>${t.caps.map((c) => `<span class="tag">${esc(S.catalogue.capabilities.find((x) => x.id === c)?.label)}</span>`).join('')}</div>${t.needs ? `<div class="helpline" style="color:var(--warn)">Needs: ${esc(t.needs)}</div>` : ''}<div class="chips"><button class="pillbtn" data-act="tpl-preview" data-k="${t.key}">Preview</button><button class="pillbtn pri" data-act="tpl-add" data-k="${t.key}">${has ? 'Add another' : 'Add to my bots'}</button></div></div>`; }).join('')}</div>
      <h3 style="margin:26px 0 10px;font-size:13px;color:var(--dim)">Skills</h3><div class="bots">${S.catalogue.skillTemplates.map((t) => `<div class="bot" style="cursor:default"><span class="tag" style="align-self:flex-start">${esc(t.category)}</span><h4>${esc(t.name)}</h4><div class="cur" style="white-space:normal">${esc(t.description)}</div><button class="pillbtn" data-act="skill-tpl" data-i="${t.i}">Add to library</button></div>`).join('')}</div>`];
  };

  const CONNECTORS = [
    ['Productivity', [['Web search', 'web', 'live'], ['Nebula Files', 'files', 'live'], ['Apps on this PC', 'apps', 'live'], ['Timers (island)', 'timer', 'live'], ['Clipboard', 'clipboard', 'live'], ['Notion'], ['Trello'], ['Linear']]],
    ['Email', [['Gmail'], ['Microsoft Outlook']]], ['Calendar', [['Google Calendar'], ['Microsoft Calendar']]],
    ['Documents', [['Word, Excel, PowerPoint, PDF (made by Nebula)', 'files', 'live'], ['Google Docs'], ['Google Sheets'], ['Google Slides']]],
    ['Storage', [['Google Drive'], ['OneDrive'], ['Dropbox'], ['Box']]], ['Messaging', [['Slack'], ['Microsoft Teams'], ['Discord']]],
    ['Development', [['GitHub'], ['GitLab'], ['Jira']]], ['Media', [['Spotify / YouTube Music', 'music', 'live'], ['YouTube'], ['Zoom'], ['Google Meet']]],
    ['Business', [['Salesforce'], ['HubSpot'], ['Shopify'], ['Zendesk'], ['Intercom'], ['Figma'], ['Canva'], ['X'], ['Reddit']]],
    ['Automation', [['Custom APIs'], ['MCP servers']]],
  ];
  VIEWS.connect = () => {
    const q = (R.f.cq || '').toLowerCase();
    return ['Connect Apps', `<div class="hero"><div><h2>Connect <em>apps</em></h2><p>Only what really works is marked connected. Everything else needs a sign-in service Nebula doesn't have yet — click one to see what it needs.</p></div></div>
      <div class="toolbar"><input type="search" placeholder="Search apps" data-keep="cq" id="cq" value="${esc(R.f.cq || '')}"><span class="helpline">${S.catalogue.capabilities.length} built in · switch them on per bot in its Tools settings</span></div>
      ${CONNECTORS.map(([cat, items]) => { const it = items.filter((x) => !q || x[0].toLowerCase().includes(q)); return it.length ? `<h3 style="margin:18px 0 10px;font-size:12px;color:var(--faint);text-transform:uppercase;letter-spacing:.08em">${cat}</h3><div class="conn">${it.map(([n, cap, st]) => `<button class="c" data-act="conn" data-n="${esc(n)}" data-cap="${cap || ''}"><span class="lg" style="background:${st ? 'var(--brand)' : '#2a2838'}">${esc(n.slice(0, 1))}</span><div style="flex:1;min-width:0"><b>${esc(n)}</b><small>${st ? 'Built in · working' : 'Not available yet'}</small></div>${st ? pill('ok', 'Connected') : ''}</button>`).join('')}</div>` : ''; }).join('')}`];
  };

  VIEWS.analytics = () => {
    const days = +(R.f.adays || 14); const fb = R.f.anbot || '';
    const from = Date.now() - days * 86400000;
    const ts = S.tasks.filter((t) => t.createdAt >= from && (!fb || t.botId === fb));
    const done = ts.filter((t) => t.status === 'COMPLETED'); const failed = ts.filter((t) => t.status === 'FAILED'); const fin = done.length + failed.length;
    const mins = Math.round(ts.reduce((a, t) => a + (t.finishedAt && t.startedAt ? t.finishedAt - t.startedAt : 0), 0) / 60000);
    const acts = S.activity.filter((e) => e.at >= from && (!fb || e.botId === fb));
    const buckets = []; for (let i = days - 1; i >= 0; i--) { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - i); buckets.push({ d, c: 0, f: 0 }); }
    for (const t of ts) { const at = t.finishedAt || t.updatedAt; const b = buckets.find((x) => at >= x.d.getTime() && at < x.d.getTime() + 86400000); if (b) { if (t.status === 'COMPLETED') b.c++; if (t.status === 'FAILED') b.f++; } }
    const max = Math.max(1, ...buckets.map((b) => b.c + b.f));
    const steps = ts.flatMap((t) => t.steps); const verbs = {}; for (const s of steps) { const v = s.label.split(/[ :“]/)[0]; verbs[v] = (verbs[v] || 0) + 1; }
    const perBot = S.bots.map((b) => { const m = ts.filter((t) => t.botId === b.id); const d = m.filter((t) => t.status === 'COMPLETED').length; const f2 = m.filter((t) => t.status === 'FAILED').length; return { b, n: m.length, d, f: f2 }; }).filter((x) => x.n);
    const routRuns = S.routines.flatMap((r) => r.history.filter((h) => h.at >= from && (!fb || r.botId === fb)));
    const routOk = routRuns.filter((h) => h.taskId && task(h.taskId)?.status === 'COMPLETED').length;
    return ['Analytics', `<div class="toolbar"><div class="seg">${[7, 14, 30, 90].map((d) => `<button class="${days === d ? 'on' : ''}" data-act="filter" data-k="adays" data-v="${d}">${d} days</button>`).join('')}</div><select data-act-change="anbot"><option value="">All bots</option>${S.bots.map((x) => `<option value="${x.id}" ${fb === x.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select></div>
      <div class="grid g4" style="margin-bottom:14px"><div class="card stat"><span>Tasks completed</span><b>${done.length}</b><small>${ts.length} created</small></div><div class="card stat"><span>Success rate</span><b>${fin ? Math.round(done.length / fin * 100) : 0}%</b><small>${failed.length} failed</small></div><div class="card stat"><span>Time spent working</span><b>${mins >= 120 ? `${(mins / 60).toFixed(1)} h` : mins >= 1 ? `${mins} min` : `${Math.round(ts.reduce((a, t) => a + (t.finishedAt && t.startedAt ? t.finishedAt - t.startedAt : 0), 0) / 1000)} s`}</b><small>measured from start to finish</small></div><div class="card stat"><span>Approvals</span><b>${acts.filter((e) => e.kind === 'approval').length}</b><small>${acts.filter((e) => e.kind === 'approved').length} granted · ${acts.filter((e) => e.kind === 'denied').length} denied</small></div></div>
      <div class="grid g2" style="margin-bottom:14px"><div class="card"><h3>Finished per day</h3><div class="bars">${buckets.map((b) => `<div title="${b.d.toLocaleDateString('en-GB')}: ${b.c} done, ${b.f} failed"><i style="height:${b.c / max * 100}%;background:var(--accent)"></i><i style="height:${b.f / max * 100}%;background:var(--bad)"></i><span>${days <= 14 ? b.d.getDate() : ''}</span></div>`).join('')}</div><div class="legend"><span><i style="background:var(--accent)"></i>Completed</span><span><i style="background:var(--bad)"></i>Failed</span></div></div>
      <div class="card"><h3>Most-used actions</h3>${Object.keys(verbs).length ? Object.entries(verbs).sort((a, b) => b[1] - a[1]).slice(0, 7).map(([v, n]) => `<div class="kv"><span>${esc(v)}</span><b>${n}</b></div>`).join('') : '<div class="empty">No actions yet.</div>'}<div class="kv"><span>Routine runs</span><b>${routRuns.length} · ${routRuns.length ? Math.round(routOk / routRuns.length * 100) : 0}% completed</b></div><div class="kv"><span>Files created</span><b>${S.files.filter((f) => ts.some((t) => t.id === f.taskId)).length}</b></div><div class="kv" style="border:0"><span>Errors logged</span><b>${acts.filter((e) => e.kind === 'error').length}</b></div></div></div>
      <div class="card"><h3>By bot</h3>${perBot.length ? `<table class="t"><tr><th>Bot</th><th>Tasks</th><th>Completed</th><th>Failed</th><th>Success</th></tr>${perBot.map((x) => `<tr><td style="display:flex;gap:8px;align-items:center">${av(x.b, 22, false)}${esc(x.b.name)}</td><td>${x.n}</td><td>${x.d}</td><td>${x.f}</td><td>${x.d + x.f ? Math.round(x.d / (x.d + x.f) * 100) : 0}%</td></tr>`).join('')}</table>` : '<div class="empty">No tasks in this period.</div>'}
      <p class="helpline" style="margin-top:12px">“Time saved” isn't shown: there's no honest way to measure it yet, so Nebula won't invent a number.</p></div>`];
  };

  VIEWS.settings = () => ['Settings', `<div class="grid g2"><div class="card"><h3>You</h3>
      <div class="field"><label>Your name</label><input type="text" id="pfName" value="${esc(S.profile.name || '')}"></div>
      <div class="field"><label>What you do</label><input type="text" id="pfWork" value="${esc(S.profile.work || '')}"><div class="help">Your bots use this for context.</div></div>
      <div class="two"><div class="field"><label>Team size</label><input type="number" id="pfTeam" min="1" value="${esc(S.profile.team || '')}"></div><div class="field"><label>How you heard about us</label><input type="text" id="pfHeard" value="${esc(S.profile.heard || '')}"></div></div>
      <button class="pillbtn pri" data-act="pf-save">Save</button></div>
    <div class="card"><h3>Proactivity</h3><div class="seg" style="margin-bottom:12px">${['off', 'low', 'balanced', 'high'].map((k) => `<button class="${S.settings.proactivity === k ? 'on' : ''}" data-act="set" data-k="proactivity" data-v="${k}">${k[0].toUpperCase() + k.slice(1)}</button>`).join('')}</div><div class="helpline" style="margin-bottom:14px">Suggestions on Home come only from what really happened — failed tasks, waiting approvals, interrupted work, due dates, idle bots. Dismissed ones don't come back.</div>
      <div class="two"><div class="field"><label>Quiet hours from</label><input type="time" id="qFrom" value="${esc(S.settings.quietFrom || '')}"></div><div class="field"><label>to</label><input type="time" id="qTo" value="${esc(S.settings.quietTo || '')}"></div></div><button class="pillbtn" data-act="quiet-save">Save quiet hours</button></div>
    <div class="card"><h3>Notifications</h3><div class="opt ${S.settings.notifyOn ? 'on' : ''}" data-act="set" data-k="notifyOn" data-v="${S.settings.notifyOn ? '' : '1'}"><span class="box"></span><div><b>Desktop notifications</b><small>When bots finish, fail, or need you — and this window isn't in front. Fine-tune per bot in its Settings.</small></div></div></div>
    <div class="card"><h3>Workspace</h3><div class="chips"><button class="pillbtn" data-act="replay">Replay the intro</button><button class="pillbtn" data-act="redo-onb">Answer the welcome questions again</button><button class="pillbtn" data-act="nebula-settings">Nebula settings (AI key, voice)</button><button class="pillbtn" data-act="files-folder">Open Files folder</button></div>
      <div class="kv" style="margin-top:12px"><span>Where it runs</span><b>On this PC, in Nebula</b></div><div class="kv"><span>Version</span><b>Nebula Workspace preview · RovBots phase 1–2</b></div></div></div>`];

  // ---------------------------------------------------------------- modals: assign, routine, team, skill, task drawer
  function assignModal(botId, text = '') {
    const bots = S.bots.filter((b) => !b.archived && !b.draft);
    if (!bots.length) { toast('Create a bot first.'); wizard('pick'); return; }
    modal('Assign a task', `<div class="field"><label>Bot</label><select id="asBot">${bots.map((b) => `<option value="${b.id}" ${b.id === (botId || bots.find((x) => x.primary)?.id) ? 'selected' : ''}>${esc(b.name)} — ${esc(b.role)}</option>`).join('')}</select></div>
      <div class="field"><label>What should it do?</label><textarea id="asText" rows="5" placeholder="Be specific: the goal, what to hand back, and anything it should know.">${esc(text)}</textarea></div>
      <div class="three"><div class="field"><label>Title (optional)</label><input type="text" id="asTitle" placeholder="Short name"></div><div class="field"><label>Priority</label><select id="asPri"><option value="normal">Normal</option><option value="high">High</option><option value="urgent">Urgent</option><option value="low">Low</option></select></div><div class="field"><label>Due (optional)</label><input type="datetime-local" id="asDue"></div></div>
      <div class="helpline">It runs in the background — you can close this window. It shows here, in the island and in notifications.</div>`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" data-act="assign-go">Assign</button>');
  }
  function routineModal(r = {}) {
    const s = r.schedule || { type: 'weekdays', time: '07:00', days: [1] };
    const bots = S.bots.filter((b) => !b.archived);
    if (!bots.length) { toast('Create a bot first.'); return; }
    R.rd = { ...r, schedule: { ...s, days: [...(s.days || [1])] } };
    modal(r.id ? 'Edit routine' : 'New routine', `<div class="two"><div class="field"><label>Routine name</label><input type="text" id="rName" value="${esc(r.name || '')}" placeholder="Morning briefing"></div><div class="field"><label>Bot</label><select id="rBot">${bots.map((b) => `<option value="${b.id}" ${b.id === (r.botId || R.rbot) ? 'selected' : ''}>${esc(b.name)}</option>`).join('')}</select></div></div>
      <div class="field"><label>What should it do each time?</label><textarea id="rIns" rows="4" placeholder="Check what finished overnight, what's waiting for me, and prepare a short briefing with what needs my attention first.">${esc(r.instructions || '')}</textarea></div>
      <div class="three"><div class="field"><label>When</label><select id="rType">${[['once', 'Once'], ['daily', 'Every day'], ['weekdays', 'Weekdays'], ['weekly', 'Weekly'], ['monthly', 'Monthly'], ['yearly', 'Yearly'], ['interval', 'Every N minutes'], ['task', 'When a task finishes'], ['manual', 'Only when I press Run']].map(([k, l]) => `<option value="${k}" ${s.type === k ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        <div class="field" id="rTimeF"><label>Time</label><input type="time" id="rTime" value="${esc(s.time || '07:00')}"></div><div class="field" id="rExtraF"></div></div>
      <div class="field" id="rDaysF"></div>
      <div class="two"><div class="field"><label>Where to put the result (optional)</label><input type="text" id="rOut" value="${esc(r.output || '')}" placeholder="e.g. a Word document; in the chat"></div><div class="field"><label>Approvals</label><select id="rAppr"><option value="bot" ${r.approval !== 'always' ? 'selected' : ''}>Follow the bot's autonomy</option><option value="always" ${r.approval === 'always' ? 'selected' : ''}>Ask before every action</option></select></div></div>
      <div class="two"><div class="opt ${r.notify !== false ? 'on' : ''}" data-act="r-toggle" data-k="notify"><span class="box"></span><div><b>Notify me</b><small>Via the bot's notification settings</small></div></div><div class="opt ${r.active !== false ? 'on' : ''}" data-act="r-toggle" data-k="active"><span class="box"></span><div><b>Active</b><small>Turn off to keep it without running</small></div></div></div>
      <div class="helpline">Time zone: ${esc(Intl.DateTimeFormat().resolvedOptions().timeZone)}. Runs while Nebula is running on this PC.</div>`, `${r.id ? `<button class="pillbtn danger" data-act="routine-del" data-id="${r.id}">Delete</button><span class="sp"></span>` : ''}<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" data-act="routine-save">${r.id ? 'Save' : 'Create routine'}</button>`);
    R.rd.notify = r.notify !== false; R.rd.active = r.active !== false;
    const sync = () => {
      const t = $('rType').value; const sd = R.rd.schedule; sd.type = t;
      $('rTimeF').hidden = ['interval', 'task', 'manual'].includes(t);
      $('rExtraF').innerHTML = t === 'once' || t === 'yearly' ? `<label>Date</label><input type="date" id="rDate" value="${esc(sd.date || new Date().toISOString().slice(0, 10))}">` : t === 'monthly' ? `<label>Day of month</label><input type="number" id="rDom" min="1" max="31" value="${sd.dom || 1}">` : t === 'interval' ? `<label>Every (minutes, min 15)</label><input type="number" id="rEvery" min="15" value="${sd.every || 60}">` : t === 'task' ? `<label>Which bot's tasks</label><select id="rTBot"><option value="">Any bot</option>${S.bots.map((b) => `<option value="${b.id}" ${sd.bot === b.id ? 'selected' : ''}>${esc(b.name)}</option>`).join('')}</select>` : '';
      $('rDaysF').innerHTML = t === 'weekly' ? `<label>Days</label><div class="chips">${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((d, i) => `<button class="qa ${sd.days.includes(i) ? 'on' : ''}" data-act="r-day" data-i="${i}">${d}</button>`).join('')}</div>` : t === 'task' ? '<div class="helpline">Runs after a task of that bot completes. A routine\'s own tasks never trigger routines, so they can\'t loop.</div>' : '';
    };
    $('rType').onchange = sync; sync();
  }
  function teamModal(tm = {}) {
    const bots = S.bots.filter((b) => !b.archived && !b.draft);
    if (bots.length < 2) { toast('A team needs at least two bots — create another first.', true); return; }
    R.td = { members: [...(tm.members || [])], coordinator: tm.coordinator || null, mode: tm.mode || 'manager', id: tm.id };
    const draw = () => modal(tm.id ? 'Edit team' : 'Create a team', `<div class="two"><div class="field"><label>Team name</label><input type="text" id="tmName" value="${esc(tm.name || $('tmName')?.value || '')}" placeholder="Marketing Team"></div><div class="field"><label>Purpose</label><input type="text" id="tmPurpose" value="${esc(tm.purpose || $('tmPurpose')?.value || '')}" placeholder="Plan and run campaigns"></div></div>
      <div class="field"><label>Members (pick at least two)</label>${bots.map((b) => `<div class="opt ${R.td.members.includes(b.id) ? 'on' : ''}" data-act="tm-mem" data-id="${b.id}"><span class="box"></span>${av(b, 26, false)}<div style="flex:1"><b>${esc(b.name)}</b><small>${esc(b.role)}</small></div>${R.td.members.includes(b.id) ? `<button class="pillbtn ${R.td.coordinator === b.id ? 'pri' : ''}" data-act="tm-coord" data-id="${b.id}">${R.td.coordinator === b.id ? 'Coordinator' : 'Make coordinator'}</button>` : ''}</div>`).join('')}</div>
      <div class="field"><label>How they work together</label><div class="seg">${[['manager', 'Manager'], ['pipeline', 'Pipeline'], ['review', 'Create + review'], ['debate', 'Debate']].map(([k, l]) => `<button class="${R.td.mode === k ? 'on' : ''}" data-act="tm-mode" data-v="${k}">${l}</button>`).join('')}</div><div class="help">Bots take turns (one hand-off at a time) — they don't run in parallel yet.</div></div>
      <div class="field"><label>Team instructions (shared with every member on team tasks)</label><textarea id="tmIns" rows="3">${esc(tm.instructions || '')}</textarea></div>`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" data-act="team-save">Save team</button>');
    R.tdraw = () => { tm = { ...tm, name: $('tmName').value, purpose: $('tmPurpose').value, instructions: $('tmIns').value }; draw(); };
    draw();
  }
  function skillModal(s = {}) {
    R.sk = { ...s, steps: [...(s.steps || [])], tools: [...(s.tools || [])] };
    const toolsOk = Object.entries(S.catalogue.tools).filter(([k, v]) => !['plan', 'ask_user', 'finish_task'].includes(k) && v.label);
    modal(s.id ? `Skill · ${esc(s.name)}` : 'New skill', `<div class="two"><div class="field"><label>Name</label><input type="text" id="skName" value="${esc(s.name || '')}"></div><div class="field"><label>Category</label><input type="text" id="skCat" value="${esc(s.category || 'General')}"></div></div>
      <div class="field"><label>Description</label><input type="text" id="skDesc" value="${esc(s.description || '')}"></div>
      <div class="field"><label>Instructions</label><textarea id="skIns" rows="4">${esc(s.instructions || '')}</textarea></div>
      <div class="field"><label>Steps (one per line)</label><textarea id="skSteps" rows="4">${esc((s.steps || []).join('\n'))}</textarea></div>
      <div class="three"><div class="field"><label>Inputs</label><input type="text" id="skIn" value="${esc(s.inputs || '')}"></div><div class="field"><label>Outputs</label><input type="text" id="skOut" value="${esc(s.outputs || '')}"></div><div class="field"><label>If it fails</label><input type="text" id="skFail" value="${esc(s.failure || '')}"></div></div>
      <div class="field"><label>Tools it needs</label><div class="chips">${toolsOk.map(([k, v]) => `<button class="qa ${R.sk.tools.includes(k) ? 'on' : ''}" data-act="sk-tool" data-k="${k}">${esc(v.label)}</button>`).join('')}</div></div>
      <div class="field"><label>Examples (optional)</label><textarea id="skEx" rows="2">${esc(s.examples || '')}</textarea></div>
      ${s.id ? `<div class="field"><label>Used by</label><div class="chips">${S.bots.filter((b) => !b.archived).map((b) => `<button class="qa ${b.skills.includes(s.id) ? 'on' : ''}" data-act="sk-assign" data-bot="${b.id}" data-id="${s.id}">${esc(b.name)}</button>`).join('')}</div></div>
        <div class="field"><label>Versions</label>${s.versions?.length ? s.versions.map((v) => `<div class="kv"><span>v${v.version} · ${new Date(v.at).toLocaleString('en-GB')}</span><a class="linkbtn" data-act="sk-roll" data-id="${s.id}" data-v="${v.version}">Roll back to this</a></div>`).join('') : '<div class="helpline">No earlier versions.</div>'}</div>
        <div class="field"><label>Test it</label><div class="ask" style="margin:0"><select id="skBot" style="width:auto;border:0;background:none">${S.bots.filter((b) => !b.archived && !b.draft).map((b) => `<option value="${b.id}">${esc(b.name)}</option>`).join('')}</select><input id="skInput" placeholder="Input for the test run (optional)"><button class="pillbtn" data-act="sk-test" data-id="${s.id}">Test run</button></div></div>` : ''}
      <div class="helpline">Never put passwords, keys or secrets in a skill — Nebula refuses to save them.</div>`,
    `${s.id ? `<button class="pillbtn danger" data-act="sk-del" data-id="${s.id}">Delete</button><button class="pillbtn" data-act="sk-dup" data-id="${s.id}">Duplicate</button><button class="pillbtn" data-act="sk-export" data-id="${s.id}">Export</button><span class="sp"></span>` : ''}<button class="pillbtn" data-act="close">Cancel</button>${s.id && S.bots.some((b) => b.skills.includes(s.id)) ? '<button class="pillbtn" data-act="sk-save" data-all="0" title="Save as a new skill; bots keep the current one">Save as new version only for new use</button>' : ''}<button class="pillbtn pri" data-act="sk-save" data-all="1">${s.id ? 'Save (applies to all bots using it)' : 'Save skill'}</button>`, 'xl');
  }
  function taskDrawer(id) {
    const t = task(id); if (!t) { closeLayer(); return; }
    R.task = id;
    const b = bot(t.botId); const c = conv(t.conversationId);
    const result = c ? [...c.messages].reverse().find((m) => m.role === 'assistant' && m.taskId === t.id) : null;
    const files = (t.fileIds || []).map((f) => S.files.find((x) => x.id === f)).filter(Boolean);
    const plan = t.plan || []; const doneN = t.steps.filter((s) => s.status === 'DONE').length;
    const logs = S.activity.filter((e) => e.taskId === t.id);
    const canPause = ['IN_PROGRESS', 'QUEUED'].includes(t.status); const canResume = t.status === 'PAUSED';
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(t.status);
    const keepSteer = $('steerIn') ? $('steerIn').value : '';
    drawer(`<div class="mhead">${av(b, 30)}<b style="font-size:15px">${esc(t.title)}</b><button class="ico" data-act="close">${I('x')}</button></div>
      <div class="mbody" id="drawerBody">
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:14px">${tStatus(t)}<span class="tag">${esc(b ? b.name : 'Removed bot')}</span><span class="tag">from ${esc(t.source || 'chat')}</span>${t.parentId ? `<a class="linkbtn" data-act="task" data-id="${t.parentId}">← parent task</a>` : ''}${t.retries ? `<span class="tag">retry ${t.retries}</span>` : ''}</div>
        ${t.status === 'REQUIRES_INPUT' && t.input ? `<div style="margin-bottom:14px">${inputCard(t)}</div>` : ''}
        ${t.activity && t.status === 'IN_PROGRESS' ? `<div class="typing" style="margin-bottom:12px"><i></i>${esc(t.activity)}</div>` : ''}
        <div class="chips" style="margin-bottom:16px">${canPause ? `<button class="pillbtn" data-act="task-act" data-a="pause" data-id="${t.id}">${I('pause')}Pause</button>` : ''}${canResume ? `<button class="pillbtn pri" data-act="task-act" data-a="resume" data-id="${t.id}">${I('play')}Resume</button>` : ''}${!terminal ? `<button class="pillbtn danger" data-act="task-act" data-a="cancel" data-id="${t.id}">Stop</button>` : ''}${['FAILED', 'CANCELLED'].includes(t.status) ? `<button class="pillbtn pri" data-act="task-act" data-a="retry" data-id="${t.id}">${I('retry')}Retry</button>` : ''}${c ? `<button class="pillbtn" data-act="open-task-chat" data-id="${t.id}">Open conversation</button>` : ''}${terminal || ['QUEUED', 'PAUSED'].includes(t.status) ? `<button class="pillbtn" data-act="task-del" data-id="${t.id}">Delete</button>` : ''}</div>
        ${!terminal ? `<div class="field"><label>Add an instruction while it works</label><div class="ask" style="margin:0"><input id="steerIn" placeholder="e.g. Only use sources from 2026" value="${esc(keepSteer)}"><button class="pillbtn" data-act="steer" data-id="${t.id}">Add</button></div></div>` : ''}
        <div class="two"><div class="field"><label>Priority</label><select data-act-change="t-pri" data-id="${t.id}">${['urgent', 'high', 'normal', 'low'].map((p) => `<option ${t.priority === p ? 'selected' : ''} value="${p}">${p[0].toUpperCase() + p.slice(1)}</option>`).join('')}</select></div>
          <div class="field"><label>Assigned bot</label><select data-act-change="t-bot" data-id="${t.id}" ${t.status === 'IN_PROGRESS' ? 'disabled title="Pause or wait for it to finish first"' : ''}>${S.bots.filter((x) => !x.archived).map((x) => `<option value="${x.id}" ${x.id === t.botId ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select></div></div>
        ${t.description && t.description !== t.title ? `<div class="field"><label>What it was asked</label><div class="bub" style="max-height:180px;overflow:auto">${md(t.description)}</div></div>` : ''}
        ${plan.length ? `<div class="field"><label>Plan · ${doneN}/${plan.length}</label><div class="prog" style="margin-bottom:8px"><i style="width:${progress(t) || 0}%"></i></div><ul class="steps">${plan.map((p, i) => `<li class="${i < doneN ? 'DONE' : 'PLAN'}"><i>${i < doneN ? '✓' : i + 1}</i><div>${esc(p)}</div></li>`).join('')}</ul></div>` : ''}
        <div class="field"><label>What it did</label>${t.steps.length ? `<ul class="steps">${t.steps.map((s) => `<li class="${s.status}"><i>${s.status === 'DONE' ? '✓' : s.status === 'WAITING' ? '…' : '!'}</i><div>${esc(s.label)}${s.verified ? ' <span class="tag">verified</span>' : ''}<small>${esc(s.detail || '')}${s.at ? ` · ${clock(s.at)}` : ''}</small></div></li>`).join('')}</ul>` : '<div class="helpline">No actions yet.</div>'}</div>
        ${(t.children || []).length ? `<div class="field"><label>Delegated</label><div class="rows">${t.children.map((cid) => task(cid)).filter(Boolean).map(taskRow).join('')}</div></div>` : ''}
        ${files.length ? `<div class="field"><label>Files</label>${files.map((f) => `<span class="fchip" data-act="file" data-id="${f.id}"><i>${esc(fileExt(f))}</i>${esc(f.fileName)}</span>`).join('')}</div>` : ''}
        ${t.summary ? `<div class="field"><label>${t.status === 'FAILED' ? 'What went wrong' : 'Result'}</label><div class="bub">${md(t.summary)}</div></div>` : ''}
        ${result && result.text !== t.summary ? `<div class="field"><label>Full answer</label><div class="bub" style="max-height:340px;overflow:auto">${md(result.text)}</div></div>` : ''}
        <div class="field"><label>Log</label><div class="tl">${logs.map(evRow).join('') || '<div class="helpline">—</div>'}</div></div>
        <div class="kv"><span>Created</span><b>${new Date(t.createdAt).toLocaleString('en-GB')}</b></div>${t.startedAt ? `<div class="kv"><span>Started</span><b>${new Date(t.startedAt).toLocaleString('en-GB')}</b></div>` : ''}${t.finishedAt ? `<div class="kv"><span>Finished</span><b>${new Date(t.finishedAt).toLocaleString('en-GB')} (${Math.max(1, Math.round((t.finishedAt - (t.startedAt || t.createdAt)) / 1000))} s)</b></div>` : ''}${t.dueAt ? `<div class="kv"><span>Due</span><b>${new Date(t.dueAt).toLocaleString('en-GB')}</b></div>` : ''}<div class="kv"><span>Checkpoint</span><b>${t.hasCheckpoint ? 'Saved — can resume after a restart' : '—'}</b></div>
      </div>`);
  }

  // ---------------------------------------------------------------- actions
  const A = {};
  A.nav = (d) => go(d.v);
  A.bot = (d) => openBot(d.id);
  A.chat = (d) => { const c = conv(d.cid); if (c) { if (c.taskId) taskDrawer(c.taskId); else openBot(c.botId, 'chat', c.id); } };
  A.tab = (d) => { R.tab = d.t; if (d.t !== 'settings') R.edit = null; render(true); };
  A.close = () => closeLayer();
  A.scrim = (_d, e) => { if (e.target.classList.contains('scrim')) { closeLayer(); R.task = null; } };
  A.create = () => wizard('pick');
  A.assign = (d) => assignModal(d.bot || (R.view === 'bot' ? R.bot : null));
  A['assign-go'] = async () => {
    const text = $('asText').value.trim(); if (!text) { toast('Describe the task.', true); return; }
    const due = $('asDue').value ? new Date($('asDue').value).getTime() : null;
    const id = await call('ws:assign', { botId: $('asBot').value, description: text, title: $('asTitle').value.trim() || undefined, priority: $('asPri').value, dueAt: due });
    if (id) { closeLayer(); toast('Assigned — it\'s running in the background.'); setTimeout(() => taskDrawer(id), 250); }
  };
  A['home-task'] = async () => { const v = $('homeAsk').value.trim(); const p = S.bots.find((b) => b.primary && !b.archived) || S.bots.find((b) => !b.archived && !b.draft); if (!v || !p) return; const id = await call('ws:assign', { botId: p.id, description: v }); if (id) { $('homeAsk').value = ''; toast(`${p.name} is on it.`); } };
  A['home-chat'] = async () => { const v = $('homeAsk').value.trim(); const p = S.bots.find((b) => b.primary && !b.archived) || S.bots.find((b) => !b.archived && !b.draft); if (!v || !p) return; const cid = await call('ws:chat', { botId: p.id, text: v }); if (cid) { $('homeAsk').value = ''; openBot(p.id, 'chat', cid); } };
  A.voice = () => { api.send('ws:voice'); toast('Nebula is listening — say what you need.'); };
  A.approvals = () => { const ap = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT'); if (!ap.length) { toast('Nothing is waiting for you.'); return; } modal(`Waiting for you · ${ap.length}`, ap.map((t) => `<div style="margin-bottom:12px">${inputCard(t)}</div>`).join('')); R.apprOpen = true; };
  A.computer = () => { const b = S.bots.find((x) => !x.archived && !x.draft); if (b) openBot(b.id, 'computer'); else toast('Create a bot first.'); };
  A.filter = (d) => { R.f[d.k] = d.v; render(true); };
  A.url = (d) => api.send('open:url', d.url);
  A.file = (d) => call('ws:file', { id: d.id, act: 'open' });
  A['file-menu'] = (d, e) => menu(e, [{ label: 'Open', fn: () => call('ws:file', { id: d.id, act: 'open' }) }, { label: 'Show in folder', fn: () => call('ws:file', { id: d.id, act: 'show' }) }, { label: 'Pin / unpin', fn: () => call('ws:pin', { kind: 'file', id: d.id }) }, '-', { label: 'Delete file', danger: true, fn: () => confirmBox('Delete this file?', 'The file is removed from your PC.', 'Delete', () => call('ws:file', { id: d.id, act: 'delete' })) }]);
  A['files-folder'] = () => api.send('ws:files-folder');
  A.pin = (d) => call('ws:pin', { kind: d.k, id: d.id });
  A.task = (d) => taskDrawer(d.id);
  A['task-act'] = async (d) => { await call('ws:task-act', { id: d.id, act: d.a }); if (d.a === 'retry') toast('Retrying.'); };
  A['task-del'] = (d) => confirmBox('Delete this task?', 'Its record and log are removed. Files it made stay in your Library.', 'Delete', async () => { await call('ws:task-act', { id: d.id, act: 'delete' }); R.task = null; });
  A.steer = async (d) => { const v = $('steerIn').value.trim(); if (!v) return; $('steerIn').value = ''; await call('ws:task-act', { id: d.id, act: 'steer', arg: v }, 'Added — it\'ll take it into account at its next step.'); };
  A['open-task-chat'] = (d) => { const t = task(d.id); if (!t) return; closeLayer(); R.task = null; openBot(t.botId, 'chat', t.conversationId); };
  A.answer = async (d) => { await call('ws:answer', { taskId: d.id, choices: [d.c] }); if (/^Deny/.test(d.c)) toast('Denied. The bot won\'t try that again.'); };
  A['answer-text'] = async (d) => { const v = $(`ans-${d.id}`).value.trim(); if (!v) return; await call('ws:answer', { taskId: d.id, choices: [v] }); };
  A['edit-approval'] = (d) => {
    const t = task(d.id); if (!t?.input?.card) return;
    modal('Edit before approving', `<p class="helpline" style="margin-top:0">Change what the bot will do, then approve the edited version.</p><textarea id="apEdit" rows="12" style="font:12.5px ui-monospace,Consolas,monospace">${esc(JSON.stringify(t.input.card.args, null, 2))}</textarea>`, `<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="apGo">Approve edited</button>`);
    $('apGo').onclick = async () => { let a; try { a = JSON.parse($('apEdit').value); } catch { toast('That isn\'t valid JSON.', true); return; } closeLayer(); await call('ws:answer', { taskId: d.id, choices: ['Approve'], edited: a }); };
  };
  A.sugg = async (d) => {
    const g = S.suggestions.find((x) => x.key === d.key); if (!g) return; const a = g.action;
    if (a.view === 'create') wizard('pick'); else if (a.view) go(a.view); else if (a.bot) openBot(a.bot); else if (a.task && a.act) { await call('ws:task-act', { id: a.task, act: a.act }); toast('Done.'); } else if (a.task) taskDrawer(a.task);
  };
  A.dismiss = (d) => call('ws:dismiss', { key: d.key });

  // bots
  A['bot-act'] = async (d) => {
    const r = await call('ws:bot-act', { id: d.id, act: d.a });
    if (d.a === 'duplicate' && r) { toast('Duplicated.'); openBot(r, 'settings'); }
    if (d.a === 'export' && r) toast('Exported.');
    if (d.a === 'archive') { toast('Archived — find it under My Bots → Archived.'); go('bots'); }
  };
  A['bot-menu'] = (d, e) => { const b = bot(d.id); menu(e, [{ label: 'Edit settings', fn: () => { R.tab = 'settings'; render(true); } }, { label: S.pins.some((p) => p.kind === 'bot' && p.id === b.id) ? 'Unpin from sidebar' : 'Pin to sidebar', fn: () => call('ws:pin', { kind: 'bot', id: b.id }) }, { label: b.primary ? 'Chief of Staff ✓' : 'Make Chief of Staff', fn: () => call('ws:bot-act', { id: b.id, act: 'primary' }) }, { label: 'Duplicate', fn: () => A['bot-act']({ id: b.id, a: 'duplicate' }) }, { label: 'Export configuration', fn: () => A['bot-act']({ id: b.id, a: 'export' }) }, '-', { label: b.archived ? 'Restore' : 'Archive', fn: () => A['bot-act']({ id: b.id, a: b.archived ? 'unarchive' : 'archive' }) }, { label: 'Delete…', danger: true, fn: () => A['bot-delete']({ id: b.id }) }]); };
  A['bot-delete'] = (d) => {
    const b = bot(d.id);
    modal(`Delete ${esc(b.name)}?`, `<p style="margin-top:0;color:var(--dim)">This removes the bot, its knowledge (${b.knowledge.length}), memories (${b.memories.length}), settings and routines. It can't be undone — export it first if you might want it back.</p><div class="opt" data-act="toggle-self"><span class="box"></span><div><b>Also delete its work</b><small>Its tasks, conversations and the files it made (${b.counts.done + b.counts.failed + b.counts.open} tasks). Leave unticked to keep them.</small></div></div><div class="field"><label>Type the bot's name to confirm</label><input type="text" id="delName" placeholder="${esc(b.name)}"></div>`, `<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn danger" id="delGo">Delete forever</button>`);
    $('delGo').onclick = async () => { if ($('delName').value.trim() !== b.name) { toast('The name doesn\'t match.', true); return; } const work = $('layer').querySelector('.opt').classList.contains('on'); closeLayer(); await call('ws:bot-act', { id: b.id, act: 'delete', arg: { work } }, `${b.name} was deleted.`); go('bots'); };
  };
  A['toggle-self'] = (_d, e) => e.currentTarget.classList.toggle('on');
  A['import-bot'] = async () => { const id = await call('ws:bot-import'); if (id) { toast('Imported.'); openBot(id, 'settings'); } };
  A['edit-draft'] = (d) => { const b = bot(d.id); WZ = { step: 0, d: JSON.parse(JSON.stringify(b)), first: '' }; drawWizard(); };

  // chat
  A['new-chat'] = async (d) => { const cid = await call('ws:new-chat', { botId: d.id }); if (cid) { R.cid = cid; replyTo = null; attach = []; render(true); setTimeout(() => $('composer')?.focus(), 60); } };
  A['pick-chat'] = (d) => { R.cid = d.cid; replyTo = null; render(true); };
  A.starter = (d) => { $('composer').value = d.t; A.send(); };
  A.send = async () => {
    const el = $('composer'); if (!el) return;
    let text = el.value.trim(); if (!text && !attach.length) return;
    const b = bot(R.bot);
    if (replyTo) text = `${replyTo.text.split('\n').slice(0, 4).map((l) => `> ${l.slice(0, 200)}`).join('\n')}\n\n${text}`;
    for (const a of attach) text += `\n\n[Attached file: ${a.name}]\n\`\`\`\n${a.text}\n\`\`\``;
    const rt = replyTo; el.value = ''; delete draft[R.cid]; replyTo = null; attach = []; grow(el);
    const cid = await call('ws:chat', { botId: b.id, cid: R.cid, text, replyTo: rt?.id });
    if (cid) { R.cid = cid; render(true); }
  };
  A['stop-chat'] = () => call('ws:conv-act', { cid: R.cid, act: 'stop' });
  A.attach = () => {
    const inp = document.createElement('input'); inp.type = 'file'; inp.multiple = true;
    inp.onchange = () => { for (const f of inp.files) { if (f.size > 300 * 1024) { toast(`${f.name} is over 300 KB.`, true); continue; } if (!/^text\/|json|xml|csv|javascript/.test(f.type) && !/\.(txt|md|csv|tsv|json|html?|xml|log|ya?ml|js|ts|py|java|kt|c|cpp|cs|go|rs|sql|css)$/i.test(f.name)) { toast(`${f.name}: only text files can be attached for now.`, true); continue; } const r = new FileReader(); r.onload = () => { attach.push({ name: f.name, text: String(r.result).slice(0, 300000) }); render(); }; r.readAsText(f); } };
    inp.click();
  };
  A['att-x'] = (d) => { attach.splice(+d.i, 1); render(); };
  const msgOf = (mid) => { const c = conv(R.cid); return c && c.messages.find((m) => m.id === mid); };
  A.reply = (d) => { const m = msgOf(d.mid); if (m) { replyTo = { id: m.id, text: m.text.replace(/^>.*\n?/gm, '').trim() }; render(); setTimeout(() => $('composer')?.focus(), 30); } };
  A['reply-x'] = () => { replyTo = null; render(); };
  A.copy = (d) => { const m = msgOf(d.mid); if (m) { navigator.clipboard.writeText(m.text).then(() => toast('Copied.'), () => toast('Couldn\'t copy.', true)); } };
  A['react-pick'] = (d, e) => menu(e, ['👍', '❤️', '🎉', '😂', '😮', '👀', '✅'].map((em) => ({ label: em, fn: () => call('ws:conv-act', { cid: R.cid, act: 'react', arg: { mid: d.mid, emoji: em } }) })));
  A.react = (d) => call('ws:conv-act', { cid: R.cid, act: 'react', arg: { mid: d.mid, emoji: d.e } });
  A['edit-msg'] = (d) => { const m = msgOf(d.mid); if (!m) return; confirmBox('Edit and resend?', 'Messages after this one are removed from the conversation, then your edited message is sent.', 'Edit', async () => { await call('ws:conv-act', { cid: R.cid, act: 'truncate', arg: m.id }); $('composer').value = m.text; grow($('composer')); $('composer').focus(); }, false); };
  A['retry-msg'] = async (d) => { const c = conv(R.cid); if (!c) return; const i = c.messages.findIndex((m) => m.id === d.mid); const u = [...c.messages.slice(0, i)].reverse().find((m) => m.role === 'user'); if (!u) return; await call('ws:conv-act', { cid: R.cid, act: 'truncate', arg: u.id }); await call('ws:chat', { botId: R.bot, cid: R.cid, text: u.text }); };
  A.branch = async (d) => { const cid = await call('ws:conv-act', { cid: R.cid, act: 'branch', arg: d.mid }); if (cid) { R.cid = cid; toast('Branched into a new chat.'); render(true); } };
  A.jump = (d) => { const el = document.getElementById(`m-${d.mid}`); if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); el.animate([{ background: 'rgba(139,92,255,.25)' }, { background: 'transparent' }], 1200); } };
  A.expand = (d) => { R.f[`x${d.mid}`] = !R.f[`x${d.mid}`]; render(); };
  A['conv-menu'] = (d, e) => { const c = conv(d.cid); menu(e, [{ label: 'Rename', fn: () => { modal('Rename chat', `<input type="text" id="rnIn" value="${esc(c.title)}">`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="rnGo">Rename</button>'); $('rnGo').onclick = async () => { await call('ws:conv-act', { cid: c.id, act: 'rename', arg: $('rnIn').value }); closeLayer(); }; } }, { label: c.pinned ? 'Unpin' : 'Pin', fn: () => call('ws:conv-act', { cid: c.id, act: 'pin' }) }, { label: c.archived ? 'Unarchive' : 'Archive', fn: () => call('ws:conv-act', { cid: c.id, act: 'archive' }) }, { label: 'Export as Markdown', fn: () => call('ws:conv-act', { cid: c.id, act: 'export' }) }, '-', { label: 'Delete chat', danger: true, fn: () => confirmBox('Delete this chat?', 'The conversation is removed. Tasks it started stay in Tasks.', 'Delete', async () => { await call('ws:conv-act', { cid: c.id, act: 'delete' }); R.cid = null; render(true); }) }]); };

  // knowledge + memory
  A['k-note'] = async (d) => { const id = d.id || await wzEnsureDraft(); modal('Add a note', `<div class="two"><div class="field"><label>Title</label><input type="text" id="knT" placeholder="House style"></div><div class="field"><label>Category</label><input type="text" id="knC" value="General"></div></div><div class="field"><label>Text</label><textarea id="knX" rows="10" placeholder="Paste or type anything it should know."></textarea></div>`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="knGo">Add</button>'); $('knGo').onclick = async () => { const r = await call('ws:knowledge', { botId: id, act: 'note', title: $('knT').value, text: $('knX').value, category: $('knC').value }); if (r) { toast('Learned.'); if (WZ && WZ.d.id === id && WZ.step === 4) drawWizard(); else closeLayer(); } }; };
  A['k-url'] = async (d) => { const id = d.id || await wzEnsureDraft(); modal('Add a link', '<div class="field"><label>Web address</label><input type="url" id="kuU" placeholder="https://…"></div><div class="field"><label>Category</label><input type="text" id="kuC" value="Web"></div><div class="helpline">Nebula reads the page once and saves its text. Pages behind a login or paywall can\'t be read.</div>', '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="kuGo">Read it</button>'); $('kuGo').onclick = async () => { $('kuGo').textContent = 'Reading…'; const r = await call('ws:knowledge', { botId: id, act: 'url', url: $('kuU').value, category: $('kuC').value }); if (r) { toast('Learned that page.'); if (WZ && WZ.d.id === id && WZ.step === 4) drawWizard(); else closeLayer(); } else $('kuGo').textContent = 'Read it'; }; };
  A['k-files'] = async (d) => { const id = d.id || await wzEnsureDraft(); const r = await call('ws:knowledge', { botId: id, act: 'files', category: 'Files' }); if (r && r.added) toast(`Learned ${r.added} file${r.added > 1 ? 's' : ''}.${r.errors.length ? ` ${r.errors.length} skipped.` : ''}`); if (r && r.errors.length) toast(r.errors[0], true); if (WZ && WZ.d.id === id && WZ.step === 4) setTimeout(drawWizard, 200); };
  A['k-remove'] = (d) => confirmBox('Remove this knowledge?', 'The bot will no longer know it.', 'Remove', async () => { await call('ws:knowledge', { botId: d.id, act: 'remove', kid: d.k }); if (WZ && WZ.step === 4) setTimeout(drawWizard, 150); });
  A['k-preview'] = async (d) => { const t = await call('ws:knowledge', { botId: d.id, act: 'preview', kid: d.k }); if (t != null) modal('Preview', `<pre style="white-space:pre-wrap;font:12.5px/1.5 ui-monospace,Consolas,monospace;margin:0;user-select:text">${esc(t)}</pre>`, '', 'xl'); };
  A['k-cat'] = (d) => { const b = bot(d.id); const k = b.knowledge.find((x) => x.id === d.k); modal('Category', `<input type="text" id="kcIn" value="${esc(k.category)}">`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="kcGo">Save</button>'); $('kcGo').onclick = async () => { await call('ws:knowledge', { botId: d.id, act: 'edit', kid: d.k, category: $('kcIn').value }); closeLayer(); }; };
  A['mem-add'] = async (d) => { const v = $('memIn').value.trim(); if (!v) return; await call('ws:memory', { botId: d.id, act: 'add', text: v }); $('memIn').value = ''; };
  A['mem-remove'] = (d) => call('ws:memory', { botId: d.id, act: 'remove', mid: d.m });
  A['mem-edit'] = (d) => { const m = bot(d.id).memories.find((x) => x.id === d.m); modal('Edit memory', `<textarea id="meIn" rows="3">${esc(m.text)}</textarea>`, '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="meGo">Save</button>'); $('meGo').onclick = async () => { await call('ws:memory', { botId: d.id, act: 'edit', mid: d.m, text: $('meIn').value }); closeLayer(); }; };
  A['mem-toggle'] = (d) => call('ws:memory', { botId: d.id, act: 'toggle' });
  A['mem-clear'] = (d) => confirmBox('Clear all memories?', 'Everything this bot remembers is erased. Knowledge and settings stay.', 'Clear', () => call('ws:bot-act', { id: d.id, act: 'clear-memory' }));

  // the bot editor (wizard + settings)
  const edTarget = () => (WZ && $('layer').querySelector('.wiz') ? WZ.d : R.edit);
  const redraw = () => (WZ && $('layer').querySelector('.wiz') ? drawWizard() : render(true));
  function setPath(o, p, v) { const ks = p.split('.'); let x = o; for (const k of ks.slice(0, -1)) { x[k] = x[k] || {}; x = x[k]; } x[ks[ks.length - 1]] = v; }
  A['ed-set'] = (d) => { setPath(edTarget(), d.p, d.num ? +d.v : d.v); redraw(); };
  A['ed-toggle'] = (d) => { const e = edTarget(); setPath(e, d.p, val(e, d.p) === false); redraw(); };
  A['ed-cap'] = (d) => { const e = edTarget(); e.caps = e.caps.includes(d.c) ? e.caps.filter((x) => x !== d.c) : [...e.caps, d.c]; redraw(); };
  A['ed-allow'] = (d) => { const e = edTarget(); e.collab = e.collab || { allowed: [] }; const a = e.collab.allowed; e.collab.allowed = a.includes(d.id) ? a.filter((x) => x !== d.id) : [...a, d.id]; redraw(); };
  A['ed-surprise'] = () => { const e = edTarget(); e.shape = Math.floor(Math.random() * 10); e.color = PALETTE[Math.floor(Math.random() * PALETTE.length)]; redraw(); };
  A['ed-custom-role'] = () => { const e = edTarget(); e.role = ''; redraw(); };
  A['ed-reset'] = () => { R.edit = null; render(true); };
  A['ed-save'] = async () => { const e = R.edit; const id = await call('ws:bot-save', e, 'Saved.'); if (id) { R.edit = null; } };
  A['jump-sec'] = (d) => document.getElementById(`sec-${d.s}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  A['ins-ai'] = async (_d, e) => {
    const desc = $('insDesc').value.trim(); if (!desc) { toast('Describe the behaviour first.', true); return; }
    const btn = e.currentTarget; btn.textContent = 'Writing…'; btn.disabled = true;
    const r = await call('ws:instructions-ai', { text: desc, role: edTarget().role });
    btn.disabled = false; btn.innerHTML = `${I('spark')}Write the instructions with AI`;
    if (r) { const t = edTarget(); for (const [k] of INS) if (typeof r[k] === 'string' && r[k].trim()) t.ins[k] = r[k].trim(); toast('Drafted — read it over and edit anything.'); redraw(); }
  };
  A['wz-go'] = async (d) => { const i = +d.i; if (i >= 4 && !(await wzEnsureDraft())) return; WZ.step = i; drawWizard(); };
  A['wz-next'] = async () => { if (WZ.step === 0 && !WZ.d.name.trim()) { toast('Give your bot a name.', true); return; } if (WZ.step + 1 >= 4 && !(await wzEnsureDraft())) return; WZ.step++; drawWizard(); };
  A['wz-back'] = () => { WZ.step--; drawWizard(); };
  A['wz-scratch'] = () => { WZ.step = 0; drawWizard(); };
  A['wz-tpl'] = (d) => {
    const t = S.catalogue.templates.find((x) => x.key === d.k);
    Object.assign(WZ.d, { name: t.name, handle: slug(t.name), role: t.role, shape: t.shape, color: t.color, description: t.desc, welcome: `Hi, I'm ${t.name}. ${t.desc}`, caps: [...t.caps], autonomy: t.autonomy, template: t.key, primary: !!t.primary && !S.bots.some((b) => b.primary) });
    Object.assign(WZ.d.ins, { resp: t.resp || '', never: t.never || '', approach: t.approach || '', style: t.style || '', output: t.output || '' });
    WZ.step = 0; drawWizard();
  };
  A['wz-skill'] = (d) => { const s = WZ.d.skills; WZ.d.skills = s.includes(d.id) ? s.filter((x) => x !== d.id) : [...s, d.id]; drawWizard(); };
  A['wz-skill-tpl'] = async (d) => { const id = await call('ws:skill-template', { i: +d.i }); if (id) { WZ.d.skills.push(id); setTimeout(drawWizard, 150); } };
  A['wz-draft'] = async () => { const id = await call('ws:bot-save', { ...WZ.d, name: WZ.d.name || 'New bot', draft: true }, 'Saved as a draft.'); if (id) { WZ = null; closeLayer(); go('bots', { f: { ...R.f, bots: 'drafts' } }); R.f.bots = 'drafts'; } };
  A['wz-create'] = async () => {
    if (!WZ.d.name.trim()) { toast('Give your bot a name.', true); WZ.step = 0; drawWizard(); return; }
    const first = $('wzFirst').value.trim();
    const id = await call('ws:bot-save', { ...WZ.d, draft: false });
    if (!id) return;
    WZ = null; closeLayer();
    if (first) { await call('ws:assign', { botId: id, description: first }); toast('Created — and its first task is running.'); openBot(id, 'tasks'); }
    else { toast('Your bot is ready.'); openBot(id, 'chat'); }
  };

  // templates / marketplace
  A['tpl-add'] = async (d) => { const id = await call('ws:template', { key: d.k }); if (id) { toast('Added to your bots.'); openBot(id, 'chat'); } };
  A['tpl-preview'] = (d) => { const t = S.catalogue.templates.find((x) => x.key === d.k); modal(esc(t.name), `<div style="display:flex;gap:16px;align-items:center;margin-bottom:14px"><span class="av" style="width:64px;height:64px">${window.NB.charSvg(t.shape, t.color)}</span><div><b style="font-size:18px">${esc(t.name)}</b><div class="helpline">${esc(t.cat)} · by Nebula · version 1 · ${esc(S.catalogue.autonomy[t.autonomy].label)}</div></div></div><p style="color:var(--dim)">${esc(t.desc)}</p><dl class="sumgrid"><dt>Responsible for</dt><dd>${esc(t.resp || '—')}</dd>${t.never ? `<dt>Never</dt><dd>${esc(t.never)}</dd>` : ''}${t.approach ? `<dt>Approach</dt><dd>${esc(t.approach)}</dd>` : ''}${t.output ? `<dt>Output</dt><dd>${esc(t.output)}</dd>` : ''}<dt>Tools</dt><dd>${t.caps.map((c) => S.catalogue.capabilities.find((x) => x.id === c)?.label).join(', ')}</dd><dt>Connectors</dt><dd>${esc(t.needs || 'None needed')}</dd><dt>Sample tasks</dt><dd>${(t.samples || []).map(esc).join('\n')}</dd></dl>`, `<button class="pillbtn" data-act="close">Close</button><button class="pillbtn pri" data-act="tpl-add" data-k="${t.key}">Add to my bots</button>`); };

  // routines
  A['routine-new'] = (d) => { R.rbot = d.bot || (R.view === 'bot' ? R.bot : null); routineModal({}); };
  A['routine-edit'] = (d) => routineModal(S.routines.find((r) => r.id === d.id));
  A['routine-act'] = async (d) => { const r = await call('ws:routine-act', { id: d.id, act: d.a }); if (d.a === 'run') toast(r ? 'Running now.' : 'Not started — check Activity.'); };
  A['routine-del'] = (d) => confirmBox('Delete this routine?', 'It stops running. Past results stay in Tasks.', 'Delete', () => call('ws:routine-act', { id: d.id, act: 'delete' }));
  A['r-day'] = (d) => { const s = R.rd.schedule; const i = +d.i; s.days = s.days.includes(i) ? s.days.filter((x) => x !== i) : [...s.days, i]; $('rType').onchange(); };
  A['r-toggle'] = (d, e) => { R.rd[d.k] = !R.rd[d.k]; e.currentTarget.classList.toggle('on', R.rd[d.k]); };
  A['routine-save'] = async () => {
    const s = R.rd.schedule; s.time = $('rTime').value || '07:00';
    if ($('rDate')) s.date = $('rDate').value; if ($('rDom')) s.dom = +$('rDom').value; if ($('rEvery')) s.every = +$('rEvery').value; if ($('rTBot')) s.bot = $('rTBot').value || null;
    const id = await call('ws:routine-save', { id: R.rd.id, name: $('rName').value || 'Routine', botId: $('rBot').value, instructions: $('rIns').value, schedule: s, output: $('rOut').value, approval: $('rAppr').value, notify: R.rd.notify, active: R.rd.active });
    if (id) { closeLayer(); toast('Routine saved.'); if (R.view !== 'bot') go('routines'); }
  };

  // teams
  A['team-new'] = () => teamModal({});
  A['team-edit'] = (d) => teamModal(S.teams.find((x) => x.id === d.id));
  A.team = (d) => { R.team = d.id; render(true); };
  A['tm-mem'] = (d, e) => { if (e.target.closest('[data-act="tm-coord"]')) return; const m = R.td.members; R.td.members = m.includes(d.id) ? m.filter((x) => x !== d.id) : [...m, d.id]; if (!R.td.members.includes(R.td.coordinator)) R.td.coordinator = R.td.members[0] || null; R.tdraw(); };
  A['tm-coord'] = (d) => { R.td.coordinator = d.id; R.tdraw(); };
  A['tm-mode'] = (d) => { R.td.mode = d.v; R.tdraw(); };
  A['team-save'] = async () => { const id = await call('ws:team-save', { id: R.td.id, name: $('tmName').value, purpose: $('tmPurpose').value, instructions: $('tmIns').value, members: R.td.members, coordinator: R.td.coordinator, mode: R.td.mode }); if (id) { closeLayer(); R.team = id; go('teams', { team: id }); R.team = id; render(true); } };
  A['team-del'] = (d) => confirmBox('Delete this team?', 'The bots stay; only the team is removed.', 'Delete', async () => { await call('ws:team-act', { id: d.id, act: 'delete' }); R.team = null; render(true); });
  A['team-task'] = async (d) => { const v = $('teamTask').value.trim(); if (!v) return; const id = await call('ws:team-act', { id: d.id, act: 'task', arg: v }); if (id) { $('teamTask').value = ''; toast('The team is on it.'); } };

  // skills
  A['skill-new'] = () => skillModal({});
  A['skill-edit'] = (d) => skillModal(S.skills.find((s) => s.id === d.id));
  A['skill-tpl'] = async (d) => { const id = await call('ws:skill-template', { i: +d.i }, 'Added to your skill library.'); if (id && R.view === 'skills') render(true); };
  A['skill-import'] = async () => { const id = await call('ws:skill-import'); if (id) toast('Imported.'); };
  A['skill-ai'] = () => { modal('Create a skill with AI', '<div class="field"><label>Describe the skill</label><textarea id="saiIn" rows="5" placeholder="e.g. Every Friday, collect what each of my bots finished this week and write a one-page report with highlights, problems and next week\'s focus."></textarea></div><div class="helpline">The AI drafts the name, steps, tools, inputs, outputs and limits. You review everything before it\'s saved.</div>', '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="saiGo">Draft it</button>'); $('saiGo').onclick = async () => { $('saiGo').textContent = 'Drafting…'; const r = await call('ws:skill-ai', { text: $('saiIn').value }); if (r) skillModal({ ...r, instructions: `${r.instructions || ''}${r.limitations ? `\nLimits: ${r.limitations}` : ''}`, steps: r.steps || [], tools: (r.tools || []).filter((t) => S.catalogue.tools[t]) }); else $('saiGo').textContent = 'Draft it'; }; };
  A['skill-teach'] = () => modal('Teach a skill', '<p style="margin-top:0;color:var(--dim)">Watching you work on screen isn\'t available yet — Nebula can\'t record and replay your clicks safely. For now, teach by describing it: walk through what you do, step by step, as if training a new teammate.</p><div class="field"><label>Walk it through</label><textarea id="tchIn" rows="8" placeholder="1. I open last week\'s report…\n2. I check which tasks finished…\n3. …"></textarea></div><div class="helpline">Don\'t include passwords or keys.</div>', '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="tchGo">Turn it into a skill</button>') || setTimeout(() => { $('tchGo').onclick = async () => { $('tchGo').textContent = 'Working…'; const r = await call('ws:skill-ai', { text: `Turn this demonstration into a reusable skill:\n${$('tchIn').value}` }); if (r) skillModal({ ...r, steps: r.steps || [], tools: (r.tools || []).filter((t) => S.catalogue.tools[t]) }); else $('tchGo').textContent = 'Turn it into a skill'; }; }, 0);
  A['sk-tool'] = (d, e) => { const t = R.sk.tools; R.sk.tools = t.includes(d.k) ? t.filter((x) => x !== d.k) : [...t, d.k]; e.currentTarget.classList.toggle('on'); };
  A['sk-save'] = async (d) => { const s = { id: R.sk.id, name: $('skName').value, category: $('skCat').value, description: $('skDesc').value, instructions: $('skIns').value, steps: $('skSteps').value.split('\n').map((x) => x.trim()).filter(Boolean), inputs: $('skIn').value, outputs: $('skOut').value, failure: $('skFail').value, examples: $('skEx').value, tools: R.sk.tools }; if (!s.name.trim()) { toast('Name the skill.', true); return; } const id = await call('ws:skill-save', { skill: s, applyAll: d.all !== '0' }, 'Skill saved.'); if (id) closeLayer(); };
  A['sk-del'] = (d) => confirmBox('Delete this skill?', 'It\'s removed from every bot that uses it.', 'Delete', () => call('ws:skill-act', { id: d.id, act: 'delete' }));
  A['sk-dup'] = async (d) => { await call('ws:skill-act', { id: d.id, act: 'duplicate' }, 'Duplicated.'); closeLayer(); };
  A['sk-export'] = (d) => call('ws:skill-act', { id: d.id, act: 'export' }, 'Exported.');
  A['sk-roll'] = async (d) => { await call('ws:skill-act', { id: d.id, act: 'rollback', arg: +d.v }, `Rolled back to v${d.v}.`); closeLayer(); };
  A['sk-assign'] = async (d, e) => { const b = bot(d.bot); await call('ws:skill-act', { id: d.id, act: b.skills.includes(d.id) ? 'unassign' : 'assign', arg: d.bot }); e.currentTarget.classList.toggle('on'); };
  A['sk-test'] = async (d) => { const id = await call('ws:skill-act', { id: d.id, act: 'run', arg: { botId: $('skBot').value, input: $('skInput').value, test: true } }); if (id) { closeLayer(); taskDrawer(id); } };
  A['skill-run'] = (d) => { modal('Run skill', '<div class="field"><label>Input (optional)</label><input type="text" id="srIn"></div>', '<button class="pillbtn" data-act="close">Cancel</button><button class="pillbtn pri" id="srGo">Run</button>'); $('srGo').onclick = async () => { const id = await call('ws:skill-act', { id: d.id, act: 'run', arg: { botId: d.bot, input: $('srIn').value } }); if (id) { closeLayer(); taskDrawer(id); } }; };
  A['skill-assign'] = (d) => call('ws:skill-act', { id: d.id, act: 'assign', arg: d.bot });
  A['skill-unassign'] = (d) => call('ws:skill-act', { id: d.id, act: 'unassign', arg: d.bot });

  // connect apps
  A.conn = (d) => {
    const cap = S.catalogue.capabilities.find((c) => c.id === d.cap);
    if (cap) {
      const test = { web: 'web', files: 'files' }[cap.id];
      modal(esc(d.n), `<div class="kv"><span>Status</span><b style="color:var(--ok)">Built in · working</b></div><div class="kv"><span>What bots can do</span><b>${esc(cap.desc)}</b></div><div class="kv"><span>Permissions</span><b>Per bot: switch “${esc(cap.label)}” on in a bot's Tools settings. Risky actions follow its autonomy and approval rules.</b></div><div class="kv"><span>Bots with access</span><b>${S.bots.filter((b) => b.caps.includes(cap.id)).map((b) => esc(b.name)).join(', ') || 'None yet'}</b></div><div id="testOut" class="helpline" style="margin-top:10px"></div>`, `${test ? `<button class="pillbtn" id="tGo">Test connection</button>` : ''}<button class="pillbtn pri" data-act="close">Done</button>`);
      if (test) $('tGo').onclick = async () => { $('testOut').textContent = 'Testing…'; const r = await call('ws:test', { what: test }); $('testOut').textContent = r ? `✓ ${typeof r === 'string' ? r : 'Working'}` : '✗ The test failed.'; };
      return;
    }
    const mcp = /MCP|Custom API/.test(d.n);
    modal(esc(d.n), `<div class="kv"><span>Status</span><b style="color:var(--faint)">Not available yet</b></div><p style="color:var(--dim)">${mcp ? 'Connecting your own MCP servers and custom APIs (endpoint, sign-in, tool discovery, per-tool permissions, encrypted secrets) is the next part of RovBots being built. Until it ships, nothing here will pretend to connect.' : `Connecting ${esc(d.n)} needs a secure sign-in (OAuth) app registered with ${esc(d.n)} and a place to keep your tokens encrypted. Nebula doesn't have those yet, so bots can't read or change anything in ${esc(d.n)} — they'll tell you so instead of guessing.`}</p><div class="note">Meanwhile, paste the content in (an email, a doc, a table) or add it as Knowledge, and your bots can work with it.</div>`, '<button class="pillbtn pri" data-act="close">OK</button>');
  };

  // settings
  A['pf-save'] = () => call('ws:profile', { name: $('pfName').value.trim(), work: $('pfWork').value.trim(), team: $('pfTeam').value, heard: $('pfHeard').value.trim() }, 'Saved.');
  A.set = (d) => call('ws:settings', { [d.k]: d.k === 'notifyOn' ? !!d.v : d.v });
  A['quiet-save'] = () => call('ws:settings', { quietFrom: $('qFrom').value, quietTo: $('qTo').value }, 'Saved.');
  A.replay = () => window.NBIntro.play(() => {});
  A['redo-onb'] = () => { $('app').hidden = true; window.NBOnboarding.start(S.profile.name); };
  A['nebula-settings'] = () => api.send('ws:open-nebula', 'settings');

  // ---------------------------------------------------------------- events
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]'); if (!el) return;
    const f = A[el.dataset.act]; if (!f) return;
    if (el.dataset.act !== 'scrim') e.stopPropagation();
    f(el.dataset, { ...e, currentTarget: el, target: e.target });
  });
  document.addEventListener('change', (e) => {
    const el = e.target;
    if (el.dataset.actChange) {
      const k = el.dataset.actChange;
      if (k === 't-pri') call('ws:task-act', { id: el.dataset.id, act: 'priority', arg: el.value });
      else if (k === 't-bot') call('ws:task-act', { id: el.dataset.id, act: 'reassign', arg: el.value }, 'Reassigned.');
      else { R.f[k] = el.value; render(true); }
      return;
    }
  });
  document.addEventListener('input', (e) => {
    const el = e.target;
    if (el.dataset.b) { setPath(edTarget(), el.dataset.b, el.dataset.num ? +el.value : el.value); if (el.dataset.b === 'color') { /* live preview in avatars */ const t = edTarget(); document.querySelectorAll('.avpick svg path:first-child').forEach((p) => p.setAttribute('fill', t.color)); } return; }
    if (el.id === 'composer') { draft[R.cid] = el.value; grow(el); return; }
    if (['chatq', 'tq', 'kq', 'sq', 'lq', 'cq'].includes(el.id)) { R.f[el.id] = el.value; clearTimeout(el._t); el._t = setTimeout(() => render(), 160); }
  });
  document.addEventListener('keydown', (e) => {
    if (e.target.id === 'composer' && e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); A.send(); }
    if (e.target.id === 'homeAsk' && e.key === 'Enter') { e.preventDefault(); A['home-task'](); }
    if (e.target.id === 'teamTask' && e.key === 'Enter') A['team-task']({ id: R.team });
    if (e.target.id === 'memIn' && e.key === 'Enter') A['mem-add']({ id: R.bot });
    if (e.target.id === 'steerIn' && e.key === 'Enter') A.steer({ id: R.task });
    if (e.target.id?.startsWith('ans-') && e.key === 'Enter') A['answer-text']({ id: e.target.id.slice(4) });
    if (e.key === 'Escape' && $('layer').innerHTML) { closeLayer(); R.task = null; WZ = null; }
  });
  // Select text in a reply → quote it.
  document.addEventListener('mouseup', (e) => {
    const sel = getSelection(); const txt = sel && String(sel).trim();
    document.querySelectorAll('.selq').forEach((x) => x.remove());
    if (!txt || !e.target.closest('.msgs')) return;
    const b = document.createElement('button'); b.className = 'pillbtn selq'; b.style.cssText = `position:fixed;z-index:45;left:${e.clientX + 8}px;top:${e.clientY - 40}px`;
    b.innerHTML = `${I('reply')}Quote in reply`; b.onmousedown = (ev) => { ev.preventDefault(); replyTo = { id: e.target.closest('.msg')?.id?.slice(2) || null, text: txt.slice(0, 600) }; b.remove(); render(); setTimeout(() => $('composer')?.focus(), 30); };
    document.body.appendChild(b); setTimeout(() => b.remove(), 6000);
  });
  $('collapse').onclick = () => $('app').classList.toggle('collapsed');
  $('newChatBtn').onclick = async () => { const p = S.bots.find((b) => b.primary && !b.archived) || S.bots.find((b) => !b.archived && !b.draft); if (!p) { wizard('pick'); return; } await A['new-chat']({ id: p.id }); openBot(p.id, 'chat', R.cid); };

  // ---------------------------------------------------------------- state from the main process
  function onState(s) {
    S = s;
    if (!$('app').hidden) render();
    if (R.task && $('layer').querySelector('.drawer') && !(document.activeElement && $('layer').contains(document.activeElement) && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName))) { const sc = $('drawerBody')?.scrollTop || 0; taskDrawer(R.task); if ($('drawerBody')) $('drawerBody').scrollTop = sc; }
    if (WZ && $('layer').querySelector('.wiz') && WZ.step === 4) drawWizard();
    if (R.apprOpen && $('layer').querySelector('.appr') && !$('layer').querySelector('.drawer')) { const ap = S.tasks.filter((t) => t.status === 'REQUIRES_INPUT'); if (ap.length) A.approvals(); else { closeLayer(); R.apprOpen = false; } }
  }
  api.on('ws:state', onState);
  api.on('ws:open', (o) => handleOpen(o));
  api.on('ws:sound', () => {});
  function handleOpen(o) { if (!o || !S) return; if (o.taskId) taskDrawer(o.taskId); else if (o.view === 'approvals') A.approvals(); else if (o.view) go(o.view); }
  function showApp() { $('app').hidden = false; $('brandMark').innerHTML = window.NB.logoSvg('#fff', '#5200F4'); render(true); }
  window.addEventListener('nb-intro-done', () => { api.invoke('ws:init').then((s) => { S = s; showApp(); }); });

  api.invoke('ws:init').then((s) => {
    S = s;
    const q = new URLSearchParams(location.search).get('open');
    if (!S.profile.onboarded) { window.NBOnboarding.start(S.profile.name || ''); return; }
    showApp();
    if (q) try { handleOpen(JSON.parse(q)); } catch { /* */ }
  });
})();
