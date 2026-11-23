'use strict';
// Checks the sports watcher with a fake ESPN feed: parsing, colours, and goal detection.
const assert = require('assert');
const { Sports } = require('../src/sports');
let home = 1, away = 0, state = 'in';
const ev = () => ({ events: [{ id: '42', date: '2026-11-23T15:00Z', competitions: [{
  status: { type: { state, shortDetail: state === 'post' ? 'FT' : "67'" } },
  competitors: [
    { homeAway: 'home', score: String(home), team: { id: '1', abbreviation: 'ARS', shortDisplayName: 'Arsenal', color: 'e20520', alternateColor: 'ffffff' } },
    { homeAway: 'away', score: String(away), team: { id: '2', abbreviation: 'MUN', shortDisplayName: 'Man United', color: 'da020e', alternateColor: 'fbe122' } }],
  details: away ? [{ scoringPlay: true, clock: { displayValue: "67'" }, type: { text: 'Goal' }, team: { id: '2' }, athletesInvolved: [{ displayName: 'B. Fernandes' }] }] : [] }] }] });
global.fetch = async () => ({ ok: true, json: async () => ev() });
(async () => {
  const goals = []; let games = [];
  const s = new Sports({ getTeams: () => [{ league: 'soccer/eng.1', id: '2' }], enabled: () => true, onChange: (g) => { games = g; }, onGoal: (x) => goals.push(x) });
  assert.strictEqual(await s.poll(), 20000);
  assert.strictEqual(games.length, 1);
  assert.strictEqual(games[0].home.abbr, 'ARS');
  assert.notStrictEqual(games[0].home.color, games[0].away.color, 'two reds should be split');
  away = 1; await s.poll();
  assert.strictEqual(goals.length, 1); assert.strictEqual(goals[0].label, 'GOAL'); assert.strictEqual(goals[0].home, false);
  assert.strictEqual(goals[0].who, "B. Fernandes 67'");
  state = 'post'; await s.poll();
  assert.strictEqual(games[0].clock, 'Full time');
  console.log('Sports watcher OK', games[0].home.color, games[0].away.color);
})().catch((e) => { console.error(e); process.exit(1); });
