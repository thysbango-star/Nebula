'use strict';
// Live scores for the teams you follow (Settings → Sports), from ESPN's public scoreboard feed.
// Same logic as the phone (sports/Sports.kt): poll fast only while one of your teams is playing,
// and report a goal when their score goes up.

// How points work (so the moment says the right thing):
//  - Rugby union (World Rugby Law 8): try 5, penalty try 7, conversion 2, penalty goal 3, drop goal 3.
//    After a try the scoring team may kick a conversion (place or drop kick, in line with where the try was
//    grounded, within 60 s of playing time — sevens and 10s: drop kick within 30 s). A penalty try is 7 straight
//    away with no kick. So: +5 = TRY ("conversion to come"), +2 soon after = CONVERTED, no +2 in the window (or
//    another score first) = conversion missed, +7 at once = converted try.
const KICK_WINDOW = 150000; // 60 s of playing time + walking back + the feed catching up
//  - Cricket: runs (4 for a boundary, 6 over the rope on the full) and wickets; a score reads runs/wickets.
const CRICKET_FEED = 'cricket/scorepanel';
const RUGBY_TESTS = 'rugby/tests';
const L = (path, name, kind, sport, country, feeds) => ({ path, name, kind, sport, country, feeds: feeds || [path] });
const LEAGUES = [
  L('soccer/eng.1', 'Premier League', 'soccer', 'Football', 'England'),
  L('soccer/rsa.1', 'Betway Premiership', 'soccer', 'Football', 'South Africa'),
  L('soccer/esp.1', 'LaLiga', 'soccer', 'Football', 'Spain'),
  L('soccer/ita.1', 'Serie A', 'soccer', 'Football', 'Italy'),
  L('soccer/ger.1', 'Bundesliga', 'soccer', 'Football', 'Germany'),
  L('soccer/fra.1', 'Ligue 1', 'soccer', 'Football', 'France'),
  L('soccer/usa.1', 'MLS', 'soccer', 'Football', 'USA'),
  L('soccer/uefa.champions', 'Champions League', 'soccer', 'Football', 'Europe'),
  L('soccer/fifa.world', 'World Cup', 'soccer', 'Football', 'International'),
  L(RUGBY_TESTS, 'Test rugby', 'rugby', 'Rugby', 'International', ['rugby/289234', 'rugby/17567', 'rugby/244293', 'rugby/180659', 'rugby/164205']),
  L('rugby/270557', 'United Rugby Championship', 'rugby', 'Rugby', 'South Africa'),
  L('rugby/267979', 'Premiership Rugby', 'rugby', 'Rugby', 'England'),
  L('rugby/270559', 'Top 14', 'rugby', 'Rugby', 'France'),
  L('rugby/242041', 'Super Rugby Pacific', 'rugby', 'Rugby', 'New Zealand & Australia'),
  L('cricket/intl', 'International cricket', 'cricket', 'Cricket', 'International', [CRICKET_FEED]),
  L('cricket/sa20', 'SA20', 'cricket', 'Cricket', 'South Africa', [CRICKET_FEED]),
  L('cricket/ipl', 'IPL', 'cricket', 'Cricket', 'India', [CRICKET_FEED]),
  L('cricket/bbl', 'Big Bash', 'cricket', 'Cricket', 'Australia', [CRICKET_FEED]),
  L('basketball/nba', 'NBA', 'basketball', 'US sports', 'USA'),
  L('football/nfl', 'NFL', 'football', 'US sports', 'USA'),
  L('hockey/nhl', 'NHL', 'hockey', 'US sports', 'USA'),
  L('baseball/mlb', 'MLB', 'baseball', 'US sports', 'USA'),
];
const T = (league, abbr, name, color) => ({ league, id: '', abbr, name, color });
const RUGBY_NATIONS = [['RSA', 'South Africa', '#00704a'], ['NZ', 'New Zealand', '#3a3a3a'], ['AUS', 'Australia', '#f2b51c'], ['ARG', 'Argentina', '#5da9e9'],
  ['ENG', 'England', '#c8102e'], ['IRE', 'Ireland', '#169b62'], ['FRA', 'France', '#1e3a8a'], ['SCO', 'Scotland', '#1f3b73'], ['WAL', 'Wales', '#d30731'],
  ['ITA', 'Italy', '#0057b8'], ['FIJ', 'Fiji', '#2f80c8'], ['JPN', 'Japan', '#d7263d'], ['SAM', 'Samoa', '#1d3c8f'], ['GEO', 'Georgia', '#b3132a'],
  ['TGA', 'Tonga', '#c8102e'], ['USA', 'United States', '#27457a'], ['CAN', 'Canada', '#d52b1e']].map(([a, n, c]) => T(RUGBY_TESTS, a, n, c));
