'use strict';
// Nebula's brain for Windows: desktop commands handled instantly, everything else goes to Groq.
// Speech-to-text uses Groq's free Whisper model. Mirrors the Android GroqBrain.

const { shell } = require('electron');
const { spawn } = require('child_process');

const BASE = 'https://api.groq.com/openai/v1';
const DEFAULT_MODEL = 'openai/gpt-oss-120b';
const STT_MODELS = ['whisper-large-v3-turbo', 'whisper-large-v3'];
const PREFERRED = ['openai/gpt-oss-120b', 'llama-3.3-70b-versatile', 'openai/gpt-oss-20b', 'llama-3.1-8b-instant'];
const MAX_TURNS = 12;

class ApiError extends Error {
  constructor(code, msg, raw) { super(msg); this.code = code; this.raw = raw || ''; }
}
class SpokenError extends Error {
  constructor(spoken, detail) { super(spoken); this.spoken = spoken; this.detail = detail; }
}

class Brain {
  /** @param {{get:Function,set:Function}} config  @param {Function} getKey */
  constructor(config, getKey, rotateKey = () => false) {
    this.config = config;
    this.getKey = getKey;
    this.rotateKey = rotateKey; // switches to the next backup key; returns false if there isn't one
    this.history = [];
  }

  // ------------------------------------------------------------ agent (tools)

  /** One tool-calling turn for the task engine. Returns the assistant message ({content, tool_calls, sources}). */
  async agentChat(messages, tools, onWait = () => {}) {
    const key = this.getKey();
    if (!key) throw new SpokenError('I need my AI key first. Open Settings and paste your Groq key.');
    let model = this.config.get('model') || DEFAULT_MODEL;
    const tried = new Set(); let waits = 0;
    for (;;) {
      tried.add(model);
      const body = { model, messages, tools, tool_choice: 'auto', parallel_tool_calls: false, temperature: 0.3, max_completion_tokens: 2048 };
      if (model.startsWith('openai/gpt-oss')) body.reasoning_effort = 'medium';
      try {
        const json = await this.request('POST', '/chat/completions', this.getKey(), JSON.stringify(body));
        const m = json?.choices?.[0]?.message;
        if (!m) throw new SpokenError('The AI returned an empty answer.');
        if (this.config.get('model') !== model) this.config.set('model', model);
        return { content: m.content || '', tool_calls: m.tool_calls || [], sources: sourcesFrom(m) };
      } catch (e) {
        if (e instanceof ApiError && e.code === 429 && waits < 4 && !/per day|TPD|RPD/.test(e.message)) {
          const s = Math.min(30, Math.max(1, +((/try again in ([0-9.]+)s/.exec(e.message) || [])[1]) || 6));
          onWait(`Groq's free tier is busy, waiting ${Math.round(s)}s…`); waits++;
          await new Promise((r) => setTimeout(r, s * 1000 + 300)); continue;
        }
        if (e instanceof ApiError && isModelGone(e)) { const next = await this.pickModel(this.getKey(), tried); if (next) { model = next; continue; } }
        throw toSpoken(e);
      }
    }
  }

  /** Web search through Groq's browser tool: {text, sources}. */
  async search(query) {
    const key = this.getKey();
    const model = this.config.get('model') || DEFAULT_MODEL;
    try {
      const r = await this.chat(key, model.startsWith('openai/gpt-oss') ? model : DEFAULT_MODEL,
        [{ role: 'user', content: `Search the web and report the key facts with numbers and dates: ${query}` }], true);
      return { text: r.text, sources: r.sources };
    } catch (e) { return { text: `Search failed: ${e.spoken || e.message}`, sources: [] }; }
  }

  resetConversation() { this.history = []; }

  // ------------------------------------------------------------ public

