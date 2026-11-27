'use strict';
// MCP (Model Context Protocol) client for Nebula Workspace: connect RovBots to real tools.
//  - stdio servers: Nebula starts the command (npx, uvx, node, python…) and talks JSON-RPC over stdin/stdout.
//  - remote servers: Streamable HTTP (POST JSON-RPC, answers as JSON or SSE, Mcp-Session-Id), with an SSE fallback
//    for older servers.
// Paste the "mcpServers" config block that mcp.so (and Claude/Cursor docs) show, or fill in the form.
// Secrets (env values, headers) are encrypted by the caller's encrypt/decrypt and never shown back in full.

const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const { id } = require('./store');

const PROTOCOL = '2025-06-18';
const CLIENT = { name: 'Nebula Workspace', version: '1.0' };
const TIMEOUT = 60000;

const slug = (s, n = 24) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, n) || 'server';
/** The name the AI sees: mcp__<server>__<tool>, max 64 chars, only [a-zA-Z0-9_-]. */
function toolKey(server, tool) {
  const t = String(tool).replace(/[^a-zA-Z0-9_-]/g, '_');
  return `mcp__${slug(server.name, 18)}_${server.id.slice(-4)}__${t}`.slice(0, 64);
}

/** Default risk from the server's own hints; the user can override per tool. */
function riskOf(tool) {
  const a = tool.annotations || {};
  if (a.destructiveHint === true) return 'high';
  if (a.readOnlyHint === true) return 'none';
  const n = `${tool.name} ${tool.description || ''}`.toLowerCase();
  if (/(^|[^a-z])(delete|remove|drop|destroy|purge|wipe|erase|truncate|force[-_ ]?push|revoke|uninstall)([^a-z]|$)/.test(n)) return 'high';
  if (/^(get|list|read|search|find|fetch|query|describe|show|view|lookup|browser_snapshot|browser_take_screenshot)|_(get|list|read|search)\b/.test(tool.name.toLowerCase())) return 'none';
  return 'medium';
}

/** Parse a pasted config: {"mcpServers":{name:{command,args,env}|{url,headers}}}, a single server object, or a URL. */
function parseConfig(text) {
  const t = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(t)) return [{ name: new URL(t).hostname.replace(/^(www|mcp)\./, '').split('.')[0], type: 'http', url: t }];
  let o;
  try { o = JSON.parse(t); } catch {
    try { o = JSON.parse(`{${t.replace(/,\s*$/, '')}}`); } catch { throw new Error('That isn\'t a config block. Copy the whole JSON from the server\'s page (it starts with { and "mcpServers").'); }
  }
  const map = o.mcpServers || o.servers || o.mcp?.servers || (o.command || o.url ? { [o.name || 'server']: o } : o);
  const out = [];
  for (const [name, c] of Object.entries(map || {})) {
    if (!c || typeof c !== 'object') continue;
    if (c.url || c.serverUrl) out.push({ name, type: 'http', url: c.url || c.serverUrl, headers: c.headers || {} });
    else if (c.command) out.push({ name, type: 'stdio', command: String(c.command), args: (c.args || []).map(String), env: c.env || {} });
  }
  if (!out.length) throw new Error('No servers found in that config.');
  return out;
}

// ------------------------------------------------------------------ transports