const CRICKET_TEAMS = [
  ...[['SA', 'South Africa', '#00843d'], ['AUS', 'Australia', '#f2b51c'], ['ENG', 'England', '#1c3d7a'], ['IND', 'India', '#1f5bb5'], ['NZ', 'New Zealand', '#3a3a3a'],
    ['PAK', 'Pakistan', '#11703a'], ['SL', 'Sri Lanka', '#1b3f94'], ['WI', 'West Indies', '#7b1e3a'], ['BAN', 'Bangladesh', '#006a4e'], ['AFG', 'Afghanistan', '#2563b8'],
    ['ZIM', 'Zimbabwe', '#ce1126'], ['IRE', 'Ireland', '#169b62']].map(([a, n, c]) => T('cricket/intl', a, n, c)),
  ...[['DSG', "Durban's Super Giants", '#00a3c4'], ['JSK', 'Joburg Super Kings', '#f5b800'], ['MICT', 'MI Cape Town', '#1f5fad'], ['PR', 'Paarl Royals', '#e5157f'],
    ['PC', 'Pretoria Capitals', '#2e5ba8'], ['SEC', 'Sunrisers Eastern Cape', '#f26522']].map(([a, n, c]) => T('cricket/sa20', a, n, c)),
  ...[['CSK', 'Chennai Super Kings', '#f5c400'], ['MI', 'Mumbai Indians', '#1f5fad'], ['RCB', 'Royal Challengers Bengaluru', '#d1141b'], ['KKR', 'Kolkata Knight Riders', '#5b2a86'],
    ['SRH', 'Sunrisers Hyderabad', '#f26522'], ['RR', 'Rajasthan Royals', '#e5157f'], ['DC', 'Delhi Capitals', '#2e5ba8'], ['PBKS', 'Punjab Kings', '#d71920'],
    ['GT', 'Gujarat Titans', '#2a4470'], ['LSG', 'Lucknow Super Giants', '#18a3c4']].map(([a, n, c]) => T('cricket/ipl', a, n, c)),
  ...[['STR', 'Adelaide Strikers', '#2e8fd8'], ['HEA', 'Brisbane Heat', '#0fa3b1'], ['HUR', 'Hobart Hurricanes', '#6b3fa0'], ['REN', 'Melbourne Renegades', '#d71920'],
    ['STA', 'Melbourne Stars', '#1ba34a'], ['SCO', 'Perth Scorchers', '#f26522'], ['SIX', 'Sydney Sixers', '#e5157f'], ['THU', 'Sydney Thunder', '#8bc53f']].map(([a, n, c]) => T('cricket/bbl', a, n, c)),
];
const BASE = 'https://site.api.espn.com/apis/site/v2/sports/';
const league = (path) => LEAGUES.find((l) => l.path === path);
const norm = (x) => String(x || '').toLowerCase().replace(/[^a-z0-9]/g, '');
const matches = (t, s) => (t.id && t.id === s.id) || norm(t.name) === norm(s.name) || (!t.id && t.abbr && !t.league.startsWith('cricket/') && t.abbr.toLowerCase() === String(s.abbr).toLowerCase());

