'use strict';
// Nebula for Windows — the task engine. Same rules as the phone:
// the AI plans, uses tools, and a task only shows COMPLETED when a tool verified it.
// States: IN_PROGRESS · REQUIRES_INPUT · COMPLETED · FAILED · CANCELLED.

const fs = require('fs');
const path = require('path');
const { shell, clipboard } = require('electron');
const docs = require('./docs');

const MAX_TURNS = 30;

const fn = (name, description, properties, required = []) => ({ type: 'function', function: { name, description, parameters: { type: 'object', properties, required } } });
const S = (d) => ({ type: 'string', description: d });
const I = (d) => ({ type: 'integer', description: d });
const B = (d) => ({ type: 'boolean', description: d });
const ARR = (d) => ({ type: 'array', items: { type: 'string' }, description: d });

const TOOLS = [
  fn('plan', 'Show the user your step-by-step plan for a multi-step task. Call first for tasks with 2+ steps.', { steps: ARR('Short step titles') }, ['steps']),
  fn('web_search', 'Search the web for current facts (news, weather, prices, people, places, sports).', { query: S('Search query') }, ['query']),
  fn('create_document', `Create a real file (docx, xlsx, pptx, pdf, csv, txt, md, html) from a spec. ${docs.SCHEMA_HINT}`, { spec: { type: 'object', description: 'The document spec' } }, ['spec']),
  fn('get_document', 'Get the full spec of a file Nebula made, before editing it.', { file_id: S('File id') }, ['file_id']),
  fn('update_document', 'Replace a file\'s content with an edited spec (same schema as create_document).', { file_id: S('File id'), spec: { type: 'object', description: 'Complete edited spec' } }, ['file_id', 'spec']),
  fn('open_app', 'Open an installed Windows app by name. The name only (e.g. "Spotify"), never the rest of the request.', { name: S('Just the app name') }, ['name']),
  fn('open_url', 'Open a website or app link (https://…, spotify:search:QUERY, ms-settings:…).', { url: S('URL or app link') }, ['url']),
  fn('play_music', 'Find music in Spotify (or YouTube Music in the browser if Spotify isn\'t installed). Pick a real song yourself for "a song of your choice".', { query: S('Song, artist, playlist or mood') }, ['query']),
  fn('set_timer', 'Start a countdown timer shown in the island.', { seconds: I('Duration in seconds'), label: S('Label') }, ['seconds']),
  fn('autoclicker', 'R3cket auto-clicker: clicks where the mouse pointer is. start | stop | faster | slower.', { action: S('start|stop|faster|slower'), interval_ms: I('Optional ms between clicks, 20-5000') }, ['action']),
  fn('copy_to_clipboard', 'Put text on the clipboard so the user can paste it.', { text: S('Text') }, ['text']),
  fn('ask_user', 'Ask the user a question with tappable options when you need information or a choice. Pauses the task.', { question: S('Question'), options: ARR('2-6 options'), multiple: B('Allow several choices') }, ['question']),
  fn('finish_task', 'End the task and report the real result. Only say "completed" if your tools confirmed success.', {
    outcome: S('completed|failed|cancelled'), summary: S('What actually happened, 1-2 sentences (read aloud)'),
    details: S('Optional longer result shown in the chat but not read aloud'),
  }, ['outcome', 'summary']),
];
const ACTION = new Set(['plan', 'create_document', 'update_document', 'open_app', 'open_url', 'play_music', 'set_timer', 'autoclicker', 'copy_to_clipboard', 'ask_user']);

const KIND = { create_document: 'document', update_document: 'document', open_app: 'app', open_url: 'web', play_music: 'music', set_timer: 'timer', autoclicker: 'game', web_search: 'research' };

class Agent {
  /**
   * @param {{brain, store, config, getKey, printPdf, launch, foreground, timer, clicker, onActivity, sound}} deps
   */
  constructor(deps) {
    Object.assign(this, deps);
    this.runs = new Map();       // conversationId → { cancelled }
    this.paused = new Map();     // taskId → saved run (waiting for the user)
    this.activity = {};          // conversationId → status line
  }