  /** Returns {text, sources, action?} where action can be {type:'timer', seconds}. */
  async respond(input) {
    const local = this.handleLocal(input);
    if (local) return local;

    const key = this.getKey();
    if (!key) return { text: "I need my AI key first. Open Nebula settings and paste your Groq key.", sources: [] };

    const turn = { role: 'user', content: input };
    this.history.push(turn);
    try {
      const r = await this.call(key, this.config.get('model') || DEFAULT_MODEL, this.history);
      if (!r.text) { this.history.pop(); return { text: "I don't have a good answer for that one.", sources: [] }; }
      this.history.push({ role: 'assistant', content: r.text });
      while (this.history.length > MAX_TURNS) this.history.shift();
      return r;
    } catch (e) {
      this.history = this.history.filter((t) => t !== turn);
      return { text: e.spoken || 'Something went wrong talking to my AI brain.', sources: [] };
    }
  }

  /** Settings "Test" button. */
  async test(rawKey) {
    const key = cleanKey(rawKey || this.getKey());
    if (key.length < 20) return `That doesn't look like a full key (${key.length} characters).`;
    try {
      const r = await this.call(key, this.config.get('model') || DEFAULT_MODEL,
        [{ role: 'user', content: 'Say hello in five words.' }]);
      const model = this.config.get('model') || DEFAULT_MODEL;
      const search = this.config.get('noSearchModel') === model ? 'off (not available for this key)' : 'on';
      return `Connected with ${model}. Web search: ${search}.\nNebula says: ${r.text}`;
    } catch (e) {
      return (e.spoken || 'Something went wrong.') + (e.detail ? `\n\nGroq says: ${e.detail}` : '') +
        `\n(Key starts with “${key.slice(0, 4)}…”)`;
    }
  }

  /** Speech-to-text: 16 kHz mono WAV buffer → text. */
  async transcribe(wav) {
    const key = this.getKey();
    if (!key) throw new SpokenError("I need my AI key first. Open Nebula settings and paste your Groq key.");
    let lastErr;
    for (const model of STT_MODELS) {
      try {
        const form = new FormData();
        form.append('file', new Blob([wav], { type: 'audio/wav' }), 'speech.wav');
        form.append('model', model);
        form.append('response_format', 'json');
        form.append('temperature', '0');
        const json = await this.request('POST', '/audio/transcriptions', key, form);
        return (json.text || '').trim();
      } catch (e) {
        lastErr = e;
        if (!(e instanceof ApiError) || !isModelGone(e)) break;
      }
    }
    throw toSpoken(lastErr);
  }

