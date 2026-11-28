'use strict';
// Calls with bots: who answers, bots handing over to each other, real tasks started from a call, interrupting,
// the note left in each bot's chat, and voices that differ between bots. Scripted AI, real Workspace engine.
const assert = require('assert');
const fs = require('fs'); const os = require('os'); const path = require('path'); const Module = require('module');
const realLoad = Module._load;
Module._load = function (req, ...rest) { if (req === 'electron') return { shell: { openExternal: async () => {} }, clipboard: { writeText() {}, readText: () => '' } }; return realLoad.call(this, req, ...rest); };
const { Store } = require('../src/store');
const { Agent, TOOLS, step, toolMsg } = require('../src/agent');
const { Workspace } = require('../src/workspace');
const { CallSession, voiceOf, speakable, parseReply, addressed, VOICES } = require('../src/call');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (fn, ms = 4000, what = 'condition') => { const t0 = Date.now(); while (!fn()) { if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${what}`); await sleep(10); } };

// ---------------------------------------------------------------- pure parts
assert.deepStrictEqual(parseReply('{"say":"Sure — on it.","next":"user","task":"Write the plan"}'), { say: 'Sure — on it.', next: 'user', task: 'Write the plan' });
assert.strictEqual(parseReply('Just words, no JSON.').say, 'Just words, no JSON.');
assert.strictEqual(speakable('**Hi** there 👋\n- one\n- two [cheerful] see https://x.com'), 'Hi there one two see the link');
const bs = [{ name: 'Researcher' }, { name: 'Content Writer' }, { name: 'Nova' }];
assert.strictEqual(addressed('ok researcher can you look', bs).name, 'Researcher');
assert.strictEqual(addressed('Content, write it up', bs).name, 'Content Writer', 'first word of a name is enough');
assert.strictEqual(addressed('what do you all think', bs), null);
assert.notDeepStrictEqual(voiceOf({}, 0), voiceOf({}, 1), 'bots get different default voices');
assert.strictEqual(voiceOf({ voice: { voice: 'diana', tone: 'Calm' } }, 0).tone, 'calm');
assert.strictEqual(voiceOf({ voice: { voice: 'nobody' } }, 2).voice, VOICES[2], 'unknown voices fall back');

// ---------------------------------------------------------------- a real call
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nbcall-'));
  const store = new Store(dir);
  const script = {}; const seen = [];
  const brain = {
    handleLocal: () => null, async test() { return 'ok'; }, async search() { return { text: '', sources: [] }; },
    async agentChat(m, tools, _w, opts) {
      await sleep(5); const who = (/^You are ([^,—\n]+)/.exec(m[0].content) || [])[1]?.trim();
      seen.push({ who, opts, sys: m[0].content, last: m[m.length - 1].content });
      const q = script[who] || []; const n = q.shift();
      if (!n) return { content: tools ? 'Done.' : JSON.stringify({ say: 'Okay.', next: 'user', task: '' }), tool_calls: [] };
      return { content: typeof n === 'string' ? n : JSON.stringify(n), tool_calls: [] };
    },
  };
  const ws = new Workspace({ dir, store, brain, config: { get: () => '', set() {} }, AgentClass: Agent, engine: { step, toolMsg, TOOLS },
    agentDeps: { printPdf: async () => Buffer.from(''), launch: async () => ({ ok: true }), foreground: null, timer() {}, clicker: async () => 'ok', onActivity() {}, screenshot: null, sound() {}, hasKey: () => true }, notify() {} });
  ws.setProfile({ name: 'Thys', onboarded: true });
  const res = ws.installTemplate('research'); const writer = ws.installTemplate('writer');
  const events = []; const spoken = [];
  const callS = new CallSession({ ws, brain, botIds: [res.id], emit: (e) => events.push(e), speak: async (b, v, t) => { spoken.push({ who: b.name, ...v, t }); await sleep(5); } });

  // 1. One bot: it answers you, briefly, in its voice and tone; the call prompt is quick and asks for JSON.
  script.Researcher = [{ say: 'Hi Thys! What should I look into?', next: 'user', task: '' }];
  await callS.hear('Hey, are you there?');
  assert.strictEqual(spoken.length, 1); assert.strictEqual(spoken[0].who, 'Researcher'); assert.ok(spoken[0].voice && spoken[0].tone);
  const p = seen.find((s) => s.who === 'Researcher');
  assert.ok(p.opts.json && p.opts.effort === 'low', 'calls ask for a fast JSON reply');
  assert.match(p.sys, /live voice call/); assert.match(p.sys, /Never pretend you did something/);
  assert.ok(events.some((e) => e.type === 'line' && e.line.who === 'user') && events.some((e) => e.type === 'line' && e.line.who === 'bot'));

  // 2. Add a second bot: the first hands over by name, the other answers, then the floor comes back to you.
  assert.ok(callS.add(writer.id)); assert.ok(!callS.add(writer.id), 'no doubles');
  assert.notStrictEqual(callS.voice(ws.bot(res.id)).voice, callS.voice(ws.bot(writer.id)).voice, 'two bots, two voices');
  script.Researcher = [{ say: 'I found three good angles. Content Writer, can you turn them into a post?', next: 'Content Writer', task: '' }];
  script['Content Writer'] = [{ say: 'Happy to — I will draft it now.', next: 'user', task: 'Draft a short post from the three angles the Researcher found' }];
  spoken.length = 0;
  await callS.hear('Researcher, what are the angles for the launch?');
  assert.deepStrictEqual(spoken.map((s) => s.who), ['Researcher', 'Content Writer'], 'bots talk to each other');
  const wPrompt = seen.filter((s) => s.who === 'Content Writer').pop();
  assert.match(wPrompt.last, /\[Researcher said\]/, 'a bot hears what the other bot said');
  // ...and the writer's task is real: it lands in its chat as a request, and the bot works on it there.
  await until(() => ws.mainConv(writer.id).messages.some((m) => m.role === 'user' && /Draft a short post/.test(m.text)), 3000, 'writer task');
  assert.ok(events.some((e) => e.type === 'line' && e.line.who === 'task' && /Content Writer started/.test(e.line.text)));

  // 3. Bots can't ping-pong forever.
  script.Researcher = Array.from({ length: 6 }, () => ({ say: 'Over to you, Content Writer.', next: 'Content Writer' }));
  script['Content Writer'] = Array.from({ length: 6 }, () => ({ say: 'Back to you, Researcher.', next: 'Researcher' }));
  spoken.length = 0;
  await callS.hear('Researcher, thoughts?');
  assert.strictEqual(spoken.length, 4, 'at most four bot lines per thing you say');

  // 4. Talking over them stops what's queued.
  script.Researcher = [{ say: 'This answer is slow.', next: 'user' }];
  const slow = callS.hear('Researcher, tell me a long story');
  callS.interrupt();
  await slow;
  assert.ok(!spoken.some((s) => s.t === 'This answer is slow.'), 'an interrupted reply is never spoken');

  // 5. Leaving: a note with the length and a transcript goes into each bot's chat.
  const r = callS.end();
  assert.ok(r.secs >= 0 && /You: Hey, are you there\?/.test(r.transcript));
  for (const b of [res, writer]) {
    const ev = ws.mainConv(b.id).messages.filter((m) => m.role === 'event' && m.kind === 'call').pop();
    assert.ok(ev && /Call/.test(ev.text) && ev.transcript.includes('Researcher:'), `${b.name} has the call note`);
  }
  await callS.hear('anyone?'); // after the end: ignored
  ws.close && ws.close();
  console.log('call-test: all good');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