  setActivity(cid, text) {
    if (text) this.activity[cid] = text; else delete this.activity[cid];
    this.onActivity({ ...this.activity });
  }

  // ------------------------------------------------------------ public

  /** New request. Resolves when Nebula has answered or needs input: {text, taskId, status, needsInput}. */
  async submit(cid, text) {
    const conv = this.store.conversation(cid) || this.store.newConversation();
    cid = conv.id;
    this.store.addMessage(cid, { role: 'user', text });
    // A waiting task in this chat? Treat the reply as its answer.
    const waiting = this.store.data.tasks.find((t) => t.conversationId === cid && t.status === 'REQUIRES_INPUT' && t.input);
    if (waiting) {
      const choice = matchAnswer(waiting.input, text);
      if (choice) return this.answer(waiting.id, choice, false);
    }
    this.stop(cid, true);
    const run = { cid, messages: this.buildMessages(cid), taskId: null, sources: [], fileIds: [], turns: 0, cancelled: false, details: null };
    this.runs.set(cid, run);
    try {
      const local = this.brain.handleLocal(text);
      if (local) return await this.localReply(run, text, local);
      return await this.loop(run);
    } catch (e) {
      return this.fail(run, e);
    } finally {
      if (this.runs.get(cid) === run) this.runs.delete(cid);
    }
  }

  async answer(taskId, choices, echo = true) {
    const t = this.store.task(taskId);
    const run = this.paused.get(taskId);
    if (!t || !t.input || !run) return { text: 'That task is gone.', taskId, status: t?.status, needsInput: false };
    const input = t.input;
    if (echo) this.store.addMessage(t.conversationId, { role: 'user', text: choices.join(', '), taskId });
    this.paused.delete(taskId);
    this.store.updateTask(taskId, (x) => { x.status = 'IN_PROGRESS'; x.input = null; x.steps.forEach((s) => { if (s.status === 'WAITING') { s.status = 'DONE'; s.detail = `You chose: ${choices.join(', ')}`; s.verified = true; } }); });
    run.cancelled = false;
    this.runs.set(run.cid, run);
    try {
      run.messages.push(toolMsg(run.pendingCallId, JSON.stringify({ answer: choices.join(', ') })));
      return await this.loop(run);
    } catch (e) { return this.fail(run, e); } finally { this.runs.delete(run.cid); void input; }
  }

  cancel(taskId) {
    const t = this.store.task(taskId); if (!t) return;
    const run = this.runs.get(t.conversationId); if (run) run.cancelled = true;
    this.paused.delete(taskId);
    this.store.updateTask(taskId, (x) => { x.status = 'CANCELLED'; x.input = null; x.summary = x.summary || 'Cancelled.'; });
    this.setActivity(t.conversationId, null);
  }

  stop(cid, quiet = false) {
    const run = this.runs.get(cid); if (run) run.cancelled = true;
    if (!quiet) for (const t of this.store.data.tasks) if (t.conversationId === cid && t.status === 'IN_PROGRESS') this.store.updateTask(t.id, (x) => { x.status = 'CANCELLED'; x.summary = 'Stopped by you.'; });
    this.setActivity(cid, null);
  }

  // ------------------------------------------------------------ the loop