class StdioTransport extends EventEmitter {
  constructor(cfg) { super(); this.cfg = cfg; this.buf = ''; this.err = ''; }
  start() {
    return new Promise((resolve, reject) => {
      const env = { ...process.env, ...this.cfg.env };
      delete env.ELECTRON_RUN_AS_NODE;
      let p;
      try {
        const win = process.platform === 'win32';
        // Windows needs a shell to find npx.cmd/uvx.exe; quote anything with spaces or shell characters.
        const q = (x) => (win && /[\s&()^|<>"]/.test(x) ? `"${String(x).replace(/"/g, '""')}"` : x);
        p = spawn(q(this.cfg.command), (this.cfg.args || []).map(q), { env, shell: win, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      } catch (e) { reject(e); return; }
      this.proc = p;
      let settled = false;
      p.on('error', (e) => {
        const msg = e.code === 'ENOENT' ? `“${this.cfg.command}” isn't installed on this PC.${/^(npx|npm|node)$/.test(this.cfg.command) ? ' Install Node.js from nodejs.org, then reconnect.' : /^(uvx|uv)$/.test(this.cfg.command) ? ' Install uv (docs.astral.sh/uv), then reconnect.' : ''}` : e.message;
        if (!settled) { settled = true; reject(new Error(msg)); } else this.emit('close', msg);
      });
      p.on('spawn', () => { if (!settled) { settled = true; resolve(); } });
      p.stdout.setEncoding('utf8');
      p.stdout.on('data', (d) => {
        this.buf += d;
        let i;
        while ((i = this.buf.indexOf('\n')) >= 0) {
          const line = this.buf.slice(0, i).trim(); this.buf = this.buf.slice(i + 1);
          if (!line) continue;
          try { this.emit('message', JSON.parse(line)); } catch { /* servers sometimes log to stdout; ignore */ }
        }
      });
      p.stderr.setEncoding('utf8');
      p.stderr.on('data', (d) => { this.err = (this.err + d).slice(-4000); });
      p.on('exit', (code) => {
        const tail = lastLine(this.err);
        const missing = /not recognized|command not found|not found: (npx|uvx|node)|ENOENT/i.test(tail) && /^(npx|npm|node|uvx|uv|python3?)$/.test(this.cfg.command);
        this.emit('close', missing ? `“${this.cfg.command}” isn't installed on this PC. ${/^(uvx|uv)$/.test(this.cfg.command) ? 'Install uv (docs.astral.sh/uv)' : /^python/.test(this.cfg.command) ? 'Install Python' : 'Install Node.js from nodejs.org'}, then press Reconnect.` : code ? `It stopped (code ${code}). ${tail}` : 'It stopped.');
      });
    });
  }
  send(msg) { if (!this.proc || !this.proc.stdin.writable) throw new Error('Not running'); this.proc.stdin.write(`${JSON.stringify(msg)}\n`); }
  close() { try { this.proc && this.proc.kill(); } catch { /* */ } this.proc = null; }
}
const lastLine = (s) => String(s || '').trim().split('\n').filter(Boolean).slice(-2).join(' ').slice(0, 300);

class HttpTransport extends EventEmitter {
  constructor(cfg, fetchImpl) { super(); this.cfg = cfg; this.fetch = fetchImpl || fetch; this.session = null; this.legacy = null; }
  async start() { /* stateless until initialize */ }
  headers() { return { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': PROTOCOL, ...(this.session ? { 'Mcp-Session-Id': this.session } : {}), ...(this.cfg.headers || {}) }; }
  async send(msg) {
    if (this.legacy) return this.legacySend(msg);
    const r = await this.fetch(this.cfg.url, { method: 'POST', headers: this.headers(), body: JSON.stringify(msg) });
    const sid = r.headers.get('mcp-session-id'); if (sid) this.session = sid;
    if ((r.status === 404 || r.status === 405) && msg.method === 'initialize' && !this.triedLegacy) { this.triedLegacy = true; await this.openLegacy(); return this.legacySend(msg); }
    if (r.status === 401 || r.status === 403) throw new Error(`The server said ${r.status} — check the key or sign-in in this server's settings.`);
    if (!r.ok && r.status !== 202) throw new Error(`The server answered ${r.status}. ${(await r.text().catch(() => '')).slice(0, 200)}`);
    const type = r.headers.get('content-type') || '';
    if (r.status === 202 || !msg.id) return;
    if (type.includes('text/event-stream')) { await readSse(r.body, (ev) => { if (ev.data) try { this.emit('message', JSON.parse(ev.data)); } catch { /* */ } }); return; }
    const text = await r.text(); if (!text.trim()) return;
    const j = JSON.parse(text); for (const m of Array.isArray(j) ? j : [j]) this.emit('message', m);
  }
  /** Older "HTTP+SSE" servers: GET an event stream, which tells us where to POST. */
  async openLegacy() {
    const ctl = new AbortController(); this.abort = ctl;
    const r = await this.fetch(this.cfg.url, { headers: { Accept: 'text/event-stream', ...(this.cfg.headers || {}) }, signal: ctl.signal });
    if (!r.ok) throw new Error(`The server answered ${r.status}.`);
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('The server never said where to send requests.')), 15000);
      readSse(r.body, (ev) => {
        if (ev.event === 'endpoint') { this.legacy = new URL(ev.data, this.cfg.url).toString(); clearTimeout(t); resolve(); }
        else if (ev.data) try { this.emit('message', JSON.parse(ev.data)); } catch { /* */ }
      }).then(() => this.emit('close', 'The connection closed.'), () => {});
    });
  }
  async legacySend(msg) {
    const r = await this.fetch(this.legacy, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(this.cfg.headers || {}) }, body: JSON.stringify(msg) });
    if (!r.ok && r.status !== 202) throw new Error(`The server answered ${r.status}.`);
  }
  close() {
    try { this.abort && this.abort.abort(); } catch { /* */ }
    if (this.session && !this.legacy) this.fetch(this.cfg.url, { method: 'DELETE', headers: this.headers() }).catch(() => {});
    this.session = null;
  }
}