  /**
   * Natural speech with Groq's Orpheus voices. Returns WAV buffers (one per sentence group),
   * calling onChunk as each arrives so playback can start straight away.
   */
  async speak(text, onChunk) {
    const key = this.getKey();
    if (!key) throw new SpokenError('No Groq key.');
    const parts = chunkText(text, 190);
    const fetchOne = async (input, retry = 0) => {
      const key = this.getKey();
      let res;
      try {
        res = await fetch(BASE + '/audio/speech', {
          method: 'POST', signal: AbortSignal.timeout(25000),
          headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: this.config.get('voiceModel') || 'canopylabs/orpheus-v1-english', voice: this.config.get('groqVoice') || 'troy', input, response_format: 'wav' }),
        });
      } catch (e) { throw new SpokenError('Voice service unreachable.', e.message); }
      if (res.ok) return Buffer.from(await res.arrayBuffer());
      const raw = await res.text();
      if ((res.status === 401 || (res.status === 429 && /per day|TPD|RPD/.test(raw))) && retry < 6 && this.rotateKey()) return fetchOne(input, retry + 1);
      if (/terms/i.test(raw)) throw new SpokenError('Groq needs you to accept the Orpheus terms once: console.groq.com → Playground → pick Orpheus → accept.');
      if (res.status === 401) throw new SpokenError('The voice service rejected the key.');
      if (res.status === 429) throw new SpokenError('Voice limit reached for now.');
      throw new SpokenError(`Voice service error ${res.status}.`, raw.slice(0, 300));
    };
    // Fetch the next piece while the current one is being handed over.
    let next = fetchOne(parts[0]);
    for (let i = 0; i < parts.length; i++) {
      const buf = await next;
      if (i + 1 < parts.length) next = fetchOne(parts[i + 1]).catch((e) => e);
      if (buf instanceof Error) { if (i === 0) throw buf; return; }
      if (onChunk(buf, i === parts.length - 1) === false) return;
    }
  }

  // ------------------------------------------------------------ desktop commands

  handleLocal(input) {
    const q = input.toLowerCase().trim().replace(/[?.!]+$/, '');
    if (!q) return { text: "I didn't catch that.", sources: [] };

    if (/^(what('s| is) the time|what time is it|tell me the time)( now| right now)?$/.test(q)) {
      return { text: `It's ${new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}.`, sources: [] };
    }
    if (/^(what('s| is) (the date|today('s date)?)|what day is it( today)?)$/.test(q)) {
      return { text: `It's ${new Date().toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}.`, sources: [] };
    }
    if (q.includes('timer') && (/\b(set|start)\b/.test(q) || q.startsWith('timer'))) {
      const seconds = parseDuration(q);
      if (seconds <= 0) return { text: 'How long? Try “set a timer for 5 minutes”.', sources: [] };
      return { text: `Timer set for ${describe(seconds)}. It's in the island.`, sources: [], action: { type: 'timer', seconds } };
    }
    if (/^(cancel|stop) (the )?timer/.test(q)) {
      return { text: 'Timer cancelled.', sources: [], action: { type: 'timer', seconds: 0 } };
    }
    // R3cket auto-clicker (clicks wherever the mouse pointer is).
    if (/\b(auto ?-?click(er|ing)?|auto ?tap|clicking)\b/.test(q)) {
      const ms = q.match(/(\d{2,4})\s*(ms|milliseconds?)/);
      const cps = q.match(/(\d+(?:\.\d+)?)\s*(times a second|per second|clicks? a second|cps)/);
      const interval = ms ? +ms[1] : cps ? Math.round(1000 / Math.max(0.2, +cps[1])) : 0;
      if (/\b(stop|end|pause|cancel|turn off|disable)\b/.test(q)) return { text: 'Stopped clicking.', sources: [], action: { type: 'clicker', cmd: 'stop' } };
      if (/\b(faster|speed up)\b/.test(q)) return { text: 'Faster.', sources: [], action: { type: 'clicker', cmd: 'faster' } };
      if (/\b(slower|slow down)\b/.test(q)) return { text: 'Slower.', sources: [], action: { type: 'clicker', cmd: 'slower' } };
      return { text: 'Auto-clicking where your pointer is. Press the auto-click shortcut or say stop clicking to stop.', sources: [],
        action: { type: 'clicker', cmd: 'start', interval } };
    }
    const open = q.match(/^(open|launch|start|go to)\s+(.+)$/);
    // Only a bare "open Spotify". "Open Spotify and play a song" is a task for the AI.
    if (open && open[2].split(/\s+/).length <= 3 && !/\b(and|then|to|play|search|find|send|with|for|show|check)\b|,/.test(open[2])) {
      return this.open(open[2].replace(/^the\s+/, '').replace(/\s+app$/, '').trim());
    }
    return null;
  }

  open(name) {
    const apps = {
      calculator: 'calc', notepad: 'notepad', paint: 'mspaint', 'file explorer': 'explorer', explorer: 'explorer',
      files: 'explorer', settings: 'ms-settings:', 'task manager': 'taskmgr', terminal: 'wt', 'command prompt': 'cmd',
      spotify: 'spotify:', mail: 'outlookmail:', outlook: 'outlook', camera: 'microsoft.windows.camera:',
      store: 'ms-windows-store:', 'microsoft store': 'ms-windows-store:', word: 'winword', excel: 'excel',
      powerpoint: 'powerpnt', edge: 'microsoft-edge:', chrome: 'chrome', whatsapp: 'whatsapp:', discord: 'discord:',
      steam: 'steam:', teams: 'msteams:', photos: 'ms-photos:', clock: 'ms-clock:', calendar: 'outlookcal:',
    };
    const sites = {
      youtube: 'https://youtube.com', gmail: 'https://mail.google.com', google: 'https://google.com',
      netflix: 'https://netflix.com', github: 'https://github.com', maps: 'https://maps.google.com',
      'google maps': 'https://maps.google.com', twitter: 'https://x.com', x: 'https://x.com',
      instagram: 'https://instagram.com', facebook: 'https://facebook.com', reddit: 'https://reddit.com',
      chatgpt: 'https://chatgpt.com', groq: 'https://console.groq.com', drive: 'https://drive.google.com',
    };
    const pretty = name.replace(/\b\w/g, (c) => c.toUpperCase());
    if (sites[name]) { shell.openExternal(sites[name]); return { text: `Opening ${pretty}.`, sources: [], close: true }; }
    if (/^[\w-]+(\.[\w-]+)+(\/.*)?$/.test(name)) {
      shell.openExternal(`https://${name}`);
      return { text: `Opening ${name}.`, sources: [], close: true };
    }
    const target = apps[name] || name.replace(/[^\w .:-]/g, '');
    try {
      const child = spawn('cmd', ['/c', 'start', '""', target], { detached: true, windowsHide: true, stdio: 'ignore' });
      child.unref();
      return { text: `Opening ${pretty}.`, sources: [], close: true };
    } catch {
      return { text: `I couldn't open ${pretty}.`, sources: [] };
    }
  }

  // ------------------------------------------------------------ Groq chat

  async call(key, model, history) {
    let current = model;
    const tried = new Set();
    for (;;) {
      tried.add(current);
      try {
        const r = await this.callWithSearchFallback(key, current, history);
        if (this.config.get('model') !== current) this.config.set('model', current);
        return r;
      } catch (e) {
        if (e instanceof ApiError && isModelGone(e)) {
          const next = await this.pickModel(key, tried);
          if (next) { current = next; continue; }
        }
        throw toSpoken(e);
      }
    }
  }

  async callWithSearchFallback(key, model, history) {
    const allowed = this.config.get('noSearchModel') !== model && model.startsWith('openai/gpt-oss');
    if (!allowed) return this.chat(key, model, history, false);
    try {
      return await this.chat(key, model, history, true);
    } catch (e) {
      const text = `${e.message} ${(e.raw || '').slice(0, 800)}`.toLowerCase();
      const searchProblem = e instanceof ApiError && [400, 403, 404, 422, 429].includes(e.code) &&
        ['browser', 'search', 'tool'].some((w) => text.includes(w)) && !isModelGone(e);
      if (!searchProblem) throw e;
      this.config.set('noSearchModel', model);
      return this.chat(key, model, history, false);
    }
  }

  async chat(key, model, history, search) {
    const body = {
      model,
      messages: [{ role: 'system', content: systemPrompt(search) }, ...history],
      temperature: 0.6,
      max_completion_tokens: 1024,
    };
    if (model.startsWith('openai/gpt-oss')) body.reasoning_effort = 'low';
    if (search) body.tools = [{ type: 'browser_search' }];
    const json = await this.request('POST', '/chat/completions', key, JSON.stringify(body));
    const message = json?.choices?.[0]?.message;
    if (!message) throw new SpokenError("I can't help with that one.");
    return { text: cleanForSpeech(message.content || ''), sources: sourcesFrom(message) };
  }

  async pickModel(key, exclude) {
    try {
      const json = await this.request('GET', '/models', key);
      const ids = (json.data || []).map((m) => m.id)
        .filter((id) => !exclude.has(id) && !['whisper', 'guard', 'tts', 'orpheus', 'playai', 'embed'].some((w) => id.includes(w)));
      return PREFERRED.find((p) => ids.includes(p)) || ids.find((i) => i.includes('gpt-oss-120b')) ||
        ids.find((i) => i.includes('70b')) || ids[0] || null;
    } catch { return null; }
  }

  async request(method, path, key, body) {
    let res;
    try {
      const headers = { Authorization: `Bearer ${key}`, Accept: 'application/json' };
      if (typeof body === 'string') headers['Content-Type'] = 'application/json';
      res = await fetch(BASE + path, { method, headers, body, signal: AbortSignal.timeout(45000) });
    } catch (e) {
      if (e.name === 'TimeoutError') throw new SpokenError('That took too long. Try asking again.');
      throw new SpokenError("I couldn't connect to my AI brain. Check your internet connection.", e.message);
    }
    const text = await res.text();
    if (res.ok) return JSON.parse(text);
    let msg = `HTTP ${res.status}`;
    try { msg = JSON.parse(text).error.message || msg; } catch { /* keep */ }
    // Daily allowance used up (or key revoked): move to the next backup key and retry once per key.
    const exhausted = res.status === 401 || (res.status === 429 && /per day|TPD|RPD/.test(msg)) || (res.status === 403 && /organization/i.test(msg));
    if (exhausted && key === this.getKey() && (this._switches || 0) < 6 && this.rotateKey()) {
      this._switches = (this._switches || 0) + 1;
      try { return await this.request(method, path, this.getKey(), body); } finally { this._switches = 0; }
    }
    throw new ApiError(res.status, msg, text);
  }
}