  async loop(run) {
    while (run.turns < MAX_TURNS) {
      if (run.cancelled) return { text: 'Stopped.', taskId: run.taskId, status: 'CANCELLED', needsInput: false };
      run.turns++;
      this.setActivity(run.cid, run.turns === 1 ? 'Thinking…' : 'Working on it…');
      const msg = await this.brain.agentChat(run.messages, TOOLS, (w) => this.setActivity(run.cid, w));
      if (run.cancelled) return { text: 'Stopped.', taskId: run.taskId, status: 'CANCELLED', needsInput: false };
      for (const s of msg.sources || []) if (!run.sources.some((x) => x.url === s.url)) run.sources.push(s);
      const calls = msg.tool_calls || [];
      if (!calls.length) return this.finalize(run, null, clean(msg.content || ''));
      run.messages.push({ role: 'assistant', content: msg.content || '', tool_calls: calls });
      for (let i = 0; i < calls.length; i++) {
        const call = calls[i];
        const name = call.function?.name;
        let args = {}; try { args = JSON.parse(call.function?.arguments || '{}'); } catch { /* */ }
        if (ACTION.has(name) && !run.taskId) this.createTask(run, name);
        this.setActivity(run.cid, label(name, args));
        const out = await this.exec(name, args, run).catch((e) => ({ content: JSON.stringify({ ok: false, result: `Tool error: ${e.message}` }), step: step(`${name} failed`, false, false, e.message) }));
        this.record(run, out);
        if (out.finish) {
          run.details = out.details || null;
          run.messages.push(toolMsg(call.id, out.content));
          for (const c of calls.slice(i + 1)) run.messages.push(toolMsg(c.id, 'Not run: task finished.'));
          return this.finalize(run, out.finish, null);
        }
        if (out.pause) {
          run.pendingCallId = call.id;
          for (const c of calls.slice(i + 1)) run.messages.push(toolMsg(c.id, 'Not run yet.'));
          return this.pause(run, out.pause);
        }
        run.messages.push(toolMsg(call.id, out.content));
      }
    }
    return this.finalize(run, ['failed', `I stopped after ${MAX_TURNS} steps without finishing.`], null);
  }