async function readSse(body, onEvent) {
  const dec = new TextDecoder(); let buf = ''; let ev = { event: 'message', data: '' };
  for await (const chunk of body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.search(/\r?\n/)) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(buf[i] === '\r' ? i + 2 : i + 1);
      if (!line) { if (ev.data || ev.event !== 'message') onEvent({ ...ev, data: ev.data.replace(/\n$/, '') }); ev = { event: 'message', data: '' }; continue; }
      if (line.startsWith(':')) continue;
      const k = line.indexOf(':'); const f = k < 0 ? line : line.slice(0, k); const v = k < 0 ? '' : line.slice(k + 1).replace(/^ /, '');
      if (f === 'data') ev.data += `${v}\n`; else if (f === 'event') ev.event = v;
    }
  }
}

// ------------------------------------------------------------------ one connection

class McpConnection extends EventEmitter {
  constructor(cfg, fetchImpl) { super(); this.cfg = cfg; this.fetch = fetchImpl; this.next = 1; this.pending = new Map(); this.status = 'off'; this.error = ''; this.tools = []; this.info = null; }
  async connect() {
    this.close();
    this.status = 'connecting'; this.error = ''; this.emit('status');
    const t = this.cfg.type === 'http' ? new HttpTransport(this.cfg, this.fetch) : new StdioTransport(this.cfg);
    this.t = t;
    t.on('message', (m) => this.onMessage(m));
    t.on('close', (why) => { if (this.t !== t) return; this.fail(new Error(why || 'Disconnected')); });
    try {
      await t.start();
      const init = await this.request('initialize', { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: CLIENT }, 45000);
      this.info = { name: init.serverInfo?.name || '', version: init.serverInfo?.version || '', protocol: init.protocolVersion, instructions: String(init.instructions || '').slice(0, 1500) };
      await this.notify('notifications/initialized');
      await this.refreshTools();
      this.status = 'connected'; this.emit('status');
    } catch (e) { this.fail(e); throw e; }
  }
  async refreshTools() {
    const all = []; let cursor;
    for (let i = 0; i < 20; i++) { const r = await this.request('tools/list', cursor ? { cursor } : {}); all.push(...(r.tools || [])); cursor = r.nextCursor; if (!cursor) break; }
    this.tools = all.map((x) => ({ name: x.name, title: x.title || x.annotations?.title || '', description: String(x.description || '').slice(0, 1200), inputSchema: x.inputSchema || { type: 'object', properties: {} }, annotations: x.annotations || {} }));
    this.emit('status');
    return this.tools;
  }
  async call(name, args) {
    const r = await this.request('tools/call', { name, arguments: args || {} }, 120000);
    const parts = [];
    for (const c of r.content || []) {
      if (c.type === 'text') parts.push(c.text);
      else if (c.type === 'resource' && c.resource) parts.push(c.resource.text || `[resource ${c.resource.uri}]`);
      else if (c.type === 'resource_link') parts.push(`[${c.name || 'link'}: ${c.uri}]`);
      else if (c.type === 'image') parts.push(`[image ${c.mimeType || ''} — ${Math.round((c.data || '').length * 0.75 / 1024)} KB]`);
      else if (c.type === 'audio') parts.push('[audio]');
    }
    if (!parts.length && r.structuredContent) parts.push(JSON.stringify(r.structuredContent));
    return { ok: !r.isError, text: parts.join('\n').slice(0, 12000) || (r.isError ? 'The tool reported an error.' : 'Done (no output).') };
  }
  request(method, params, ms = TIMEOUT) {
    const rid = this.next++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(rid); reject(new Error(`No answer from the server after ${Math.round(ms / 1000)} s.`)); }, ms);
      this.pending.set(rid, { resolve, reject, timer });
      Promise.resolve().then(() => this.t.send({ jsonrpc: '2.0', id: rid, method, params })).catch((e) => { clearTimeout(timer); this.pending.delete(rid); reject(e); });
    });
  }
  notify(method, params) { return Promise.resolve(this.t.send({ jsonrpc: '2.0', method, ...(params ? { params } : {}) })); }
  onMessage(m) {
    if (m.id != null && this.pending.has(m.id) && (m.result !== undefined || m.error)) {
      const p = this.pending.get(m.id); this.pending.delete(m.id); clearTimeout(p.timer);
      if (m.error) p.reject(new Error(m.error.message || 'The server returned an error.')); else p.resolve(m.result || {});
      return;
    }
    if (m.method === 'notifications/tools/list_changed') { this.refreshTools().catch(() => {}); return; }
    if (m.method === 'ping' && m.id != null) { Promise.resolve(this.t.send({ jsonrpc: '2.0', id: m.id, result: {} })).catch(() => {}); return; }
    if (m.id != null && m.method) { // requests we don't support (sampling, elicitation, roots)
      Promise.resolve(this.t.send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Nebula does not support this yet.' } })).catch(() => {});
    }
  }
  fail(e) {
    this.status = 'error'; this.error = e.message || String(e);
    for (const [, p] of this.pending) { clearTimeout(p.timer); p.reject(e); }
    this.pending.clear();
    if (this.t) { const t = this.t; this.t = null; t.close(); }
    this.emit('status');
  }
  close() {
    if (this.t) { const t = this.t; this.t = null; t.close(); }
    for (const [, p] of this.pending) { clearTimeout(p.timer); p.reject(new Error('Disconnected')); }
    this.pending.clear(); this.status = 'off'; this.emit('status');
  }
}