// ------------------------------------------------------------ helpers

function isModelGone(e) {
  const t = `${e.message} ${(e.raw || '').slice(0, 600)}`.toLowerCase();
  return e.code === 404 || t.includes('decommissioned') || t.includes('model_not_found') ||
    (t.includes('model') && (t.includes('does not exist') || t.includes('not found') || t.includes('no longer')));
}

function toSpoken(e) {
  if (e instanceof SpokenError) return e;
  if (!(e instanceof ApiError)) return new SpokenError('Something went wrong talking to my AI brain.', e && e.message);
  const t = e.message.toLowerCase();
  const detail = `${e.message} (HTTP ${e.code})`;
  if (e.code === 401 || t.includes('api key')) return new SpokenError("My Groq key isn't working. Check it in Nebula settings.", detail);
  if (e.code === 403) return new SpokenError("My Groq key doesn't have access to that.", detail);
  if (e.code === 413 || t.includes('too large')) return new SpokenError('That was too long for me. Try a shorter question.', detail);
  if (e.code === 429) return new SpokenError("I've hit Groq's free limit for now. Try again in a little while.", detail);
  if (isModelGone(e)) return new SpokenError("That AI model isn't available.", detail);
  if (e.code >= 500) return new SpokenError('Groq is having trouble right now. Try again in a moment.', detail);
  return new SpokenError(`My AI brain returned an error (${e.code}).`, detail);
}