  async exec(name, a, run) {
    const ok = (good, verified, result, extra = {}) => JSON.stringify({ ok: good, verified: good && verified, result, ...extra });
    switch (name) {
      case 'plan': {
        const steps = (a.steps || []).map(String).slice(0, 10);
        if (run.taskId) this.store.updateTask(run.taskId, (t) => { t.plan = steps; });
        return { content: ok(true, true, 'Plan shown to the user.') };
      }
      case 'web_search': {
        const r = await this.brain.search(String(a.query || ''));
        for (const s of r.sources) if (!run.sources.some((x) => x.url === s.url)) run.sources.push(s);
        return { content: JSON.stringify({ ok: true, result: r.text, sources: r.sources.slice(0, 6) }), step: run.taskId ? step(`Searched “${String(a.query).slice(0, 40)}”`, true, true, `${r.sources.length} sources`) : null };
      }
      case 'create_document':
      case 'update_document': {
        let spec;
        try { spec = docs.parse(a.spec || {}, null, this.config.get('currency') || defaultCurrency()); } catch (e) { return { content: ok(false, false, `Bad spec: ${e.message}`), step: step('Create the file', false, false, 'The document spec was invalid') }; }
        let rec = name === 'update_document' ? this.store.file(a.file_id) : null;
        if (name === 'update_document' && !rec) return { content: ok(false, false, 'No file with that id.'), step: step('Update the file', false, false, 'File not found') };
        if (rec && rec.ext !== spec.type) spec.type = rec.ext;
        const buf = await docs.build(spec, this.printPdf);
        const file = rec ? rec.path : this.store.freePath(spec.title, spec.type);
        fs.writeFileSync(file, buf);
        fs.writeFileSync(file + '.nebula.json', JSON.stringify(docs.toJson(spec)));
        const size = fs.statSync(file).size;
        const verified = size > 0;
        if (rec) this.store.updateFile(rec.id, { size, title: spec.title, summary: docs.describe(spec) });
        else rec = this.store.addFile({ title: spec.title, fileName: path.basename(file), path: file, ext: spec.type, size, summary: docs.describe(spec), taskId: run.taskId });
        if (!run.fileIds.includes(rec.id)) run.fileIds.push(rec.id);
        const what = `${docs.TYPES[spec.type]} “${rec.fileName}” (${docs.describe(spec)})`;
        return { content: ok(true, verified, `${name === 'create_document' ? 'Created' : 'Updated'} ${what}. File id ${rec.id}.`), step: step(`${name === 'create_document' ? 'Create' : 'Update'} ${rec.fileName}`, true, verified, `${Math.round(size / 1024)} KB saved in Files`), fileIds: [rec.id] };
      }
      case 'get_document': {
        const rec = this.store.file(a.file_id);
        if (!rec) return { content: ok(false, false, 'No file with that id.') };
        let spec = '{}'; try { spec = fs.readFileSync(rec.path + '.nebula.json', 'utf8'); } catch { /* */ }
        return { content: JSON.stringify({ ok: true, file: rec.fileName, spec: JSON.parse(spec) }).slice(0, 14000) };
      }
      case 'open_app': {
        const app = cleanAppName(a.name);
        const r = await this.launch(app);
        let verified = false;
        if (r.ok && this.foreground) {
          for (let i = 0; i < 8 && !verified; i++) {
            await sleep(600);
            const fg = await this.foreground().catch(() => ({ exe: '' }));
            verified = !!fg.exe && (fg.exe.includes(app.toLowerCase().replace(/\s+/g, '')) || app.toLowerCase().replace(/\s+/g, '').includes(fg.exe));
          }
        }
        return { content: ok(r.ok, verified, r.ok ? (verified ? `${app} is open.` : `Asked Windows to open ${app}.`) : `I couldn't open ${app}.`), step: step(`Open ${app}`, r.ok, verified, r.ok ? (verified ? 'In front now' : 'Launched') : 'Not found') };
      }
      case 'open_url': {
        const url = String(a.url || '').trim();
        const u = /^[a-z][a-z0-9+.-]*:/i.test(url) ? url : `https://${url}`;
        try { await shell.openExternal(u); return { content: ok(true, true, `Opened ${u}.`), step: step(`Open ${u.slice(0, 50)}`, true, true, 'Opened') }; } catch { return { content: ok(false, false, `Nothing could open ${u}.`), step: step(`Open ${u.slice(0, 50)}`, false, false, 'Failed') }; }
      }
      case 'play_music': {
        const q = String(a.query || '').trim();
        let opened = false;
        try { await shell.openExternal(`spotify:search:${encodeURIComponent(q)}`); opened = true; } catch { /* no Spotify */ }
        if (!opened) { await shell.openExternal(`https://music.youtube.com/search?q=${encodeURIComponent(q)}`); }
        return { content: ok(true, true, opened ? `Spotify is showing results for “${q}”. Tell the user to press play on the top result (Windows doesn't let apps start Spotify playback directly).` : `Opened YouTube Music search for “${q}”.`), step: step(`Find “${q.slice(0, 40)}”`, true, true, opened ? 'Opened in Spotify' : 'Opened YouTube Music') };
      }
      case 'set_timer': {
        const sec = Math.max(1, Math.min(86400, a.seconds | 0));
        this.timer(sec);
        return { content: ok(true, true, `Timer set for ${sec} seconds.`), step: step(`Timer ${Math.round(sec / 60) || 1} min`, true, true, 'Shown in the island') };
      }
      case 'autoclicker': {
        if (a.interval_ms) this.config.set('clickInterval', Math.max(20, Math.min(5000, a.interval_ms | 0)));
        const msg = await this.clicker(String(a.action || 'start'));
        const good = !/couldn't|only/i.test(msg);
        return { content: ok(good, good, msg), step: step(`Auto-click: ${a.action}`, good, good, msg) };
      }
      case 'copy_to_clipboard': {
        clipboard.writeText(String(a.text || ''));
        const same = clipboard.readText() === String(a.text || '');
        return { content: ok(true, same, 'Copied to the clipboard.'), step: step('Copy to clipboard', true, same, `${String(a.text || '').length} characters`) };
      }
      case 'ask_user': {
        const options = (a.options || []).map(String).filter(Boolean).slice(0, 6);
        return { content: 'Waiting for the user.', pause: { kind: options.length ? 'choice' : 'text', question: String(a.question || 'Can you tell me more?'), options, multiple: !!a.multiple }, step: { label: 'Waiting for you', status: 'WAITING', detail: '', verified: false } };
      }
      case 'finish_task':
        return { content: 'Finished.', finish: [String(a.outcome || 'completed'), String(a.summary || '')], details: a.details ? String(a.details) : null };
      default:
        return { content: ok(false, false, `Unknown tool ${name}.`) };
    }
  }

  // ------------------------------------------------------------ bookkeeping