// ------------------------------------------------------------------ the hub (all servers)

const MASK = '••••••••';

class McpHub extends EventEmitter {
  /** @param {{list:()=>Array, save:()=>void, encrypt:(s)=>string, decrypt:(s)=>string, fetch?:Function}} o */
  constructor(o) { super(); Object.assign(this, o); this.conns = new Map(); }
  servers() { return this.list(); }
  server(sid) { return this.list().find((s) => s.id === sid); }

  add(cfg) {
    const s = { id: id(), name: String(cfg.name || 'server').slice(0, 40), type: cfg.type === 'http' ? 'http' : 'stdio', command: cfg.command || '', args: cfg.args || [], url: cfg.url || '',
      env: {}, headers: {}, enabled: true, tools: [], toolCfg: {}, createdAt: Date.now(), info: null };
    if (s.type === 'http' && !/^https?:\/\//.test(s.url)) throw new Error('The server address must start with https://');
    if (s.type === 'stdio' && !s.command) throw new Error('Say which command starts the server (e.g. npx).');
    this.setSecrets(s, cfg.env || {}, cfg.headers || {});
    this.list().push(s); this.save();
    return s;
  }
  update(sid, patch) {
    const s = this.server(sid); if (!s) throw new Error('No such server');
    for (const k of ['name', 'command', 'url']) if (patch[k] != null) s[k] = String(patch[k]).slice(0, k === 'url' ? 500 : 200);
    if (patch.args) s.args = patch.args.map(String);
    if (patch.env || patch.headers) this.setSecrets(s, patch.env || this.secrets(s).env, patch.headers || this.secrets(s).headers);
    this.save();
    return s;
  }
  /** Keys typed as ••• keep their old value. */
  setSecrets(s, env, headers) {
    const old = this.secrets(s);
    const keep = (vals, prev) => Object.fromEntries(Object.entries(vals).filter(([k]) => k.trim()).map(([k, v]) => [k.trim(), v === MASK ? prev[k] || '' : String(v)]));
    s.envEnc = this.encrypt(JSON.stringify(keep(env, old.env)));
    s.headersEnc = this.encrypt(JSON.stringify(keep(headers, old.headers)));
    s.env = Object.fromEntries(Object.keys(keep(env, old.env)).map((k) => [k, MASK]));
    s.headers = Object.fromEntries(Object.keys(keep(headers, old.headers)).map((k) => [k, MASK]));
  }
  secrets(s) {
    const d = (x) => { try { return x ? JSON.parse(this.decrypt(x)) : {}; } catch { return {}; } };
    return { env: d(s.envEnc), headers: d(s.headersEnc) };
  }
  remove(sid) { this.disconnect(sid); const l = this.list(); const i = l.findIndex((s) => s.id === sid); if (i >= 0) l.splice(i, 1); this.save(); }

  async connect(sid) {
    const s = this.server(sid); if (!s) throw new Error('No such server');
    const sec = this.secrets(s);
    let c = this.conns.get(sid);
    if (!c) { c = new McpConnection({}, this.fetch); c.on('status', () => this.sync(sid)); this.conns.set(sid, c); }
    c.cfg = { type: s.type, command: s.command, args: s.args, env: sec.env, url: s.url, headers: sec.headers };
    await c.connect();
    s.info = c.info; s.lastOk = Date.now(); this.sync(sid);
    return s;
  }
  disconnect(sid) { const c = this.conns.get(sid); if (c) { c.close(); this.conns.delete(sid); } this.sync(sid); }
  sync(sid) {
    const s = this.server(sid); if (!s) return;
    const c = this.conns.get(sid);
    s.status = c ? c.status : 'off'; s.error = c ? c.error : '';
    if (c && c.tools.length) {
      s.tools = c.tools.map((t) => ({ ...t, key: toolKey(s, t.name), risk: riskOf(t) }));
      for (const t of s.tools) if (!s.toolCfg[t.name]) s.toolCfg[t.name] = { on: true, risk: t.risk };
    }
    this.save(); this.emit('change');
  }
  toolCfg(sid, tool, patch) { const s = this.server(sid); if (!s) return; s.toolCfg[tool] = { ...(s.toolCfg[tool] || { on: true, risk: 'medium' }), ...patch }; this.save(); this.emit('change'); }
  async connectAll() { await Promise.all(this.list().filter((s) => s.enabled).map((s) => this.connect(s.id).catch(() => {}))); }
  closeAll() { for (const sid of [...this.conns.keys()]) this.disconnect(sid); }

  /** Every enabled tool: {key, server, tool, risk, schema}. */
  catalogue() {
    const out = [];
    for (const s of this.list()) {
      if (!s.enabled) continue;
      for (const t of s.tools || []) {
        const cfg = s.toolCfg[t.name] || { on: true, risk: t.risk };
        if (cfg.on === false) continue;
        out.push({ key: t.key, server: s, tool: t, risk: cfg.risk || t.risk,
          schema: { type: 'function', function: { name: t.key, description: `[${s.name}] ${t.title ? `${t.title}: ` : ''}${t.description || t.name}`.slice(0, 1000), parameters: cleanSchema(t.inputSchema) } } });
      }
    }
    return out;
  }
  find(key) { return this.catalogue().find((x) => x.key === key); }

  async call(key, args) {
    const x = this.find(key); if (!x) throw new Error('That tool isn\'t available any more.');
    let c = this.conns.get(x.server.id);
    if (!c || c.status !== 'connected') { await this.connect(x.server.id); c = this.conns.get(x.server.id); }
    return c.call(x.tool.name, args);
  }
}

/** Groq/OpenAI function schemas must be an object schema; strip things some APIs reject. */
function cleanSchema(s) {
  const o = s && typeof s === 'object' ? JSON.parse(JSON.stringify(s)) : {};
  if (o.type !== 'object') return { type: 'object', properties: {} };
  delete o.$schema; delete o.$id;
  if (!o.properties) o.properties = {};
  return o;
}

module.exports = { McpHub, McpConnection, parseConfig, toolKey, riskOf, cleanSchema, MASK, PROTOCOL };