function sourcesFrom(message) {
  const out = [];
  const add = (arr) => (arr || []).forEach((o) => {
    if (o && typeof o.url === 'string' && o.url.startsWith('http') && !out.some((s) => s.url === o.url)) {
      let title = o.title;
      if (!title) { try { title = new URL(o.url).hostname.replace(/^www\./, ''); } catch { title = 'Source'; } }
      out.push({ title, url: o.url });
    }
  });
  for (const t of message.executed_tools || []) {
    const sr = t.search_results;
    if (Array.isArray(sr)) add(sr); else if (sr && Array.isArray(sr.results)) add(sr.results);
    add(t.browser_results);
  }
  return out.slice(0, 3);
}

function systemPrompt(search) {
  const now = new Date().toLocaleString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: 'numeric', minute: '2-digit' });
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const web = search
    ? "Use browser search for anything current or factual you're not certain of: news, weather, sports, prices, opening hours, people's current roles, recent releases."
    : "You can't browse the web right now. For live things like today's weather, scores or breaking news, give your best general answer and say briefly that you can't check live info.";
  return [
    "You are Nebula, a warm, quick-witted voice assistant on the user's Windows PC.",
    'Your replies are read aloud and shown in a small island at the top of the screen, so:',
    '- Answer in one to three short sentences unless the user asks for more detail.',
    '- Write plain conversational text: no markdown, lists, tables, emojis, URLs or citations.',
    '- Say numbers, times and units the way a person would speak them.',
    web,
    `It is now ${now}, time zone ${tz}. Use this for "today", "tonight", "this weekend" and local answers; if a question needs the user's city and you can't tell it, ask briefly.`,
    'The PC itself handles the R3cket auto-clicker ("auto-click", "stop clicking", "click faster"), timers ("set a timer for 10 minutes") and opening apps or sites ("open Spotify", "open YouTube"). If the user asks for one of those in a way that did not trigger it, tell them the phrase to use.',
    "If you can't do something, say so in one sentence.",
  ].join('\n');
}