  createTask(run, firstTool) {
    const userText = [...run.messages].reverse().find((m) => m.role === 'user')?.content || 'Task';
    const t = this.store.createTask({ title: titleOf(userText), kind: KIND[firstTool] || 'general', conversationId: run.cid });
    run.taskId = t.id;
  }

  record(run, out) {
    if (out.step && run.taskId) this.store.updateTask(run.taskId, (t) => { t.steps.push({ ...out.step, at: Date.now() }); });
  }

  async localReply(run, text, local) {
    if (local.action?.type === 'timer') this.timer(local.action.seconds);
    if (local.action?.type === 'clicker') {
      if (local.action.interval) this.config.set('clickInterval', local.action.interval);
      const m = await this.clicker(local.action.cmd); if (m) local.text = m;
    }
    this.store.addMessage(run.cid, { role: 'assistant', text: local.text });
    this.setActivity(run.cid, null);
    return { text: local.text, taskId: null, status: null, needsInput: false, close: local.close };
  }

  pause(run, input) {
    this.setActivity(run.cid, null);
    if (!run.taskId) this.createTask(run, 'ask_user');
    this.paused.set(run.taskId, run);
    this.store.updateTask(run.taskId, (t) => { t.status = 'REQUIRES_INPUT'; t.input = input; t.fileIds = [...new Set([...t.fileIds, ...run.fileIds])]; });
    this.store.addMessage(run.cid, { role: 'assistant', text: input.question, taskId: run.taskId });
    this.sound('needs');
    return { text: input.question + (input.options.length >= 2 && input.options.length <= 4 ? ` Say ${input.options.join(' or ')}.` : ''), taskId: run.taskId, status: 'REQUIRES_INPUT', needsInput: true };
  }

  finalize(run, finish, text) {
    this.setActivity(run.cid, null);
    const reply = (finish?.[1] || text || '').trim() || 'Done.';
    const shown = run.details ? `${reply}\n\n${run.details}` : reply;
    if (!run.taskId) {
      this.store.addMessage(run.cid, { role: 'assistant', text: shown, sources: run.sources, fileIds: run.fileIds });
      return { text: reply, taskId: null, status: null, needsInput: false };
    }
    const t = this.store.task(run.taskId);
    const actions = t.steps.filter((s) => s.status !== 'WAITING');
    const claimed = (finish?.[0] || 'completed').toLowerCase();
    const last = actions[actions.length - 1];
    let status; let summary = reply;
    if (claimed.startsWith('cancel')) status = 'CANCELLED';
    else if (claimed.startsWith('fail')) status = 'FAILED';
    else if (last?.status === 'FAILED') { status = 'FAILED'; if (!/couldn|fail/i.test(reply)) summary = `${last.label} failed: ${last.detail}`; }
    else if (last && last.verified) status = 'COMPLETED';
    else if (last) { status = 'COMPLETED'; summary = `${reply} (Not confirmed: ${last.label.toLowerCase()}.)`; }
    else { status = 'FAILED'; summary = `Nothing could be done or confirmed. ${reply}`; }
    this.store.updateTask(run.taskId, (x) => { x.status = status; x.summary = summary; x.fileIds = [...new Set([...x.fileIds, ...run.fileIds])]; });
    this.store.addMessage(run.cid, { role: 'assistant', text: run.details ? `${summary}\n\n${run.details}` : summary, taskId: run.taskId, sources: run.sources, fileIds: run.fileIds });
    this.sound(status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'failure' : 'soft');
    return { text: summary, taskId: run.taskId, status, needsInput: false };
  }

  fail(run, e) {
    this.setActivity(run.cid, null);
    const text = e.spoken || `Something went wrong: ${e.message}`;
    if (run.taskId) this.store.updateTask(run.taskId, (t) => { t.status = 'FAILED'; t.summary = text; });
    this.store.addMessage(run.cid, { role: 'error', text, taskId: run.taskId });
    return { text, taskId: run.taskId, status: run.taskId ? 'FAILED' : null, needsInput: false };
  }

