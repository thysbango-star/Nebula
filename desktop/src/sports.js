'use strict';
// Live scores for the teams you follow (Settings → Sports), from ESPN's public scoreboard feed.
// Same logic as the phone (sports/Sports.kt): poll fast only while one of your teams is playing,
// and report a goal when their score goes up.

const LEAGUES = [
  { path: 'soccer/eng.1', name: 'Premier League', kind: 'soccer' },
  { path: 'soccer/rsa.1', name: 'SA Premiership', kind: 'soccer' },
  { path: 'soccer/uefa.champions', name: 'Champions League', kind: 'soccer' },
  { path: 'soccer/esp.1', name: 'LaLiga', kind: 'soccer' },
  { path: 'soccer/ita.1', name: 'Serie A', kind: 'soccer' },
  { path: 'soccer/ger.1', name: 'Bundesliga', kind: 'soccer' },
  { path: 'soccer/fra.1', name: 'Ligue 1', kind: 'soccer' },
  { path: 'soccer/usa.1', name: 'MLS', kind: 'soccer' },
  { path: 'basketball/nba', name: 'NBA', kind: 'basketball' },
  { path: 'football/nfl', name: 'NFL', kind: 'football' },
  { path: 'hockey/nhl', name: 'NHL', kind: 'hockey' },
  { path: 'baseball/mlb', name: 'MLB', kind: 'baseball' },
];
const BASE = 'https://site.api.espn.com/apis/site/v2/sports/';
const league = (path) => LEAGUES.find((l) => l.path === path);

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
    const extra = /own/i.test(kind) ? ' (own goal)' : /penalty/i.test(kind) ? ' (pen)' : '';
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
      name: t.shortDisplayName || t.displayName || '', score: parseInt(c.score, 10) || 0,
      color: readable(color, alt, t.abbreviation || t.id), record: (c.records && c.records[0] && c.records[0].summary) || '',
    };
    if (c.homeAway === 'home') { home = s; homeAlt = alt; } else { away = s; awayAlt = alt; }
  }
  if (!home || !away) return null;
  if (close(home.color, away.color) && awayAlt && !close(home.color, awayAlt)) away.color = readable(awayAlt, '', away.abbr);
  else if (close(home.color, away.color) && homeAlt) home.color = readable(homeAlt, '', home.abbr);
  const st = comp.status || e.status || {}; const type = st.type || {};
  const state = type.state || 'pre';
  const start = Date.parse(e.date) || 0;
  let clock;
  if (state === 'pre') clock = new Date(start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  else if (state === 'post') clock = lg.kind === 'soccer' && /^FT/.test(type.shortDetail || '') ? 'Full time' : (type.shortDetail || 'Final');
  else clock = type.shortDetail || st.displayClock || '';
  return { id: String(e.id), league: lg, home, away, state, clock, lastPlay: lastPlay(comp, home, away), start, live: state === 'in' };
}

async function scoreboard(lg) {
  const j = await get(BASE + lg.path + '/scoreboard');
  return (j.events || []).map((e) => { try { return parse(lg, e); } catch { return null; } }).filter(Boolean);
}

async function teams(path) {
  const lg = league(path); if (!lg) return [];
  const j = await get(BASE + lg.path + '/teams');
  const list = (((j.sports || [])[0] || {}).leagues || [])[0];
  return ((list && list.teams) || []).map((x) => x.team).filter(Boolean).map((t) => ({
    league: lg.path, id: String(t.id), abbr: t.abbreviation || '', name: t.displayName || '',
    color: readable(hex(t.color), hex(t.alternateColor), t.abbreviation || t.id),
  })).sort((a, b) => a.name.localeCompare(b.name));
}

function goalLabel(g, side, before) {
  const delta = side.score - before;
  switch (g.league.kind) {
    case 'soccer': case 'hockey': return 'GOAL';
    case 'football': return delta >= 6 ? 'TOUCHDOWN' : delta === 3 ? 'FIELD GOAL' : delta === 2 ? 'SAFETY' : 'SCORE';
    case 'baseball': return /homer|home run/i.test(g.lastPlay) ? 'HOME RUN' : 'RUN';
    default: return ''; // basketball: too frequent for a big moment, the score just rolls
  }
}

// ---------------------------------------------------------------- watcher
class Sports {
  /** onChange({games}) whenever the list changes; onGoal(score) when one of your teams scores. */
  constructor({ getTeams, enabled, onChange, onGoal, log = () => {} }) {
    Object.assign(this, { getTeams, enabled, onChange, onGoal, log });
    this.games = []; this.last = new Map(); this.finishedAt = new Map(); this.timer = null; this.demoUntil = 0;
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
    const mine = (this.getTeams() || []).filter((t) => t && t.league && t.id);
    if (!this.enabled() || !mine.length) { this.set([]); return 60000; }
    const now = Date.now();
    const paths = [...new Set(mine.map((t) => t.league))];
    const all = (await Promise.all(paths.map((p) => (league(p) ? scoreboard(league(p)).catch(() => []) : [])))).flat();
    const found = all.filter((g) => mine.some((t) => t.league === g.league.path && (t.id === g.home.id || t.id === g.away.id)));
    for (const g of found) {
      const prev = this.last.get(g.id);
      if (prev && g.live) {
        for (const home of [true, false]) {
          const s = home ? g.home : g.away, b = (home ? prev.home : prev.away).score;
          if (s.score > b) { this.onGoal({ game: g, home, before: b, label: goalLabel(g, s, b), who: g.lastPlay, at: now }); break; }
        }
      }
      if (prev && prev.state === 'in' && g.state === 'post') this.finishedAt.set(g.id, now);
      this.last.set(g.id, g);
    }
    const show = found.filter((g) => g.live || (g.state === 'post' && now - (this.finishedAt.get(g.id) || 0) < 15 * 60000) ||
      (g.state === 'pre' && g.start - now >= 0 && g.start - now < 15 * 60000));
    show.sort((a, b) => (a.live ? 0 : a.state === 'pre' ? 1 : 2) - (b.live ? 0 : b.state === 'pre' ? 1 : 2));
    this.set(show);
    if (found.some((g) => g.live)) return 20000;
    if (found.some((g) => g.state === 'pre' && g.start - now < 30 * 60000)) return 60000;
    return 10 * 60000;
  }
  /** A pretend match and a goal, so you can see it without waiting for kick-off. */
  demo() {
    const lg = { path: 'demo', name: 'Premier League', kind: 'soccer' };
    const g = { id: 'demo', league: lg, state: 'in', live: true, clock: "64'", lastPlay: "T. Mokoena 38'", start: 0,
      home: { id: '1', abbr: 'JHB', name: 'Lions', score: 1, color: '#f2b01e' }, away: { id: '2', abbr: 'CPT', name: 'Comets', score: 0, color: '#2f6bff' } };
    this.demoUntil = Date.now() + 22000;
    this.set([g]);
    setTimeout(() => {
      const g2 = { ...g, clock: "67'", lastPlay: "S. Dlamini 67'", away: { ...g.away, score: 1 } };
      this.set([g2]);
      this.onGoal({ game: g2, home: false, before: 0, label: 'GOAL', who: g2.lastPlay, at: Date.now() });
    }, 2600);
    setTimeout(() => { this.demoUntil = 0; this.set([]); this.refresh(); }, 22000);
  }
}

module.exports = { Sports, LEAGUES, teams, scoreboard, parse, readable };
