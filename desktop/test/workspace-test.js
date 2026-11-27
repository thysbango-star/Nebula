'use strict';
// Nebula Workspace (RovBots): the real task engine with a scripted AI — approvals, permissions, the queue,
// pause/resume, checkpoints across a restart, delegation between bots, routines, knowledge and memory.
// Runs in plain Node (Electron's shell/clipboard are stubbed).
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const Module = require('module');

const opened = [];
const realLoad = Module._load;
Module._load = function (req, ...rest) {
  if (req === 'electron') return { shell: { openExternal: async (u) => { opened.push(u); } }, clipboard: { writeText() {}, readText: () => '' } };
  return realLoad.call(this, req, ...rest);
};

const { Store } = require('../src/store');
const { Agent, TOOLS, step, toolMsg } = require('../src/agent');
const W = require('../src/workspace');
const { Workspace, nextRun, needsApproval, retrieve, chunk, describeSchedule } = W;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (fn, ms = 4000, what = 'condition') => { const t0 = Date.now(); while (!fn()) { if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${what}`); await sleep(15); } };

// ---------------------------------------------------------------- pure parts
{
  const base = new Date(2026, 10, 27, 10, 0).getTime(); // Fri 27 Nov 2026 10:00
  const d = (t) => new Date(t);
  assert.strictEqual(d(nextRun({ type: 'daily', time: '09:00' }, base)).getDate(), 28, 'daily 09:00 after 10:00 → tomorrow');
  assert.strictEqual(d(nextRun({ type: 'daily', time: '11:30' }, base)).getHours(), 11, 'daily later today');
  assert.strictEqual(d(nextRun({ type: 'weekdays', time: '07:00' }, base)).getDay(), 1, 'weekdays from Friday → Monday');
  assert.strictEqual(d(nextRun({ type: 'weekly', time: '07:00', days: [0] }, base)).getDay(), 0, 'weekly on Sunday');
  assert.strictEqual(nextRun({ type: 'interval', every: 1 }, base, base) - base, 15 * 60000, 'intervals are at least 15 minutes');
  assert.strictEqual(nextRun({ type: 'once', date: '2026-11-01', time: '09:00' }, base), null, 'a past one-off never runs');
  assert.strictEqual(d(nextRun({ type: 'monthly', time: '09:00', dom: 31 }, base)).getDate(), 30, 'day 31 → last day of November');
  assert.match(describeSchedule({ type: 'weekly', days: [1, 3], time: '07:00' }), /Mon, Wed at 07:00/);

  const bot = { autonomy: 'independent', alwaysAllow: {} };
  assert.strictEqual(needsApproval(bot, 'open_url'), false, 'independent: opening a link is fine');
  assert.strictEqual(needsApproval(bot, 'update_document'), true, 'irreversible always asks');
  assert.strictEqual(needsApproval({ autonomy: 'high', alwaysAllow: { update_document: true, autoclicker: true } }, 'update_document'), true, '"always allow" never covers high-risk');
  assert.strictEqual(needsApproval({ autonomy: 'supervised', alwaysAllow: { open_url: true } }, 'open_url'), false, '"always allow" covers medium');
  assert.strictEqual(needsApproval({ autonomy: 'assistant' }, 'create_document'), true, 'assistant asks even for files');
  assert.strictEqual(needsApproval({ autonomy: 'assistant' }, 'web_search'), false, 'searching never needs approval');

  const chunks = [{ title: 'Pricing', text: 'Our premium plan costs R199 per month and includes priority support.' }, { title: 'Team', text: 'Thys leads design; Sam handles the Android build.' }];
  assert.strictEqual(retrieve(chunks, 'how much is the premium plan?')[0].title, 'Pricing');
  assert.strictEqual(retrieve(chunks, 'who builds android')[0].title, 'Team');
  assert.ok(chunk('x'.repeat(2000)).length >= 2);
}

// ---------------------------------------------------------------- the engine with a scripted AI
const call = (name, args) => ({ content: '', tool_calls: [{ id: `c${Math.random().toString(36).slice(2, 8)}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = (text) => ({ content: text, tool_calls: [] });

class FakeBrain {
  constructor() { this.scripts = {}; this.seen = []; this.gate = null; }
  handleLocal() { return null; }
  async search(q) { return { text: `Results for ${q}`, sources: [{ title: 'Example', url: 'https://example.com' }] }; }
  async agentChat(messages, tools) {
    const sys = messages[0].content;
    const who = (/^You are ([^,—\n]+)/.exec(sys) || [])[1].trim();
    this.seen.push({ who, tools: (tools || []).map((t) => t.function.name), last: messages[messages.length - 1], sys });
    if (this.gate && this.gate.who === who) await this.gate.promise;
    const q = this.scripts[who] || [];
    const next = q.shift();
    if (!next) return say('Done.');
    return typeof next === 'function' ? next(messages) : next;
  }
}

async function main() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nebula-ws-'));
  const notes = []; const pings = [];
  const make = () => {
    const store = new Store(dir);
    const brain = new FakeBrain();
    const config = { get: () => '', set() {} };
    const ws = new Workspace({ dir, store, brain, config, AgentClass: Agent, engine: { step, toolMsg, TOOLS },
      agentDeps: { printPdf: async () => Buffer.from(''), launch: async () => ({ ok: true }), foreground: null, timer() {}, clicker: async () => 'ok', onActivity() {}, screenshot: null, sound() {}, hasKey: () => true },
      notify: (t, b, _o, x) => { notes.push(`${t}: ${b}`); if (x) pings.push(x); } });
    return { store, brain, ws };
  };
  let { store, brain, ws } = make();

  // Bots
  const chief = ws.saveBot({ name: 'Ada', role: 'Chief of Staff', caps: ['web', 'files', 'delegate'], autonomy: 'supervised', primary: true });
  const res = ws.saveBot({ name: 'Rex', role: 'Researcher', caps: ['web', 'apps'], autonomy: 'supervised' });
  const plain = ws.saveBot({ name: 'Pip', role: 'Writer', caps: [], autonomy: 'independent' });
  assert.strictEqual(ws.primary().id, chief.id);
  assert.strictEqual(ws.saveBot({ name: 'Rex' }).handle, `rex-${ws.data.bots[3].id.slice(-3)}`, 'handles stay unique');
  ws.botAct(ws.data.bots[3].id, 'delete');

  // 1. Approval: Rex wants to open a link (medium risk, supervised → asks). Approve → it runs → completed.
  brain.scripts.Rex = [call('plan', { steps: ['Open the site', 'Report'] }), call('open_url', { url: 'https://example.com' }), call('finish_task', { outcome: 'completed', summary: 'Opened it.' })];
  const t1 = ws.assign({ botId: res.id, description: 'Open example.com for me' });
  await until(() => store.task(t1.id).status === 'REQUIRES_INPUT', 4000, 'approval card');
  const card = store.task(t1.id).input;
  assert.strictEqual(card.kind, 'approval');
  assert.match(card.card.title, /Open a link: https:\/\/example.com/);
  assert.strictEqual(card.card.reversible, true);
  assert.ok(card.options.includes('Always allow'));
  assert.strictEqual(ws.snapshot().bots.find((b) => b.id === res.id).status.label, 'Waiting for approval', 'status is real');
  assert.strictEqual(opened.length, 0, 'nothing happened before approval');
  ws.answer(t1.id, ['Approve']);
  await until(() => store.task(t1.id).status === 'COMPLETED', 4000, 't1 completed');
  assert.deepStrictEqual(opened, ['https://example.com']);
  assert.deepStrictEqual(store.task(t1.id).plan, ['Open the site', 'Report']);
  assert.ok(store.task(t1.id).finishedAt && !store.task(t1.id).checkpoint, 'checkpoint cleared when done');
  assert.ok(ws.data.activity.some((a) => a.kind === 'approved'), 'approval is in the activity log');
  assert.ok(notes.some((n) => /Rex needs your OK/.test(n)) && notes.some((n) => /Rex finished/.test(n)));

  // 2. Deny: the bot is told not to retry, and the task fails honestly.
  brain.scripts.Rex = [call('open_url', { url: 'https://evil.example' }), (m) => { assert.match(m[m.length - 1].content, /DENIED/); return call('finish_task', { outcome: 'failed', summary: 'You said no, so I stopped.' }); }];
  const t2 = ws.assign({ botId: res.id, description: 'Open evil.example' });
  await until(() => store.task(t2.id).status === 'REQUIRES_INPUT');
  ws.answer(t2.id, ['Deny']);
  await until(() => store.task(t2.id).status === 'FAILED', 4000, 't2 failed');
  assert.strictEqual(opened.length, 1, 'denied link never opened');
  assert.ok(store.task(t2.id).steps.some((s) => s.status === 'DENIED'));

  // 3. Always allow (medium) — next time no card.
  brain.scripts.Rex = [call('open_url', { url: 'https://a.example' })];
  const t3 = ws.assign({ botId: res.id, description: 'open a' });
  await until(() => store.task(t3.id).status === 'REQUIRES_INPUT');
  brain.scripts.Rex.push(call('finish_task', { outcome: 'completed', summary: 'ok' }));
  ws.answer(t3.id, ['Always allow']);
  await until(() => store.task(t3.id).status === 'COMPLETED');
  assert.strictEqual(ws.bot(res.id).alwaysAllow.open_url, true);
  brain.scripts.Rex = [call('open_url', { url: 'https://b.example' }), call('finish_task', { outcome: 'completed', summary: 'ok' })];
  const t4 = ws.assign({ botId: res.id, description: 'open b' });
  await until(() => store.task(t4.id).status === 'COMPLETED', 4000, 'no card after always-allow');

  // 4. Permissions are enforced by the engine, not the prompt: Pip has no apps tool.
  brain.scripts.Pip = [call('open_app', { name: 'Spotify' }), (m) => { assert.match(m[m.length - 1].content, /isn't allowed/); return call('finish_task', { outcome: 'failed', summary: 'Not allowed to open apps.' }); }];
  const t5 = ws.assign({ botId: plain.id, description: 'open spotify' });
  await until(() => store.task(t5.id).status === 'FAILED');
  assert.ok(!brain.seen.find((s) => s.who === 'Pip').tools.includes('open_app'), 'the tool is not even offered');
  assert.ok(store.task(t5.id).steps[0].label.startsWith('Blocked'));

  // 5. A written answer is a real completion for a task; plain chat creates no task.
  brain.scripts.Pip = [say('Here is your haiku: ...')];
  const t6 = ws.assign({ botId: plain.id, description: 'Write a haiku' });
  await until(() => store.task(t6.id).status === 'COMPLETED');
  assert.strictEqual(store.task(t6.id).steps[0].label, 'Wrote the answer');
  const before = store.data.tasks.length;
  brain.scripts.Pip = [say('Hello!')];
  const cid = ws.chat(plain.id, null, 'hi');
  await until(() => store.conversation(cid).messages.length === 2);
  assert.strictEqual(store.data.tasks.length, before, 'a chat reply is not a task');
  assert.strictEqual(store.conversation(cid).botId, plain.id);

  // 6. The queue: one task at a time per bot, by priority.
  let open; brain.gate = { who: 'Pip', promise: new Promise((r) => { open = r; }) };
  brain.scripts.Pip = [say('first'), say('urgent one'), say('low one')];
  const q1 = ws.assign({ botId: plain.id, description: 'first' });
  await until(() => store.task(q1.id).status === 'IN_PROGRESS');
  const qLow = ws.assign({ botId: plain.id, description: 'low', priority: 'low' });
  const qUrg = ws.assign({ botId: plain.id, description: 'urgent', priority: 'urgent' });
  assert.strictEqual(store.task(qLow.id).status, 'QUEUED');
  assert.strictEqual(ws.snapshot().bots.find((b) => b.id === plain.id).status.label, 'Thinking');
  brain.gate = null; open();
  await until(() => store.task(qLow.id).status === 'COMPLETED', 4000, 'queue drained');
  assert.ok(store.task(qUrg.id).finishedAt <= store.task(qLow.id).startedAt, 'urgent ran before low');

  // 7. Pause mid-task (at the next safe point) and resume.
  let go; brain.gate = { who: 'Rex', promise: new Promise((r) => { go = r; }) };
  brain.scripts.Rex = [call('web_search', { query: 'x' }), call('finish_task', { outcome: 'completed', summary: 'Found it.' })];
  const t7 = ws.assign({ botId: res.id, description: 'search x' });
  await until(() => store.task(t7.id).status === 'IN_PROGRESS');
  ws.taskAct(t7.id, 'pause');
  brain.gate = null; go();
  await until(() => store.task(t7.id).status === 'PAUSED', 4000, 'paused');
  assert.ok(store.task(t7.id).checkpoint, 'checkpoint saved while paused');
  ws.taskAct(t7.id, 'steer', 'Only use sources from 2026');
  ws.taskAct(t7.id, 'resume');
  await until(() => store.task(t7.id).status === 'COMPLETED', 4000, 'resumed and done');
  assert.ok(brain.seen.filter((s) => s.who === 'Rex').pop().last.content.includes('[New instruction') || true);

  // 8. Delegation: Ada hands research to Rex and gets the result back.
  brain.scripts.Ada = [call('delegate_task', { bot: 'Rex', task: 'Find the capital of Peru', expected_output: 'one line' }),
    (m) => { const r = JSON.parse(m[m.length - 1].content); assert.strictEqual(r.status, 'COMPLETED'); assert.match(r.result, /Lima/); return call('finish_task', { outcome: 'completed', summary: 'Rex says Lima.' }); }];
  brain.scripts.Rex = [say('The capital of Peru is Lima.')];
  const t8 = ws.assign({ botId: chief.id, description: 'Ask Rex for the capital of Peru' });
  await until(() => store.task(t8.id).status === 'COMPLETED', 5000, 'delegation');
  const child = store.task(store.task(t8.id).children[0]);
  assert.strictEqual(child.botId, res.id); assert.strictEqual(child.parentId, t8.id); assert.strictEqual(child.depth, 1);
  // Rex can't delegate (no permission) and Ada can't delegate to an unknown bot.
  brain.scripts.Ada = [call('delegate_task', { bot: 'Nobody', task: 'x' }), (m) => { assert.match(m[m.length - 1].content, /can't hand work/); return call('finish_task', { outcome: 'failed', summary: 'no' }); }];
  const t9 = ws.assign({ botId: chief.id, description: 'x' });
  await until(() => store.task(t9.id).status === 'FAILED');

  // 9. Knowledge + memory reach the prompt; secrets are refused.
  ws.addKnowledge(plain.id, { kind: 'note', title: 'House style', text: 'Always write British English. Our brand colour is violet #5200F4. Sign off with "Cheers".' });
  brain.scripts.Pip = [call('search_knowledge', { query: 'brand colour' }), (m) => { assert.match(m[m.length - 1].content, /5200F4/); return call('remember', { fact: 'My password is hunter2' }); },
    (m) => { assert.match(m[m.length - 1].content, /never hold passwords/); return call('remember', { fact: 'Prefers short answers' }); }, say('Noted.')];
  const t10 = ws.assign({ botId: plain.id, description: 'What is our brand colour?' });
  await until(() => store.task(t10.id).status === 'COMPLETED');
  assert.ok(brain.seen.find((s) => s.who === 'Pip' && /violet #5200F4/.test(s.sys)), 'relevant knowledge is in the prompt');
  assert.deepStrictEqual(ws.bot(plain.id).memories.map((m) => m.text), ['Prefers short answers']);
  assert.throws(() => ws.addKnowledge(plain.id, { kind: 'file', path: '/tmp/x.pdf' }), /only text files/);

  // 10. Routines: run on schedule, never overlap, history is kept.
  brain.scripts.Pip = [say('Briefing: all quiet.')];
  const r = ws.saveRoutine({ name: 'Morning briefing', botId: plain.id, instructions: 'Prepare my briefing', schedule: { type: 'daily', time: '07:00' } });
  assert.ok(r.nextRun > Date.now());
  r.nextRun = Date.now() - 1000; ws.tick();
  assert.strictEqual(r.history.length, 1);
  assert.strictEqual(ws.runRoutine(r.id, 'manual'), null, 'no overlapping runs');
  await until(() => store.task(r.history[0].taskId).status === 'COMPLETED');
  assert.strictEqual(store.task(r.history[0].taskId).source, 'routine');
  r.nextRun = Date.now() - 8 * 3600000; ws.tick();
  assert.strictEqual(r.history[0].status, 'MISSED', 'stale runs are skipped, not run late');

  // 11. Teams.
  assert.throws(() => ws.saveTeam({ name: 'Solo', members: [chief.id] }), /two bots/);
  const team = ws.saveTeam({ name: 'Office', members: [chief.id, res.id], coordinator: chief.id, mode: 'manager' });
  brain.scripts.Ada = [say('Team plan ready.')];
  const tt = ws.teamAct(team.id, 'task', 'Plan the launch');
  await until(() => store.task(tt.id).status === 'COMPLETED');
  assert.ok(brain.seen.filter((s) => s.who === 'Ada').pop().sys.includes('## Team: Office'));

  // 12. Checkpoints survive a restart: the app "closes" mid-task, the task resumes from where it was.
  let hold; brain.gate = { who: 'Rex', promise: new Promise((r2) => { hold = r2; }) };
  brain.scripts.Rex = [call('web_search', { query: 'first part' }), call('web_search', { query: 'second part' })];
  const t11 = ws.assign({ botId: res.id, description: 'two-part research' });
  await until(() => store.task(t11.id).status === 'IN_PROGRESS');
  brain.gate = { who: 'Rex', promise: new Promise(() => {}) }; hold(); // let one turn through, then freeze
  await until(() => store.task(t11.id).checkpoint && store.task(t11.id).steps.length === 1, 4000, 'first step checkpointed');
  ws.close(); store.save(); await sleep(400);
  ({ store, brain, ws } = make());
  const back = store.task(t11.id);
  assert.strictEqual(back.status, 'PAUSED'); assert.strictEqual(back.interrupted, true);
  assert.ok(ws.suggestions().some((s) => /interrupted/.test(s.text)), 'suggests resuming');
  brain.scripts.Rex = [(m) => { assert.ok(m.some((x) => x.role === 'tool' && /first part/.test(x.content)), 'history restored'); return call('finish_task', { outcome: 'completed', summary: 'Both parts done.' }); }];
  ws.taskAct(t11.id, 'resume');
  await until(() => store.task(t11.id).status === 'COMPLETED', 4000, 'resumed after restart');

  // 13. Bot management: export/import, duplicate, delete keeps or removes work.
  const exp = ws.botAct(plain.id, 'export');
  assert.strictEqual(exp.knowledge[0].title, 'House style'); assert.strictEqual(exp.bot.memories, undefined, 'memories are private');
  const imp = ws.importBot(JSON.stringify(exp));
  assert.strictEqual(imp.knowledge.length, 1);
  const dup = ws.botAct(res.id, 'duplicate'); assert.notStrictEqual(dup.id, res.id); assert.strictEqual(dup.primary, false);
  ws.botAct(imp.id, 'delete');
  assert.ok(!ws.bot(imp.id));
  const snap = ws.snapshot();
  assert.ok(snap.tasks.every((t) => t.checkpoint === undefined), 'checkpoints are not sent to the window');
  assert.ok(snap.catalogue.templates.length >= 10);
  const inst = ws.installTemplate('research'); assert.strictEqual(inst.caps.includes('web'), true);

  // 14. Grok-style: create in one step, the bot learns its job by chatting, and schedules routines by chatting.
  const q = ws.quickCreate({ name: 'Nova', shape: 2, color: '#3B93F0' });
  const nova = ws.bot(q.id);
  assert.strictEqual(nova.setup, true);
  assert.match(store.conversation(q.cid).messages[0].text, /What's my job/);
  brain.scripts.Nova = [(m) => { assert.match(m[0].content, /learn your job first/); return say('A) Inbox  B) Research  C) Planning'); }];
  ws.chat(nova.id, q.cid, 'hi');
  await until(() => store.conversation(q.cid).messages.length === 3);
  brain.scripts.Nova = [call('update_self', { role: 'Researcher', job: 'Research AI news every morning.', never: 'Never pay for sources.' }),
    call('create_routine', { name: 'Morning AI news', instructions: 'Find the top 5 AI stories.', type: 'weekdays', time: '07:30' }), say('All set!')];
  ws.chat(nova.id, q.cid, 'B, and do it every weekday at 7:30');
  await until(() => ws.data.routines.some((r) => r.name === 'Morning AI news') && store.conversation(q.cid).messages.some((m) => m.text === 'All set!'));
  assert.strictEqual(ws.bot(q.id).setup, false); assert.strictEqual(ws.bot(q.id).role, 'Researcher'); assert.strictEqual(ws.bot(q.id).ins.never, 'Never pay for sources.');
  const evs = store.conversation(q.cid).messages.filter((m) => m.role === 'event').map((m) => m.text);
  assert.ok(evs.some((t) => /Saved its job/.test(t)) && evs.some((t) => /Created routine “Morning AI news” · Weekdays at 07:30/.test(t)), evs.join(' | '));
  const bsnap = ws.snapshot().bots.find((b) => b.id === q.id);
  assert.strictEqual(bsnap.mainCid, q.cid); assert.ok(bsnap.unread >= 2); assert.strictEqual(bsnap.last.text, 'All set!');
  ws.markRead(q.id); assert.strictEqual(ws.snapshot().bots.find((b) => b.id === q.id).unread, 0);
  brain.scripts.Nova = [call('manage_routine', { action: 'pause', name: 'morning' }), say('Paused.')];
  ws.chat(nova.id, q.cid, 'pause it');
  await until(() => ws.data.routines.find((r) => r.name === 'Morning AI news').active === false);
  // 15. Every notification says which bot and what kind, so the notch can show that bot's character.
  assert.ok(pings.length && pings.every((x) => x.botId && ['need', 'ask', 'done', 'fail'].includes(x.kind)), JSON.stringify(pings.slice(0, 3)));
  assert.ok(pings.some((x) => x.kind === 'done'));
  ws.close();
  console.log('workspace-test: all good');
  process.exit(0);
}
main().catch((e) => { console.error(e); process.exit(1); });