  buildMessages(cid) {
    const conv = this.store.conversation(cid);
    const history = (conv?.messages || []).filter((m) => m.role === 'user' || m.role === 'assistant').slice(-14)
      .map((m) => ({ role: m.role, content: m.text.slice(0, 1500) + (m.fileIds?.length ? `\n[files: ${m.fileIds.map((id) => { const f = this.store.file(id); return f ? `${f.fileName} id=${f.id}` : id; }).join(', ')}]` : '') }));
    return [{ role: 'system', content: this.systemPrompt() }, ...history];
  }

  systemPrompt() {
    const now = new Date().toLocaleString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    const files = this.store.data.files.slice(0, 12).map((f) => `- ${f.fileName} (id=${f.id}, ${f.ext})`).join('\n') || 'none yet';
    return `You are Nebula, a warm, capable assistant that DOES things on the user's Windows PC using tools.
Now: ${now} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
Recent files Nebula made:
${files}

How to work:
- First split the request into its parts: "open Spotify and play a song" = (1) play_music with a real song you pick. Tool arguments only ever hold their own part (open_app name = "Spotify"). Never pass a whole sentence as an app name.
- Do the task with tools instead of explaining how. Plain questions: answer directly in 1-3 sentences; use web_search for anything current.
- Multi-step tasks: call plan first, then act step by step.
- Files: write substantial, well-structured, professional content and call create_document ONCE with the full spec. To edit, call get_document then update_document with the complete edited spec.
- Missing essential details? ask_user with short tappable options. Don't ask about things you can reasonably decide.
- Tool results say "verified". Never claim something happened unless a tool verified it.
- When you used action tools, end with finish_task(outcome, summary). Summaries are read aloud: 1-2 short plain sentences, no markdown, no URLs. Put long results in details.
- You can't see or click the screen on Windows; for things only the user can do, say what to press.`;
  }
}

// ------------------------------------------------------------------ helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const toolMsg = (id, content) => ({ role: 'tool', tool_call_id: id, content: String(content).slice(0, 16000) });
const step = (label, ok, verified, detail) => ({ label, status: ok ? 'DONE' : 'FAILED', detail, verified: !!(ok && verified) });
const clean = (s) => String(s).replace(/【[^】]*】/g, '').replace(/\*\*|__|`|#+ /g, '').trim();
const titleOf = (t) => { const s = String(t).replace(/\s+/g, ' ').trim(); return (s.charAt(0).toUpperCase() + s.slice(1)).slice(0, 70); };
function cleanAppName(n) {
  return String(n || '').replace(/^(open|launch|start)\s+/i, '').replace(/^the\s+/i, '')
    .split(/\s+(?:and|then|to|so|that|&)\s+|[,.!?]/i)[0].replace(/\s+app$/i, '').trim().split(/\s+/).slice(0, 4).join(' ');
}
function defaultCurrency() {
  try { return (0).toLocaleString(undefined, { style: 'currency', currency: new Intl.Locale(Intl.DateTimeFormat().resolvedOptions().locale).maximize().region === 'ZA' ? 'ZAR' : 'USD' }).replace(/[\d.,\s]/g, '') || '$'; } catch { return '$'; }
}
function matchAnswer(input, text) {
  const t = text.toLowerCase().trim().replace(/[.!?]$/, '');
  if (!input.options?.length) return input.kind === 'text' ? [text.trim()] : null;
  const hit = input.options.find((o) => o.toLowerCase() === t) || input.options.find((o) => t.includes(o.toLowerCase()) || o.toLowerCase().includes(t));
  if (hit) return [hit];
  if (/^(yes|yeah|yep|sure|ok|okay|do it|go ahead)\b/.test(t)) return [input.options[0]];
  if (/^(no|nope|cancel|stop|don't)\b/.test(t)) return [input.options[input.options.length - 1]];
  return null;
}
function label(name, a) {
  return ({ web_search: 'Searching the web…', create_document: 'Creating your file…', update_document: 'Updating your file…', get_document: 'Opening your file…',
    open_app: `Opening ${cleanAppName(a.name)}…`, open_url: 'Opening…', play_music: 'Finding music…', plan: 'Planning…', set_timer: 'Setting a timer…', autoclicker: 'Auto-click…' })[name] || 'Working on it…';
}

module.exports = { Agent, TOOLS, cleanAppName, matchAnswer };