// ---------------------------------------------------------------- colours
const hex = (s) => (typeof s === 'string' && /^#?[0-9a-f]{6}$/i.test(s.trim()) ? '#' + s.trim().replace('#', '').toLowerCase() : '');
const rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const lum = (h) => { const [r, g, b] = rgb(h); return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255; };
const close = (a, b) => { if (!a || !b) return false; const x = rgb(a), y = rgb(b); return x.reduce((s, v, i) => s + (v - y[i]) ** 2, 0) < 70 * 70; };
const PAL = ['#2f6bff', '#e0383e', '#13a35b', '#f2a31b', '#8a4dff', '#00a6b4', '#e8531e'];
function readable(main, alt, key) {
  for (const c of [main, alt]) if (c && lum(c) >= 0.06 && lum(c) <= 0.85) return c;
  let h = 0; for (const ch of String(key)) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return PAL[Math.abs(h) % PAL.length];
}

// ---------------------------------------------------------------- feed
async function get(url) {
  const r = await fetch(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

/** The team's official logo from ESPN — the dark-background version when there is one (our cards are dark). */
function logoOf(t) {
  const ls = (t && t.logos) || [];
  const dark = ls.find((l) => l && l.href && (l.rel || []).includes('dark'));
  return (dark && dark.href) || (ls[0] && ls[0].href) || (t && t.logo) || '';
}

function lastPlay(comp, h, a) {
  const lp = comp.situation && comp.situation.lastPlay && comp.situation.lastPlay.text;
  if (lp) return lp;
  const d = comp.details || [];
  for (let i = d.length - 1; i >= 0; i--) {
    const x = d[i]; if (!x || !x.scoringPlay) continue;
    const who = (x.athletesInvolved && x.athletesInvolved[0] && x.athletesInvolved[0].displayName) || '';
    const min = (x.clock && x.clock.displayValue) || '';
    const kind = (x.type && x.type.text) || '';
    const tid = x.team && x.team.id;
    const team = tid === h.id ? h.name : tid === a.id ? a.name : '';
    const extra = /own/i.test(kind) ? ' (own goal)' : /penalty/i.test(kind) ? ' (pen)' : /drop/i.test(kind) ? ' (drop goal)' : '';
    return [who || team, min].filter(Boolean).join(' ') + extra;
  }
  return '';
}

function parse(lg, e) {
  const comp = (e.competitions || [])[0]; if (!comp) return null;
  let home = null, away = null, homeAlt = '', awayAlt = '';
  for (const c of comp.competitors || []) {
    const t = c.team; if (!t) continue;
    const color = hex(t.color), alt = hex(t.alternateColor);
    const s = {
      id: String(t.id || ''), abbr: t.abbreviation || (t.shortDisplayName || '').slice(0, 3).toUpperCase(),
      name: t.shortDisplayName || t.displayName || '', score: Math.trunc(parseFloat(c.score)) || 0,
      color: readable(color, alt, t.abbreviation || t.id), record: (c.records && c.records[0] && c.records[0].summary) || '', logo: logoOf(t),
    };
    if (c.homeAway === 'home') { home = s; homeAlt = alt; } else { away = s; awayAlt = alt; }
  }
  if (!home || !away) return null;
  home.text = String(home.score); away.text = String(away.score);
  if (close(home.color, away.color) && awayAlt && !close(home.color, awayAlt)) away.color = readable(awayAlt, '', away.abbr);
  else if (close(home.color, away.color) && homeAlt) home.color = readable(homeAlt, '', home.abbr);
  const st = comp.status || e.status || {}; const type = st.type || {};
  const state = type.state || 'pre';
  const start = Date.parse(e.date) || 0;
  let clock;
  if (state === 'pre') clock = new Date(start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  else if (state === 'post') clock = (lg.kind === 'soccer' || lg.kind === 'rugby') && (/^FT/.test(type.shortDetail || '') || !type.shortDetail) ? 'Full time' : (type.shortDetail || 'Final');
  else clock = type.shortDetail || st.displayClock || '';
  return { id: String(e.id), league: lg, home, away, state, clock, lastPlay: lastPlay(comp, home, away), start, live: state === 'in' };
}

// ---- cricket: "161/5 (18/20 ov, target 156)", "245 & 120/3 (35 ov)", or "" before batting
function innings(score) {
  const s = String(score || '');
  const lastInn = s.split('&').pop().trim();
  const main = lastInn.split('(')[0].trim();
  const ov = (/\(([^)]*?ov)/.exec(lastInn) || [])[1];
  const overs = ov ? ov.split(',')[0].trim() : '';
  const m = /(\d+)(?:\/(\d+))?/.exec(main);
  const runs = m ? +m[1] : 0;
  const wickets = m && m[2] != null ? +m[2] : (m && main && !main.includes('/') ? 10 : 0);
  const text = (s.includes('&') ? s.split('(')[0].trim().replace(/\s+/g, ' ') : main) || '–';
  return { runs, wickets, text, overs };
}
function parseCricket(lg, e) {
  const comp = (e.competitions || [])[0]; if (!comp) return null;
  const cs = comp.competitors || []; if (cs.length < 2) return null;
  const sides = cs.slice(0, 2).map((c) => {
    const t = c.team || {}; const inn = innings(c.score);
    const known = CRICKET_TEAMS.find((k) => norm(k.name) === norm(t.displayName));
    return { id: String(t.id || ''), abbr: t.abbreviation || (known && known.abbr) || String(t.displayName || '').slice(0, 3).toUpperCase(),
      name: t.displayName || t.name || '', score: inn.runs, text: inn.text, wickets: inn.wickets, sub: inn.overs, logo: logoOf(t),
      color: (known && known.color) || readable(hex(t.color), hex(t.alternateColor), t.abbreviation || t.id) };
  });
  const [home, away] = sides;
  if (close(home.color, away.color)) away.color = readable('', '', away.abbr + 'x');
  const st = comp.status || e.status || {}; const type = st.type || {};
  const state = type.state || 'pre'; const start = Date.parse(e.date) || 0;
  const batting = [...sides].reverse().find((x) => x.sub);
  const clock = state === 'pre' ? new Date(start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : state === 'post' ? 'Result' : batting ? batting.sub.split('/')[0].replace(/\s*ov$/, '') + ' ov' : (type.shortDetail || 'Live');
  const summary = st.summary || comp.note || type.detail || '';
  const label = [comp.class && comp.class.generalClassCard, comp.description].filter(Boolean).join(' · ');
  return { id: String(e.id), league: { ...lg, name: label || lg.name }, home, away, state, clock, lastPlay: summary, start, live: state === 'in' };
}

async function fetchFeed(feed, lg) {
  if (feed === CRICKET_FEED) {
    const j = await get('https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel');
    const evs = [...(j.events || []), ...((j.scores || []).flatMap((x) => (x && x.events) || []))];
    return evs.map((e) => { try { return parseCricket(lg, e); } catch { return null; } }).filter(Boolean);
  }
  const j = await get(BASE + feed + '/scoreboard');
  return (j.events || []).map((e) => { try { return parse(lg, e); } catch { return null; } }).filter(Boolean);
}
async function scoreboard(lg) { return (await Promise.all(lg.feeds.map((f) => fetchFeed(f, lg).catch(() => [])))).flat(); }

async function teams(path) {
  const lg = league(path); if (!lg) return [];
  if (lg.kind === 'cricket') return CRICKET_TEAMS.filter((t) => t.league === lg.path);
  if (lg.path === RUGBY_TESTS) return RUGBY_NATIONS;
  const j = await get(BASE + lg.path + '/teams');
  const list = (((j.sports || [])[0] || {}).leagues || [])[0];
  return ((list && list.teams) || []).map((x) => x.team).filter(Boolean).map((t) => ({
    league: lg.path, id: String(t.id), abbr: t.abbreviation || '', name: t.displayName || '',
    color: readable(hex(t.color), hex(t.alternateColor), t.abbreviation || t.id), logo: logoOf(t),
  })).sort((a, b) => a.name.localeCompare(b.name));
}

/** Supporters' words that flash up before the big word, like the club graphics in Premier League broadcasts. */
function chant(kind, side, label) {
  const n = String(side.name || '').toLowerCase();
  if (kind === 'rugby' && n.includes('south africa') && label.includes('TRY')) return 'BOKKE';
  if (kind === 'cricket' && n === 'south africa') return 'PROTEAS';
  if (kind === 'soccer') {
    if (n.includes('tottenham')) return 'COYS';
    if (n.includes('newcastle')) return 'HOWAY';
    if (n.includes('kaizer chiefs')) return 'AMAKHOSI';
    if (n.includes('sundowns')) return 'MASANDAWANA';
    if (n.includes('orlando pirates')) return 'BUCCANEERS';
  }
  return '';
}

const kickKey = (g, home) => `${g.id}|${home ? 'home' : 'away'}`;
function rugbyMoment(prev, g, now, kicks) {
  for (const home of [true, false]) {
    const s = home ? g.home : g.away, w = home ? prev.home : prev.away;
    if (s.score <= w.score) continue;
    const delta = s.score - w.score, play = g.lastPlay || '', k = kickKey(g, home);
    const waiting = kicks.awaiting.has(k); kicks.awaiting.delete(k); kicks.missed.delete(k);
    const other = kickKey(g, !home); if (kicks.awaiting.delete(other)) kicks.missed.set(other, now);
    const join = (...a) => a.filter(Boolean).join(' · ');
    let label, who = play;
    if (/penalty try/i.test(play)) { label = 'PENALTY TRY'; who = join(play, '7 points · no kick needed'); }
    else if (delta === 2 && (waiting || /conver/i.test(play))) { label = 'CONVERTED'; who = join(play, '+2'); }
    else if (delta === 2) label = 'CONVERSION';
    else if (delta === 7) { label = 'TRY'; who = join(play, 'Converted'); }
    else if (delta === 5 || delta === 6) { label = 'TRY'; kicks.awaiting.set(k, now); }
    else if (delta === 3) label = /drop/i.test(play) ? 'DROP GOAL' : 'PENALTY';
    else if (delta === 8) label = 'TRY + PENALTY';
    else label = 'POINTS';
    if (waiting && delta !== 2) kicks.missed.set(k, now);
    return { game: g, home, before: w.text, label, who, chant: /CONVER/.test(label) ? '' : chant('rugby', s, label), at: now };
  }
  return null;
}

/** What just happened between two looks at the same game, if it deserves the big moment. */
function moment(prev, g, now = Date.now(), kicks = { awaiting: new Map(), missed: new Map() }) {
  const kind = g.league.kind;
  if (kind === 'rugby') return g.live ? rugbyMoment(prev, g, now, kicks) : null;
  if (kind === 'cricket') {
    for (const home of [true, false]) {
      const s = home ? g.home : g.away, w = home ? prev.home : prev.away, other = home ? g.away : g.home;
      if (g.live && s.wickets > w.wickets && s.score >= w.score) {
        return { game: g, home: !home, before: (home ? prev.away : prev.home).text, label: 'WICKET', who: g.lastPlay, chant: chant(kind, other, 'WICKET'), at: now };
      }
      if (g.live && s.wickets === w.wickets) {
        const label = { 6: 'SIX', 4: 'FOUR' }[s.score - w.score];
        if (label) return { game: g, home, before: w.text, label, who: g.lastPlay, chant: chant(kind, s, label), at: now };
      }
    }
    if (prev.live && g.state === 'post') {
      const won = [true, false].find((h) => { const s = h ? g.home : g.away; return /won/i.test(g.lastPlay) && g.lastPlay.toLowerCase().includes(s.name.toLowerCase()); });
      if (won != null) return { game: g, home: won, before: (won ? g.home : g.away).text, label: 'WIN', who: g.lastPlay, chant: '', at: now };
    }
    return null;
  }
  if (!g.live) return null;
  for (const home of [true, false]) {
    const s = home ? g.home : g.away, w = home ? prev.home : prev.away;
    if (s.score <= w.score) continue;
    const delta = s.score - w.score, play = g.lastPlay || '';
    let label;
    switch (kind) {
      case 'soccer': case 'hockey': label = 'GOAL'; break;
      case 'rugby': label = /penalty try/i.test(play) ? 'PENALTY TRY' : delta >= 5 ? 'TRY' : delta === 2 ? 'CONVERSION' : delta === 3 && /drop/i.test(play) ? 'DROP GOAL' : delta === 3 ? 'PENALTY' : 'POINTS'; break;
      case 'football': label = delta >= 6 ? 'TOUCHDOWN' : delta === 3 ? 'FIELD GOAL' : delta === 2 ? 'SAFETY' : 'SCORE'; break;
      case 'baseball': label = /homer|home run/i.test(play) ? 'HOME RUN' : 'RUN'; break;
      default: label = ''; // basketball: too frequent for a big moment, the score just rolls
    }
    const who = kind === 'rugby' && label === 'TRY' && delta >= 7 ? [play, 'Converted'].filter(Boolean).join(' · ') : play;
    return { game: g, home, before: w.text, label, who, chant: chant(kind, s, label), at: now };
  }
  return null;
}

// ---------------------------------------------------------------- watcher
class Sports {
  /** onChange({games}) whenever the list changes; onGoal(score) when one of your teams scores. */
  constructor({ getTeams, enabled, onChange, onGoal, onLearn, log = () => {} }) {
    Object.assign(this, { getTeams, enabled, onChange, onGoal, onLearn, log });
    this.games = []; this.last = new Map(); this.finishedAt = new Map(); this.timer = null; this.demoUntil = 0;
    this.kicks = { awaiting: new Map(), missed: new Map() };
  }
  start() { this.stop(); this.timer = setTimeout(() => this.tick(), 4000); }
  stop() { clearTimeout(this.timer); this.timer = null; }
  refresh() { this.stop(); this.timer = setTimeout(() => this.tick(), 500); }
  async tick() {
    let wait = 60000;
    try { wait = await this.poll(); } catch (e) { this.log('sports', e.message); }
    this.timer = setTimeout(() => this.tick(), wait);
  }
  set(games) {
    const key = JSON.stringify(games);
    if (key !== this.lastKey) { this.lastKey = key; this.games = games; this.onChange(games); }
  }
  async poll() {
    if (Date.now() < this.demoUntil) return 2000;
    const mine = (this.getTeams() || []).filter((t) => t && t.league && (t.id || t.name));
    if (!this.enabled() || !mine.length) { this.set([]); return 60000; }
    const now = Date.now();
    const leagues = [...new Set(mine.map((t) => t.league))].map(league).filter(Boolean);
    const cache = new Map();
    const feed = (f, lg) => { if (!cache.has(f)) cache.set(f, fetchFeed(f, lg).catch(() => [])); return cache.get(f); };
    const seen = new Set(); const found = [];
    for (const lg of leagues) {
      const games = (await Promise.all(lg.feeds.map((f) => feed(f, lg)))).flat();
      for (const g of games) {
        if (seen.has(g.id)) continue;
        if (mine.some((t) => t.league === lg.path && (matches(t, g.home) || matches(t, g.away)))) { seen.add(g.id); found.push(g); }
      }
    }
    // Teams picked from our own lists (national sides, SA20…) learn their ESPN logo the first time they play.
    if (this.onLearn) {
      const sides = found.flatMap((g) => [g.home, g.away]);
      let changed = false;
      const learned = (this.getTeams() || []).map((t) => {
        if (t.logo) return t;
        const s = sides.find((x) => matches(t, x) && x.logo);
        if (!s) return t; changed = true; return { ...t, logo: s.logo };
      });
      if (changed) this.onLearn(learned);
    }
    for (const g of found) {
      const prev = this.last.get(g.id);
      if (prev) { const m = moment(prev, g, now, this.kicks); if (m) this.onGoal(m); }
      if (prev && prev.state === 'in' && g.state === 'post') this.finishedAt.set(g.id, now);
      this.last.set(g.id, g);
    }
    // Rugby conversions: no kick by the end of the window means it was missed (or not taken).
    for (const [k, at] of this.kicks.awaiting) if (now - at > KICK_WINDOW) { this.kicks.awaiting.delete(k); this.kicks.missed.set(k, now); }
    for (const g of found) {
      if (g.league.kind !== 'rugby') continue;
      for (const home of [true, false]) {
        const k = kickKey(g, home), name = (home ? g.home : g.away).name;
        if (this.kicks.awaiting.has(k)) g.note = `${name} · conversion to come`;
        else if (now - (this.kicks.missed.get(k) || 0) < 60000) g.note = `${name} · conversion missed`;
      }
    }
    const show = found.filter((g) => g.live || (g.state === 'post' && now - (this.finishedAt.get(g.id) || 0) < 15 * 60000) ||
      (g.state === 'pre' && g.start - now >= 0 && g.start - now < 15 * 60000));
    show.sort((a, b) => (a.live ? 0 : a.state === 'pre' ? 1 : 2) - (b.live ? 0 : b.state === 'pre' ? 1 : 2));
    this.set(show);
    if (found.some((g) => g.live)) return found.some((g) => g.live && g.league.kind === 'cricket') ? 15000 : 20000;
    if (found.some((g) => g.state === 'pre' && g.start - now < 30 * 60000)) return 60000;
    return 10 * 60000;
  }
  /** A pretend game of the chosen sport and its big moment, so you can see it without waiting for kick-off. */
  demo(kind = 'soccer') {
    let g, g2, m;
    if (kind === 'rugby') {
      g = { id: 'demo', league: { path: 'demo', name: 'Nations Championship', kind: 'rugby' }, state: 'in', live: true, clock: "44'", lastPlay: '', start: 0,
        home: { id: '1', abbr: 'RSA', name: 'South Africa', score: 10, text: '10', color: '#00704a' }, away: { id: '2', abbr: 'ENG', name: 'England', score: 14, text: '14', color: '#c8102e' } };
      g2 = { ...g, clock: "47'", lastPlay: "C. Kolbe 47'", home: { ...g.home, score: 15, text: '15' } };
      m = { game: g2, home: true, before: '10', label: 'TRY', who: "C. Kolbe 47'", chant: 'BOKKE' };
    } else if (kind === 'cricket') {
      g = { id: 'demo', league: { path: 'demo', name: 'T20I · 2nd T20I', kind: 'cricket' }, state: 'in', live: true, clock: '17.4 ov', lastPlay: 'South Africa need 22 runs from 14 balls', start: 0,
        home: { id: '1', abbr: 'SA', name: 'South Africa', score: 161, text: '161/5', wickets: 5, sub: '17.4 ov', color: '#00843d' },
        away: { id: '2', abbr: 'AUS', name: 'Australia', score: 182, text: '182/7', wickets: 7, sub: '20 ov', color: '#f2b51c' } };
      g2 = { ...g, clock: '17.5 ov', lastPlay: 'South Africa need 16 runs from 13 balls', home: { ...g.home, score: 167, text: '167/5', sub: '17.5 ov' } };
      m = { game: g2, home: true, before: '161/5', label: 'SIX', who: g2.lastPlay, chant: 'PROTEAS' };
    } else {
      g = { id: 'demo', league: { path: 'demo', name: 'Premier League', kind: 'soccer' }, state: 'in', live: true, clock: "64'", lastPlay: "T. Mokoena 38'", start: 0,
        home: { id: '1', abbr: 'JHB', name: 'Lions', score: 1, text: '1', color: '#f2b01e' }, away: { id: '2', abbr: 'CPT', name: 'Comets', score: 0, text: '0', color: '#2f6bff' } };
      g2 = { ...g, clock: "67'", lastPlay: "S. Dlamini 67'", away: { ...g.away, score: 1, text: '1' } };
      m = { game: g2, home: false, before: '0', label: 'GOAL', who: g2.lastPlay, chant: '' };
    }
    this.demoUntil = Date.now() + 22000;
    this.set([g]);
    setTimeout(() => { this.set([g2]); this.onGoal({ ...m, at: Date.now() }); }, 2600);
    setTimeout(() => { this.demoUntil = 0; this.set([]); this.refresh(); }, 22000);
  }
}

module.exports = { Sports, LEAGUES, teams, scoreboard, parse, parseCricket, innings, moment, readable };