function cleanForSpeech(s) {
  return s
    .replace(/【[^】]*】/g, '')
    .replace(/\[\d+(,\s*\d+)*]/g, '')
    .replace(/\*\*|__|`|#+ /g, '')
    .replace(/^\s*[-*•]\s+/gm, '')
    .replace(/\[(.+?)]\(.+?\)/g, '$1')
    .replace(/\s*\n\s*/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

/** Sentence-sized pieces under max characters (Orpheus reads up to ~200 at a time). */
function chunkText(text, max) {
  const out = []; let cur = '';
  for (const s of String(text).split(/(?<=[.!?;:])\s+/)) {
    const words = s.length > max ? s.split(/\s+/) : [s];
    for (const w of words) {
      if (cur && cur.length + w.length + 1 > max) { out.push(cur.trim()); cur = ''; }
      cur += w + ' ';
    }
  }
  if (cur.trim()) out.push(cur.trim());
  return out.length ? out : [String(text).slice(0, max)];
}

function cleanKey(raw) {
  return String(raw || '').split('').filter((c) => c.charCodeAt(0) >= 33 && c.charCodeAt(0) <= 126 && !`"'\``.includes(c)).join('');
}

const SMALL = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, fifteen: 15, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, ninety: 90,
};
function wordsToNumber(text) {
  const w = text.split(/[ -]/).filter(Boolean);
  if (w.length >= 2) {
    const tens = SMALL[w[w.length - 2]]; const ones = SMALL[w[w.length - 1]];
    if (tens && ones && tens >= 20 && ones < 10) return tens + ones;
  }
  return SMALL[w[w.length - 1]];
}
function parseDuration(input) {
  const q = input.replace('half an hour', '30 minutes').replace('an hour and a half', '90 minutes')
    .replace('a minute and a half', '90 seconds');
  const re = /(\d+|[a-z]+(?:[- ][a-z]+)?)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b/g;
  let total = 0; let m;
  while ((m = re.exec(q))) {
    const n = /^\d+$/.test(m[1]) ? parseInt(m[1], 10) : wordsToNumber(m[1]);
    if (!n) continue;
    total += m[2].startsWith('h') ? n * 3600 : m[2].startsWith('m') ? n * 60 : n;
  }
  return total;
}
function describe(sec) {
  const h = Math.floor(sec / 3600); const m = Math.floor((sec % 3600) / 60); const s = sec % 60;
  return [h && `${h} hour${h > 1 ? 's' : ''}`, m && `${m} minute${m > 1 ? 's' : ''}`, s && `${s} second${s > 1 ? 's' : ''}`]
    .filter(Boolean).join(' and ');
}

module.exports = { Brain, cleanKey, parseDuration, chunkText, DEFAULT_MODEL };
