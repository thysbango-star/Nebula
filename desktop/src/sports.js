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
const T = (league, abbr, name, color, logo = '') => ({ league, id: '', abbr, name, color, logo });
// ESPN serves cricket logos by ESPNcricinfo team id.
const CRIC_IDS = { 'South Africa': 3, Australia: 2, England: 1, India: 6, 'New Zealand': 5, Pakistan: 7, 'Sri Lanka': 8, 'West Indies': 4, Bangladesh: 25, Afghanistan: 40, Zimbabwe: 9, Ireland: 29 };
const cricLogo = (name) => (CRIC_IDS[name] ? `https://a.espncdn.com/i/teamlogos/cricket/500/${CRIC_IDS[name]}.png` : '');
const RUGBY_NATIONS = [['RSA', 'South Africa', '#00704a'], ['NZ', 'New Zealand', '#3a3a3a'], ['AUS', 'Australia', '#f2b51c'], ['ARG', 'Argentina', '#5da9e9'],
  ['ENG', 'England', '#c8102e'], ['IRE', 'Ireland', '#169b62'], ['FRA', 'France', '#1e3a8a'], ['SCO', 'Scotland', '#1f3b73'], ['WAL', 'Wales', '#d30731'],
  ['ITA', 'Italy', '#0057b8'], ['FIJ', 'Fiji', '#2f80c8'], ['JPN', 'Japan', '#d7263d'], ['SAM', 'Samoa', '#1d3c8f'], ['GEO', 'Georgia', '#b3132a'],
  ['TGA', 'Tonga', '#c8102e'], ['USA', 'United States', '#27457a'], ['CAN', 'Canada', '#d52b1e']].map(([a, n, c]) => T(RUGBY_TESTS, a, n, c));
const CRICKET_TEAMS = [
  ...[['SA', 'South Africa', '#00843d'], ['AUS', 'Australia', '#f2b51c'], ['ENG', 'England', '#1c3d7a'], ['IND', 'India', '#1f5bb5'], ['NZ', 'New Zealand', '#3a3a3a'],
    ['PAK', 'Pakistan', '#11703a'], ['SL', 'Sri Lanka', '#1b3f94'], ['WI', 'West Indies', '#7b1e3a'], ['BAN', 'Bangladesh', '#006a4e'], ['AFG', 'Afghanistan', '#2563b8'],
    ['ZIM', 'Zimbabwe', '#ce1126'], ['IRE', 'Ireland', '#169b62']].map(([a, n, c]) => T('cricket/intl', a, n, c, cricLogo(n))),
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
  const ll = (((j.leagues || [])[0] || {}).logos || []);
  const leagueLogo = ((ll.find((l) => (l.rel || []).includes('dark')) || ll[0]) || {}).href || '';
  return (j.events || []).map((e) => { try { const g = parse(lg, e); if (g) { g.feed = feed; g.leagueLogo = leagueLogo; } return g; } catch { return null; } }).filter(Boolean);
}
async function scoreboard(lg) { return (await Promise.all(lg.feeds.map((f) => fetchFeed(f, lg).catch(() => [])))).flat(); }

/** National rugby logos, read from ESPN's own Test-rugby team lists (no guessing at ids). Fetched once. */
let nations = null;
async function nationLogos() {
  if (nations) return nations;
  const out = {};
  for (const feed of ['rugby/289234', 'rugby/17567', 'rugby/244293', 'rugby/180659', 'rugby/164205']) {
    try {
      const j = await get(BASE + feed + '/teams');
      const list = ((((j.sports || [])[0] || {}).leagues || [])[0] || {}).teams || [];
      for (const x of list) {
        const t = x.team; const logo = t && logoOf(t); if (!logo) continue;
        for (const n of [t.displayName, t.shortDisplayName, t.name]) if (n && !out[norm(n)]) out[norm(n)] = logo;
      }
    } catch { /* try the next list */ }
  }
  if (Object.keys(out).length) nations = out;
  return out;
}
// The right list for the sport: South Africa's rugby logo is not its cricket logo.
const knownLogo = (name, kind) => (kind === 'rugby' ? (nations && nations[norm(name)]) || ''
  : kind === 'cricket' ? (CRICKET_TEAMS.find((t) => norm(t.name) === norm(name) && t.logo) || {}).logo || '' : '');

