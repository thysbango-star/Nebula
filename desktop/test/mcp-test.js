'use strict';
// MCP connections for Nebula Workspace: a stdio server Nebula starts, a remote Streamable-HTTP server with a key,
// per-bot permissions, approval cards by risk, encrypted secrets, and friendly errors.
const assert = require('assert');
const fs = require('fs'); const os = require('os'); const path = require('path'); const http = require('http');
const Module = require('module');
const realLoad = Module._load;
Module._load = function (req, ...r) { if (req === 'electron') return { shell: { openExternal: async () => {} }, clipboard: { writeText() {}, readText: () => '' } }; return realLoad.call(this, req, ...r); };
const { Store } = require('../src/store');
const { Agent, TOOLS, step, toolMsg } = require('../src/agent');
const { Workspace } = require('../src/workspace');
const { parseConfig, riskOf, MASK } = require('../src/mcp');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (fn, ms = 6000, what = 'condition') => { const t0 = Date.now(); while (!fn()) { if (Date.now() - t0 > ms) throw new Error(`timed out: ${what}`); await sleep(15); } };
const call = (name, args) => ({ content: '', tool_calls: [{ id: `c${Math.random().toString(36).slice(2, 8)}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = (t) => ({ content: t, tool_calls: [] });

// ---- config parsing (what mcp.so and app docs show)
{
  const a = parseConfig('{"mcpServers":{"github":{"command":"npx","args":["-y","@modelcontextprotocol/server-github"],"env":{"GITHUB_PERSONAL_ACCESS_TOKEN":"<YOUR_TOKEN>"}}}}');
  assert.deepStrictEqual(a[0], { name: 'github', type: 'stdio', command: 'npx', args: ['-y', '@modelcontextprotocol/server-github'], env: { GITHUB_PERSONAL_ACCESS_TOKEN: '<YOUR_TOKEN>' } });
  assert.strictEqual(parseConfig('"x": {"url": "https://mcp.example.com/mcp"}')[0].type, 'http', 'a fragment without outer braces still parses');
  assert.strictEqual(parseConfig('https://mcp.linear.app/mcp')[0].name, 'linear');
  assert.throws(() => parseConfig('hello'), /config block/);
  assert.strictEqual(riskOf({ name: 'delete_repo' }), 'high'); assert.strictEqual(riskOf({ name: 'list_issues' }), 'none'); assert.strictEqual(riskOf({ name: 'create_issue' }), 'medium');
  assert.strictEqual(riskOf({ name: 'x', annotations: { readOnlyHint: true } }), 'none');
}

class FakeBrain { constructor() { this.s = []; this.seen = []; } handleLocal() { return null; } async search() { return { text: '', sources: [] }; }
  async agentChat(m, tools) { this.seen.push({ tools: (tools || []).map((t) => t.function.name), sys: m[0].content, last: m[m.length - 1] }); const n = this.s.shift(); return !n ? say('Done.') : typeof n === 'function' ? n(m) : n; } }

(async () => {
  // ---- a remote Streamable-HTTP server that needs a key, answers with SSE, and uses a session id
  let sessions = 0; let lastAuth = '';
  const srv = http.createServer((req, res) => {
    let body = ''; req.on('data', (d) => { body += d; }); req.on('end', () => {
      lastAuth = req.headers.authorization || '';
      if (req.method === 'DELETE') { res.writeHead(200); return res.end(); }
      if (lastAuth !== 'Bearer s3cret') { res.writeHead(401); return res.end('unauthorized'); }
      const m = JSON.parse(body);
      if (m.method === 'initialize') { sessions++; res.writeHead(200, { 'Content-Type': 'application/json', 'Mcp-Session-Id': 'sess-1' }); return res.end(JSON.stringify({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: 'weather' } } })); }
      assert.strictEqual(req.headers['mcp-session-id'], 'sess-1', 'session id is sent back');
      if (!m.id) { res.writeHead(202); return res.end(); }
      const result = m.method === 'tools/list' ? { tools: [{ name: 'get_forecast', description: 'Weather forecast', inputSchema: { type: 'object', properties: { city: { type: 'string' } } } }] }
        : { content: [{ type: 'text', text: `Sunny in ${m.params.arguments.city}, 27°C` }] };
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: '2.0', id: m.id, result })}\n\n`);
    });
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${srv.address().port}/mcp`;

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nebula-mcp-'));
  const store = new Store(dir); const brain = new FakeBrain();
  const enc = (s) => `ENC:${Buffer.from(s).toString('base64')}`; const dec = (s) => Buffer.from(s.slice(4), 'base64').toString();
  const ws = new Workspace({ dir, store, brain, config: { get: () => '', set() {} }, AgentClass: Agent, engine: { step, toolMsg, TOOLS }, mcpAutoConnect: false, encrypt: enc, decrypt: dec,
    agentDeps: { printPdf: async () => Buffer.from(''), launch: async () => ({ ok: true }), foreground: null, timer() {}, clicker: async () => 'ok', onActivity() {}, screenshot: null, sound() {}, hasKey: () => true } });

  // stdio
  const notesId = await ws.mcpAct('add', { name: 'Notes', type: 'stdio', command: process.execPath, args: [path.join(__dirname, 'fixtures', 'fake-mcp.js')], env: { FIRST_NOTE: 'Call mom' } });
  const notes = ws.mcp.server(notesId);
  assert.strictEqual(notes.status, 'connected', notes.error);
  assert.deepStrictEqual(notes.tools.map((t) => `${t.name}:${t.risk}`), ['list_notes:none', 'add_note:medium', 'delete_note:high']);
  assert.ok(!JSON.stringify(ws.data.mcp).includes('Call mom'), 'env values are stored encrypted');
  assert.strictEqual(notes.env.FIRST_NOTE, MASK);
  assert.ok(!JSON.stringify(ws.snapshot().mcp).includes('ENC:'), 'encrypted blobs never reach the window');

  // remote: wrong key → clear error; right key → works
  const wid = await ws.mcpAct('add', { name: 'Weather', type: 'http', url, headers: { Authorization: 'Bearer nope' } });
  assert.match(ws.mcp.server(wid).error, /401/);
  await ws.mcpAct('update', { id: wid, headers: { Authorization: 'Bearer s3cret' } });
  assert.strictEqual(ws.mcp.server(wid).status, 'connected', ws.mcp.server(wid).error);
  assert.strictEqual(ws.mcp.server(wid).tools[0].name, 'get_forecast');
  await ws.mcpAct('update', { id: wid, headers: { Authorization: MASK } }); // saving without retyping keeps the key
  assert.strictEqual(ws.mcp.server(wid).status, 'connected');

  // A bot that may use Notes (not Weather).
  const bot = ws.saveBot({ name: 'Nia', role: 'Assistant', caps: [], autonomy: 'supervised', mcp: { [notesId]: true } });
  const keyOf = (n) => notes.tools.find((t) => t.name === n).key;
  brain.s = [call(keyOf('list_notes'), {}), (m) => { assert.match(m[m.length - 1].content, /Call mom/); return call(keyOf('add_note'), { text: 'Book dentist' }); }];
  const t1 = ws.assign({ botId: bot.id, description: 'Add a note to book the dentist' });
  await until(() => store.task(t1.id).status === 'REQUIRES_INPUT', 6000, 'approval for add_note');
  const offered = brain.seen[0].tools;
  assert.ok(offered.includes(keyOf('add_note')) && !offered.some((n) => /weather/.test(n)), 'only allowed servers are offered');
  assert.ok(brain.seen[0].sys.includes('## Connected apps you can use'));
  const card = store.task(t1.id).input.card;
  assert.strictEqual(card.title, 'Notes: add_note'); assert.match(card.content, /Book dentist/); assert.match(card.destination, /program on this PC/);
  brain.s = [(m) => { assert.match(m[m.length - 1].content, /Added \\?"Book dentist/); return call('finish_task', { outcome: 'completed', summary: 'Added it.' }); }];
  ws.answer(t1.id, ['Approve']);
  await until(() => store.task(t1.id).status === 'COMPLETED', 6000, 't1 done');
  assert.ok(store.task(t1.id).steps.some((s) => s.label === 'Notes: list_notes' && s.verified));

  // High risk: always asks, "always allow" doesn't stick.
  brain.s = [call(keyOf('delete_note'), { index: 0 })];
  const t2 = ws.assign({ botId: bot.id, description: 'delete the first note' });
  await until(() => store.task(t2.id).status === 'REQUIRES_INPUT');
  assert.strictEqual(store.task(t2.id).input.card.reversible, false);
  assert.ok(!store.task(t2.id).input.options.includes('Always allow'));
  brain.s = [(m) => { assert.match(m[m.length - 1].content, /DENIED/); return call('finish_task', { outcome: 'failed', summary: 'You said no.' }); }];
  ws.answer(t2.id, ['Deny']);
  await until(() => store.task(t2.id).status === 'FAILED');

  // Tool switched off globally → not offered.
  ws.mcpAct('tool', { id: notesId, tool: 'delete_note', patch: { on: false } });
  brain.s = [say('ok')];
  const t3 = ws.assign({ botId: bot.id, description: 'hi' });
  await until(() => store.task(t3.id).status === 'COMPLETED');
  assert.ok(!brain.seen[brain.seen.length - 1].tools.includes(keyOf('delete_note')));
  // A bot without access is blocked even if the AI names the tool.
  const other = ws.saveBot({ name: 'Oz', caps: [], autonomy: 'high' });
  brain.s = [call(keyOf('list_notes'), {}), (m) => { assert.match(m[m.length - 1].content, /isn't allowed/); return call('finish_task', { outcome: 'failed', summary: 'no access' }); }];
  const t4 = ws.assign({ botId: other.id, description: 'list notes' });
  await until(() => store.task(t4.id).status === 'FAILED');

  // A crashed server reconnects on the next call.
  ws.mcp.conns.get(notesId).t.proc.kill();
  await until(() => ws.mcp.server(notesId).status === 'error', 4000, 'crash noticed');
  const r = await ws.mcp.call(keyOf('list_notes'), {});
  assert.ok(r.ok && /Call mom/.test(r.text), 'reconnected and answered');

  // Missing program → plain-English error.
  const bad = await ws.mcpAct('add', { name: 'Nope', type: 'stdio', command: 'definitely-not-a-real-command-xyz' });
  assert.match(ws.mcp.server(bad).error, /isn't installed|not found|ENOENT|stopped/i);

  ws.mcpAct('remove', { id: wid });
  assert.ok(!ws.data.mcp.some((s) => s.id === wid));
  ws.close(); srv.close();
  console.log('mcp-test: all good');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
