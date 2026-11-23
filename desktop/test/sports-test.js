'use strict';
// Checks the sports watcher with fake ESPN feeds: football goals, rugby tries/penalties, cricket sixes/wickets.
const assert = require('assert');
const { Sports, innings } = require('../src/sports');

let fb = { home: 1, away: 0, state: 'in' };
let rg = { home: 10, away: 14, play: '' };
let ck = { a: '161/5 (17.4/20 ov, target 183)', b: '182/7', state: 'in', summary: 'South Africa need 22 runs from 14 balls' };
const football = () => ({ events: [{ id: '42', date: '2026-11-23T15:00Z', competitions: [{
  status: { type: { state: fb.state, shortDetail: fb.state === 'post' ? 'FT' : "67'" } },
  competitors: [
    { homeAway: 'home', score: String(fb.home), team: { id: '1', abbreviation: 'ARS', shortDisplayName: 'Arsenal', color: 'e20520', alternateColor: 'ffffff' } },
    { homeAway: 'away', score: String(fb.away), team: { id: '2', abbreviation: 'MUN', shortDisplayName: 'Man United', color: 'da020e', alternateColor: 'fbe122' } }],
  details: fb.away ? [{ scoringPlay: true, clock: { displayValue: "67'" }, type: { text: 'Goal' }, team: { id: '2' }, athletesInvolved: [{ displayName: 'B. Fernandes' }] }] : [] }] }] });
const rugby = () => ({ events: [{ id: 'r1', date: '2026-11-23T15:00Z', competitions: [{
  status: { type: { state: 'in', shortDetail: "47'" } }, situation: { lastPlay: { text: rg.play } },
  competitors: [
    { homeAway: 'home', score: String(rg.home), team: { id: '9', abbreviation: 'RSA', shortDisplayName: 'South Africa', color: '006400' } },
    { homeAway: 'away', score: String(rg.away), team: { id: '1', abbreviation: 'ENG', shortDisplayName: 'England', color: 'ffffff', alternateColor: 'c8102e' } }] }] }] });
const cricket = () => ({ scores: [{ events: [{ id: 'c1', date: '2026-11-23T15:00Z', competitions: [{
  status: { type: { state: ck.state }, summary: ck.summary }, class: { generalClassCard: 'T20I' }, description: '2nd T20I',
  competitors: [{ score: ck.a, team: { id: '3', displayName: 'South Africa', abbreviation: 'SA' } }, { score: ck.b, team: { id: '2', displayName: 'Australia', abbreviation: 'AUS' } }] }] }] }] });
global.fetch = async (url) => ({ ok: true, json: async () => (/cricket/.test(url) ? cricket() : /rugby/.test(url) ? (/289234/.test(url) ? rugby() : { events: [] }) : football()) });

(async () => {
  // innings parsing
  assert.deepStrictEqual(innings('161/5 (18/20 ov, target 156)'), { runs: 161, wickets: 5, text: '161/5', overs: '18/20 ov' });
  assert.strictEqual(innings('245 & 120/3 (35 ov)').runs, 120);
  assert.strictEqual(innings('245').wickets, 10);

  const moments = []; let games = [];
  const teams = [{ league: 'soccer/eng.1', id: '2', name: 'Manchester United' }, { league: 'rugby/tests', id: '', abbr: 'RSA', name: 'South Africa' },
    { league: 'cricket/intl', id: '', abbr: 'SA', name: 'South Africa' }];
  const s = new Sports({ getTeams: () => teams, enabled: () => true, onChange: (g) => { games = g; }, onGoal: (x) => moments.push(x) });
  assert.strictEqual(await s.poll(), 15000, 'cricket live → poll every 15 s');
  assert.strictEqual(games.length, 3, 'one game per sport');
  const fg = games.find((g) => g.id === '42');
  assert.notStrictEqual(fg.home.color, fg.away.color, 'two reds should be split');

  fb.away = 1; rg.home = 15; rg.play = 'Kolbe try'; ck.a = '167/5 (17.5/20 ov)';
  await s.poll();
  const by = (l) => moments.find((m) => m.label === l);
  assert.ok(by('GOAL') && by('GOAL').who === "B. Fernandes 67'");
  assert.ok(by('TRY') && by('TRY').chant === 'BOKKE' && by('TRY').before === '10');
  assert.ok(by('SIX') && by('SIX').before === '161/5' && by('SIX').chant === 'PROTEAS');

  moments.length = 0; rg.home = 18; rg.play = 'Pollard penalty'; ck.a = '167/6 (18/20 ov)';
  await s.poll();
  assert.ok(by('PENALTY'), 'rugby +3 = penalty');
  assert.ok(by('WICKET') && by('WICKET').home === false, 'a wicket is the bowling side\'s moment');

  moments.length = 0; rg.home = 21; rg.play = 'Libbok drop goal'; ck.state = 'post'; ck.summary = 'South Africa won by 4 wickets';
  await s.poll();
  assert.ok(by('DROP GOAL')); assert.ok(by('WIN') && by('WIN').home === true);
  // Conversions: try → "conversion to come" → +2 = CONVERTED; a try then another score = missed.
  moments.length = 0; rg.home = 26; rg.play = 'Am try';
  await s.poll();
  assert.ok(by('TRY')); assert.match(games.find((g) => g.id === 'r1').note, /conversion to come/);
  moments.length = 0; rg.home = 28; rg.play = 'Pollard conversion';
  await s.poll();
  assert.ok(by('CONVERTED') && by('CONVERTED').chant === '', 'conversion after a try');
  assert.strictEqual(games.find((g) => g.id === 'r1').note, undefined);
  moments.length = 0; rg.home = 33; rg.play = 'Arendse try'; await s.poll();
  moments.length = 0; rg.home = 36; rg.play = 'Pollard penalty'; await s.poll();
  assert.ok(by('PENALTY')); assert.match(games.find((g) => g.id === 'r1').note, /conversion missed/);
  moments.length = 0; rg.home = 43; rg.play = 'Penalty try'; await s.poll();
  assert.ok(by('PENALTY TRY') && /no kick/.test(by('PENALTY TRY').who));
  moments.length = 0; rg.home = 50; rg.play = 'Etzebeth try'; await s.poll();
  assert.ok(by('TRY') && /Converted/.test(by('TRY').who), '+7 in one go = converted try');
  console.log('Sports watcher OK:', ['GOAL', 'TRY', 'SIX', 'PENALTY', 'WICKET', 'DROP GOAL', 'WIN', 'CONVERTED', 'conversion missed', 'PENALTY TRY'].join(', '));
})().catch((e) => { console.error(e); process.exit(1); });