async function teams(path) {
  const lg = league(path); if (!lg) return [];
  if (lg.kind === 'cricket') return CRICKET_TEAMS.filter((t) => t.league === lg.path);
  if (lg.path === RUGBY_TESTS) { const logos = await nationLogos(); return RUGBY_NATIONS.map((t) => ({ ...t, logo: logos[norm(t.name)] || '' })); }
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

// ---------------------------------------------------------------- key events (cards, subs, scorers)
// Rugby cards: a yellow is 10 minutes in the sin bin; a 20-minute red lets a replacement come on after 20 minutes
// (World Rugby's elite trial; the French Top 14 doesn't use it); a permanent red is off for the match.
const minuteOf = (x) => { const m = /(\d+)/.exec(String(x || '')); return m ? +m[1] : null; };
function keyEvents(g, j) {
  const arr = j.keyEvents || (((j.header || {}).competitions || [])[0] || {}).details || [];
  const out = [];
  for (const x of arr) {
    const type = (x.type && x.type.text) || ''; const text = x.text || '';
    const t = `${type} ${x.shortText || ''}`.toLowerCase(); const scoring = !!x.scoringPlay;
    let kind = null;
    if (t.includes('yellow') || x.yellowCard) kind = 'yellow';
    else if (t.includes('red card') || x.redCard) kind = g.league.kind === 'rugby' && !/permanent/i.test(text) && !String(g.league.path).includes('270559') ? 'red20' : 'red';
    else if (t.includes('substitut') || t.includes('replacement')) kind = 'sub';
    else if (t.includes('penalty try')) kind = 'pentry';
    else if (t.includes('conversion') && !t.includes('miss')) kind = 'conversion';
    else if (t.includes('drop') && (scoring || g.league.kind === 'rugby') && !t.includes('miss')) kind = 'drop';
    else if (t.includes('try')) kind = 'try';
    else if (t.includes('penalty') && scoring) kind = 'penalty';
    else if (t.includes('goal') && scoring) kind = 'goal';
    if (!kind) continue;
    const names = [...(x.participants || []).map((p) => p && p.athlete && p.athlete.displayName), ...(x.athletesInvolved || []).map((a) => a && a.displayName)].filter(Boolean);
    let p1 = names[0] || '', p2 = names[1] || '';
    if (kind === 'sub' && / replaces /.test(text)) { // "Substitution, Man United. A. Garnacho replaces M. Mount."
      const [on, off] = text.replace(/^[^,]*,[^.]*\.\s*/, '').split(' replaces ');
      p1 = on.trim(); p2 = off.trim().replace(/\.$/, '');
    }
    const minute = (x.clock && x.clock.displayValue) || '';
    const teamId = String((x.team && x.team.id) || '');
    out.push({ id: String(x.id || `${type}|${minute}|${teamId}|${p1}`), kind, teamId, player: p1, player2: p2, minute });
  }
  return out;
}

function scorerLine(m, e) {
  const min = e.minute ? ` ${e.minute}` : '';
  switch (e.kind) {
    case 'conversion': return `${e.player} converts${min}`;
    case 'pentry': return `Penalty try${min} · 7 points, no kick needed`;
    case 'drop': return `${e.player} drop goal${min}`;
    case 'penalty': return `${e.player} penalty${min}`;
    case 'try': return `${e.player}${min}${/Converted/.test(m.who) ? ' · Converted' : ''}`;
    default: return `${e.player}${min}${/\(pen\)/.test(m.who) ? ' (pen)' : ''}`;
  }
}

/**
 * Club moments, drawn by Nebula (not copied from any broadcast): Arsenal fire a battery of cannons, Brighton get
 * a flock of seagulls, Newcastle magpies, Crystal Palace eagles; rugby nations get the cinematic treatment
 * (letterbox, their logo looming from the corner). Everyone else: their logo tiled through the colour wipe.
 */
const variantTurn = new Map();
function ownMotif(m) {
  const n = String((m.home ? m.game.home : m.game.away).name || '').toLowerCase(); const k = m.game.league.kind;
  if (k === 'soccer' && n.includes('arsenal')) return 'cannons';
  if (k === 'soccer' && n.includes('brighton')) return 'gulls';
  if (k === 'soccer' && n.includes('newcastle')) return 'magpies';
  if (k === 'soccer' && n.includes('crystal palace')) return 'eagles';
  if (k === 'rugby') return 'cinematic';
  return 'burst';
}
/** Five looks per team, taking turns so back-to-back scores never look the same. */
function motifFor(m) {
  if ((m.kind || 'score') !== 'score') return '';
  const own = ownMotif(m);
  const looks = [...new Set([own, 'wordwall', 'logopeek', own === 'burst' ? 'logos' : 'burst', 'logos'])];
  const key = norm((m.home ? m.game.home : m.game.away).name);
  const i = variantTurn.get(key) || 0; variantTurn.set(key, i + 1);
  return looks[i % looks.length];
}

/** Team stats from the summary's box score, football's favourites first, then whatever the sport has. */
function statsFrom(g, j) {
  const teams = ((j.boxscore || {}).teams) || [];
  const of = (id) => { const t = teams.find((x) => x.team && String(x.team.id) === String(id)); const m = {}; for (const st of (t && t.statistics) || []) m[st.name] = [st.label || st.name, st.displayValue]; return m; };
  const h = of(g.home.id), a = of(g.away.id);
  const prefer = [['possessionPct', 'Possession'], ['totalShots', 'Shots'], ['shotsOnTarget', 'On target'], ['wonCorners', 'Corners'], ['foulsCommitted', 'Fouls']];
  let keys = prefer.filter(([k]) => h[k] && a[k]);
  if (!keys.length) keys = Object.keys(h).filter((k) => a[k] && !isNaN(parseFloat(h[k][1]))).slice(0, 5).map((k) => [k, h[k][0]]);
  return keys.map(([k, label]) => {
    const pct = /pct|possession/i.test(k); const hv = String(h[k][1]), av = String(a[k][1]);
    return { label, home: pct && !hv.endsWith('%') ? hv + '%' : hv, away: pct && !av.endsWith('%') ? av + '%' : av, h: parseFloat(hv) || 0, a: parseFloat(av) || 0 };
  });
}

/** From the live commentary: the fourth official's added time, and VAR (football) / TMO (rugby) checks and decisions. */
function commentaryMoments(g, j, seen) {
  const out = [];
  for (const x of j.commentary || []) {
    const text = x.text || (x.play && x.play.text) || ''; const id = String(x.sequence || text);
    if (seen.has(id) || !text) continue; seen.add(id);
    const minute = (x.time && x.time.displayValue) || '';
    const add = /(\d+)\s+minutes?\s+of\s+(?:added|additional|stoppage)\s+time/i.exec(text);
    if (add) out.push({ kind: 'added', game: g, home: true, before: '', label: `+${add[1]}`, who: 'minutes of added time', minute, extra: add[1] });
    const ref = /\bVAR\b/.test(text) ? 'VAR' : /\bTMO\b/.test(text) ? 'TMO' : null;
    if (!ref) continue;
    if (/decision|overturned|stands/i.test(text)) {
      const outcome = (text.includes(':') ? text.slice(text.indexOf(':') + 1) : text).trim().replace(/\.$/, '').split(' (')[0].slice(0, 44);
      const bad = /\b(no|disallowed|cancelled|overturned|not given|chalked)\b/i.test(outcome);
      const named = outcome.toLowerCase().includes(g.home.name.toLowerCase()) ? 1 : outcome.toLowerCase().includes(g.away.name.toLowerCase()) ? 0 : -1;
      const favour = named === -1 ? -1 : bad ? 1 - named : named;
      out.push({ kind: 'decision', game: g, home: favour !== 0, before: '', label: ref, who: outcome.toUpperCase(), minute, extra: ref, favour, good: !bad });
    } else {
      const m1 = /(possible|potential)\s+([a-z\- ]{3,24})/i.exec(text); const m2 = /check(?:ing)?\s+(?:for\s+)?(?:a\s+)?([a-z\- ]{3,24})/i.exec(text);
      const reason = m1 ? m1[0] : m2 ? `Possible ${m2[1]}` : 'Checking';
      out.push({ kind: 'check', game: g, home: true, before: '', label: ref, who: reason.trim().replace(/^./, (c) => c.toUpperCase()), minute, extra: ref });
    }
  }
  return out;
}

/** Starting lineup + bench for the team(s) you follow. */
function lineupsFrom(g, j, mine) {
  const out = [];
  for (const r of j.rosters || []) {
    const tid = String((r.team && r.team.id) || '');
    const home = r.homeAway ? r.homeAway === 'home' : tid === g.home.id;
    const side = home ? g.home : g.away;
    if (!mine.some((t) => matches(t, side))) continue;
    const players = (r.roster || []).map((p) => ({ no: String(p.jersey || ''), name: (p.athlete && p.athlete.displayName) || '', pos: (p.position && p.position.abbreviation) || '', starter: !!p.starter })).filter((p) => p.name);
    if (players.filter((p) => p.starter).length < 5) continue;
    const byNo = (a, b) => (parseInt(a.no, 10) || 99) - (parseInt(b.no, 10) || 99);
    const formation = typeof r.formation === 'object' && r.formation ? r.formation.name || '' : r.formation || '';
    const label = g.league.kind === 'rugby' ? 'STARTING XV' : g.league.kind === 'soccer' ? 'STARTING XI' : 'LINEUP';
    out.push({ kind: 'lineup', game: g, home, before: side.text, label, who: side.name, extra: formation, players: [...players.filter((p) => p.starter).sort(byNo), ...players.filter((p) => !p.starter).sort(byNo)] });
  }
  return out;
}

async function demoLogos(which) {
  if (which === 'six') return {};
  const path = ['try', 'tmo', 'lineup'].includes(which) ? 'rugby/289234' : 'soccer/eng.1';
  const out = {};
  try {
    const j = await get(BASE + path + '/teams');
    for (const x of ((((j.sports || [])[0] || {}).leagues || [])[0] || {}).teams || []) {
      const t = x.team; const l = t && logoOf(t); if (!l) continue;
      for (const n of [t.displayName, t.shortDisplayName, t.name]) if (n && !out[norm(n)]) out[norm(n)] = l;
    }
  } catch { /* offline: colour badges */ }
  try { const j = await get(BASE + path + '/scoreboard'); const l = (((j.leagues || [])[0] || {}).logos || [])[0]; if (l && l.href) out.league = l.href; } catch { /* */ }
  return out;
}

// ---------------------------------------------------------------- watcher
class Sports {
  /** onChange({games}) whenever the list changes; onGoal(score) when one of your teams scores. */
  constructor({ getTeams, enabled, onChange, onGoal, onLearn, events = () => true, log = () => {} }) {
    Object.assign(this, { getTeams, enabled, onChange, onLearn, events, log });
    this.onGoal = (m) => onGoal({ kind: 'score', ...m, motif: m.motif || motifFor({ kind: 'score', ...m }) });
    this.seenEvents = new Map(); this.cards = new Map(); this.lineupShown = new Set(); this.stats = new Map(); this.seenComm = new Map();
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
    // Games whose feed has no logo: use the national logos we know.
    for (const g of found) for (const sd of [g.home, g.away]) if (!sd.logo) sd.logo = knownLogo(sd.name, g.league.kind);
    // Teams picked from our own lists (national sides, SA20…) learn their ESPN logo the first time they play.
    if (mine.some((t) => !t.logo && t.league === RUGBY_TESTS)) await nationLogos().catch(() => {});
    if (this.onLearn) {
      const sides = found.flatMap((g) => [g.home, g.away]);
      let changed = false;
      const learned = (this.getTeams() || []).map((t) => {
        if (t.logo) return t;
        const s = sides.find((x) => matches(t, x) && x.logo);
        const lg = league(t.league);
        const logo = (s && s.logo) || knownLogo(t.name, lg && lg.kind);
        if (!logo) return t; changed = true; return { ...t, logo };
      });
      if (changed) this.onLearn(learned);
    }
    for (const g of found) {
      const prev = this.last.get(g.id);
      // Names behind the numbers: scorers, cards and substitutions from the match summary.
      const wantSummary = g.feed && g.league.kind !== 'cricket' && this.events() &&
        (g.live || (g.state === 'pre' && g.start - now < 90 * 60000 && !this.lineupShown.has(g.id)));
      const summary = wantSummary ? await get(`${BASE}${g.feed}/summary?event=${g.id}`).catch(() => null) : null;
      const fresh = summary && g.live ? this.newEvents(g, summary, now) : [];
      if (summary) {
        try { this.stats.set(g.id, statsFrom(g, summary)); } catch { /* */ }
        if (g.live) {
          const first = !this.seenComm.has(g.id);
          const seen = this.seenComm.get(g.id) || new Set(); this.seenComm.set(g.id, seen);
          const cm = commentaryMoments(g, summary, seen);
          if (!first) cm.forEach((m) => this.onGoal({ ...m, at: now }));
        }
        if (!this.lineupShown.has(g.id)) {
          const ls = lineupsFrom(g, summary, mine);
          if (ls.length) { this.lineupShown.add(g.id); ls.forEach((m) => this.onGoal({ ...m, at: now })); }
        }
      }
      if (prev) {
        const m = moment(prev, g, now, this.kicks);
        if (m) {
          const tid = (m.home ? g.home : g.away).id;
          const sc = [...fresh].reverse().find((e) => ['goal', 'try', 'conversion', 'penalty', 'drop', 'pentry'].includes(e.kind) && e.teamId === tid);
          this.onGoal(sc && sc.player ? { ...m, who: scorerLine(m, sc) } : m);
        }
      }
      const ms = fresh.map((e) => this.eventMoment(g, e, now)).filter(Boolean);
      for (const m of ms.filter((x) => x.kind !== 'sub')) this.onGoal(m);
      for (const home of [true, false]) { // a double change = one board
        const subs = ms.filter((x) => x.kind === 'sub' && x.home === home);
        const pairs = subs.flatMap((x) => x.subs);
        for (let i = 0; i < pairs.length; i += 2) this.onGoal({ ...subs[0], subs: pairs.slice(i, i + 2), minute: subs[subs.length - 1].minute });
      }
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
    for (const g of found) { g.cards = this.liveCards(g, now); g.stats = this.stats.get(g.id) || []; }
    const show = found.filter((g) => g.live || (g.state === 'post' && now - (this.finishedAt.get(g.id) || 0) < 15 * 60000) ||
      (g.state === 'pre' && g.start - now >= 0 && g.start - now < 15 * 60000));
    show.sort((a, b) => (a.live ? 0 : a.state === 'pre' ? 1 : 2) - (b.live ? 0 : b.state === 'pre' ? 1 : 2));
    this.set(show);
    if (found.some((g) => g.live)) return found.some((g) => g.live && g.league.kind === 'cricket') ? 15000 : 20000;
    if (found.some((g) => g.state === 'pre' && g.start - now < 30 * 60000)) return 60000;
    return 10 * 60000;
  }
  newEvents(g, summary, now) {
    const all = keyEvents(g, summary);
    const seen = this.seenEvents.get(g.id);
    const isCard = (e) => ['yellow', 'red', 'red20'].includes(e.kind);
    if (!seen) { // the first look only learns what already happened (no replays), but cards already running still count
      this.seenEvents.set(g.id, new Set(all.map((e) => e.id)));
      all.filter(isCard).forEach((e) => this.addCard(g, e, now));
      return [];
    }
    const fresh = all.filter((e) => !seen.has(e.id));
    fresh.forEach((e) => seen.add(e.id));
    fresh.filter(isCard).forEach((e) => this.addCard(g, e, now));
    return fresh;
  }
  addCard(g, e, now) {
    const home = e.teamId === g.home.id;
    const dur = g.league.kind === 'rugby' ? ({ yellow: 10, red20: 20 })[e.kind] || 0 : 0;
    const list = this.cards.get(g.id) || []; this.cards.set(g.id, list);
    if (list.some((c) => c.player === e.player && c.kind === e.kind && c.minute === e.minute)) return;
    // Minutes left by the match clock (playing time); between looks we count down by the wall clock.
    let left = dur;
    if (dur) { const cm = minuteOf(g.clock), em = minuteOf(e.minute); if (cm != null && em != null) left = Math.max(0, em + dur - cm); }
    list.push({ home, player: e.player, kind: e.kind, minute: e.minute, until: dur ? now + left * 60000 : 0 });
  }
  liveCards(g, now) {
    return (this.cards.get(g.id) || []).filter((c) => (c.until && c.until > now - 30000) || c.kind === 'red' || (g.league.kind === 'soccer' && c.kind === 'yellow')).slice(-6);
  }
  eventMoment(g, e) {
    const home = e.teamId === g.home.id;
    if (e.teamId && !home && e.teamId !== g.away.id) return null;
    const side = home ? g.home : g.away;
    const card = (k) => [...(this.cards.get(g.id) || [])].reverse().find((c) => c.player === e.player && c.kind === k);
    if (e.kind === 'yellow') return { kind: 'yellow', game: g, home, before: side.text, label: 'YELLOW CARD', who: e.player, minute: e.minute, until: (card('yellow') || {}).until || 0, at: Date.now() };
    if (e.kind === 'red' || e.kind === 'red20') return { kind: e.kind, game: g, home, before: side.text, label: 'RED CARD', who: e.player, minute: e.minute, until: (card(e.kind) || {}).until || 0, at: Date.now() };
    if (e.kind === 'sub') return { kind: 'sub', game: g, home, before: side.text, label: g.league.kind === 'rugby' ? 'REPLACEMENT' : 'SUBSTITUTION', who: '', minute: e.minute, subs: [[e.player, e.player2]], at: Date.now() };
    return null;
  }

  /** A pretend game of the chosen sport and its big moment, so you can see it without waiting for kick-off. */
  async demo(which = 'goal') {
    which = ({ soccer: 'goal', rugby: 'try', cricket: 'six' })[which] || which;
    if (['try', 'tmo', 'lineup'].includes(which)) await Promise.race([nationLogos().catch(() => {}), new Promise((r) => setTimeout(r, 3000))]);
    const stats = [{ label: 'Possession', home: '54%', away: '46%', h: 54, a: 46 }, { label: 'Shots', home: '11', away: '7', h: 11, a: 7 },
      { label: 'On target', home: '5', away: '2', h: 5, a: 2 }, { label: 'Corners', home: '6', away: '3', h: 6, a: 3 }];
    const tests = { path: 'demo', name: 'Nations Championship', kind: 'rugby' };
    const boks = (n) => ({ id: '1', abbr: 'RSA', name: 'South Africa', score: n, text: String(n), color: '#00704a', logo: knownLogo('South Africa', 'rugby') });
    const eng = (n) => ({ id: '2', abbr: 'ENG', name: 'England', score: n, text: String(n), color: '#c8102e', logo: knownLogo('England', 'rugby') });
    const epl = { path: 'demo', name: 'Premier League', kind: 'soccer' };
    const ars = { id: '1', abbr: 'ARS', name: 'Arsenal', score: 2, text: '2', color: '#e20520' };
    const che = { id: '2', abbr: 'CHE', name: 'Chelsea', score: 1, text: '1', color: '#034694' };
    const base = { id: 'demo', state: 'in', live: true, start: 0, lastPlay: '' };
    let g, ms = [];
    const now = Date.now();
    if (which === 'try') {
      const lg = { path: 'demo', name: 'Nations Championship', kind: 'rugby' };
      g = { ...base, league: lg, clock: "47'", lastPlay: "C. Kolbe 47'", home: { id: '1', abbr: 'RSA', name: 'South Africa', score: 15, text: '15', color: '#00704a', logo: knownLogo('South Africa', 'rugby') },
        away: { id: '2', abbr: 'ENG', name: 'England', score: 14, text: '14', color: '#c8102e', logo: knownLogo('England', 'rugby') } };
      const g2 = { ...g, clock: "48'", lastPlay: 'H. Pollard converts 48\'', home: { ...g.home, score: 17, text: '17' } };
      const until = now + 2600 + 15500 + 10 * 60000;
      ms = [{ game: g, home: true, before: '10', label: 'TRY', who: "C. Kolbe 47'", chant: 'BOKKE' },
        { game: g2, home: true, before: '15', label: 'CONVERTED', who: "H. Pollard converts 48'" },
        { kind: 'yellow', game: g2, home: false, before: '14', label: 'YELLOW CARD', who: 'M. Itoje', minute: "49'", until }];
      g = { ...g2, cards: [{ home: false, player: 'M. Itoje', kind: 'yellow', minute: "49'", until }] };
    } else if (which === 'six') {
      const lg = { path: 'demo', name: 'T20I · 2nd T20I', kind: 'cricket' };
      g = { ...base, league: lg, clock: '17.5 ov', lastPlay: 'South Africa need 16 runs from 13 balls',
        home: { id: '1', abbr: 'SA', name: 'South Africa', score: 167, text: '167/5', wickets: 5, sub: '17.5 ov', color: '#00843d', logo: cricLogo('South Africa') },
        away: { id: '2', abbr: 'AUS', name: 'Australia', score: 182, text: '182/7', wickets: 7, sub: '20 ov', color: '#f2b51c', logo: cricLogo('Australia') } };
      ms = [{ game: g, home: true, before: '161/5', label: 'SIX', who: g.lastPlay, chant: 'PROTEAS' }];
    } else if (which === 'gulls') {
      g = { ...base, league: epl, clock: "71'", lastPlay: "K. Mitoma 71'", home: { id: '1', abbr: 'BHA', name: 'Brighton', score: 2, text: '2', color: '#0057b8' },
        away: { id: '2', abbr: 'NEW', name: 'Newcastle', score: 1, text: '1', color: '#3a3a3a' } };
      ms = [{ game: g, home: true, before: '1', label: 'GOAL', who: "K. Mitoma 71'" }];
    } else if (which === 'added') {
      g = { ...base, league: epl, clock: "90'", lastPlay: "B. Saka 67'", home: ars, away: che, stats };
      ms = [{ kind: 'added', game: g, home: true, before: '2', label: '+4', who: 'minutes of added time', extra: '4', minute: "90'" }];
    } else if (which === 'var') {
      g = { ...base, league: epl, clock: "67'", lastPlay: "B. Saka 67'", home: ars, away: che, stats };
      ms = [{ kind: 'check', game: g, home: true, before: '2', label: 'VAR', who: 'Possible offside', extra: 'VAR', minute: "67'" },
        { kind: 'decision', game: g, home: true, before: '2', label: 'VAR', who: 'GOAL STANDS', extra: 'VAR', favour: 1, good: true, minute: "68'" }];
    } else if (which === 'tmo') {
      g = { ...base, league: tests, clock: "62'", home: boks(17), away: eng(14) };
      ms = [{ kind: 'check', game: g, home: false, before: '14', label: 'TMO', who: 'Possible knock-on', extra: 'TMO', minute: "62'" },
        { kind: 'decision', game: g, home: true, before: '17', label: 'TMO', who: 'NO TRY', extra: 'TMO', favour: 1, good: false, minute: "63'" }];
    } else if (which === 'lineup') {
      g = { ...base, league: tests, state: 'pre', live: false, clock: '17:00', home: boks(0), away: eng(0), start: now + 3600000 };
      const xv = ['Ox Nché', 'Malcolm Marx', 'Frans Malherbe', 'Eben Etzebeth', 'RG Snyman', 'Siya Kolisi', 'Pieter-Steph du Toit', 'Jasper Wiese', 'Cobus Reinach', 'Handré Pollard',
        'Makazole Mapimpi', 'Damian de Allende', 'Jesse Kriel', 'Cheslin Kolbe', 'Damian Willemse'];
      const bench = ['Bongi Mbonambi', 'Steven Kitshoff', 'Vincent Koch', 'Franco Mostert', 'Kwagga Smith', 'Faf de Klerk', 'Manie Libbok', 'Willie le Roux'];
      ms = [{ kind: 'lineup', game: g, home: true, before: '0', label: 'STARTING XV', who: 'South Africa',
        players: [...xv.map((n, i) => ({ no: String(i + 1), name: n, starter: true })), ...bench.map((n, i) => ({ no: String(i + 16), name: n, starter: false }))] }];
    } else if (which === 'cards') {
      g = { ...base, league: epl, clock: "78'", home: { ...ars, score: 1, text: '1' }, away: che };
      ms = [{ kind: 'red', game: g, home: false, before: '1', label: 'RED CARD', who: 'M. Caicedo', minute: "78'" }];
      g = { ...g, cards: [{ home: false, player: 'M. Caicedo', kind: 'red', minute: "78'", until: 0 }] };
    } else if (which === 'sub') {
      g = { ...base, league: epl, clock: "64'", home: ars, away: che };
      ms = [{ kind: 'sub', game: g, home: true, before: '2', label: 'SUBSTITUTION', who: '', minute: "64'", subs: [['G. Martinelli', 'L. Trossard'], ['M. Merino', 'Jorginho']] }];
    } else {
      g = { ...base, league: epl, clock: "67'", lastPlay: "B. Saka 67'", home: ars, away: che, stats };
      ms = [{ game: g, home: true, before: '1', label: 'GOAL', who: "B. Saka 67'" }];
    }
    // Previews use the real ESPN logos (and competition logo), same as a live game.
    const logos = await demoLogos(which).catch(() => ({}));
    const fix = (x) => { for (const sd of [x.home, x.away]) if (!sd.logo && logos[norm(sd.name)]) sd.logo = logos[norm(sd.name)]; if (!x.leagueLogo && logos.league) x.leagueLogo = logos.league; return x; };
    fix(g); ms.forEach((m) => fix(m.game));
    const first = ms[0];
    const start = { ...g, cards: [], home: { ...g.home, text: first.home ? first.before : g.home.text }, away: { ...g.away, text: !first.home ? first.before : g.away.text } };
    this.demoUntil = now + (which === 'lineup' ? 50000 : 40000);
    this.set([start]);
    setTimeout(() => { this.set([g]); ms.forEach((m) => this.onGoal({ ...m, at: Date.now() })); }, 2600);
    setTimeout(() => { this.demoUntil = 0; this.set([]); this.refresh(); }, 40000);
  }
}

module.exports = { Sports, LEAGUES, teams, scoreboard, parse, parseCricket, innings, moment, readable, keyEvents, motifFor };
