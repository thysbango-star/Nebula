'use strict';
// Calls with bots — like a WhatsApp call. You talk; the bot you're talking to answers out loud in its own voice and
// tone. Add more bots and they hear each other: a bot can hand the floor to another ("Researcher, can you check
// that?"), and anything that's real work starts as a real task in that bot's chat, where it shows up as usual.
//
// The engine here is plain: it turns what was heard into who speaks next and what they say. Speech in (the
// transcript) and speech out (the audio) are done by the caller with the same brain and key as the rest of Nebula.

const VOICES = ['hannah', 'troy', 'autumn', 'daniel', 'diana', 'austin']; // Groq Orpheus (English)
const TONES = ['warm', 'calm', 'cheerful', 'confident', 'thoughtful', 'energetic', 'friendly', 'deadpan'];
const MAX_BOT_TURNS = 4;   // bot lines per thing you say (stops bots talking forever)
const MAX_TASKS = 2;       // real tasks started per thing you say

/** A bot's call voice: what it's set to, or a default that differs between bots (by their order). */
function voiceOf(bot, index = 0) {
  const v = bot.voice || {};
  return { voice: VOICES.includes(v.voice) ? v.voice : VOICES[index % VOICES.length], tone: v.tone && /^[a-z][a-z -]{1,24}$/i.test(v.tone) ? v.tone.toLowerCase() : TONES[index % TONES.length] };
}

/** Text that sounds right spoken: no markdown, lists, links or emoji; short. */
function speakable(s) {
  return String(s || '')
    .replace(/```[\s\S]*?```/g, ' ').replace(/`([^`]*)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/https?:\/\/\S+/g, 'the link')
    .replace(/^\s*([-*•]|\d+[.)])\s+/gm, '').replace(/[*_#>|]+/g, ' ')
    .replace(/\[[^\]]{0,30}\]/g, ' ') // stray vocal directions — the tone is added by the speaker
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/gu, '')
    .replace(/\s+/g, ' ').trim().slice(0, 600);
}

/** Pulls {say, next, task} out of a model reply; plain text works too. */
function parseReply(raw) {
  const s = String(raw || '').trim();
  const m = s.match(/\{[\s\S]*\}/);
  if (m) {
    try {
      const j = JSON.parse(m[0]);
      return { say: speakable(j.say || j.reply || ''), next: String(j.next || 'user').trim(), task: j.task ? String(j.task).trim().slice(0, 600) : '' };
    } catch { /* fall through */ }
  }
  return { say: speakable(s), next: 'user', task: '' };
}

/** Which bot a line is aimed at: by name (or first word of a name), else null. */
function addressed(text, bots) {
  const t = ` ${String(text).toLowerCase().replace(/[^a-z0-9 ]+/g, ' ')} `;
  let best = null; let at = Infinity;
  for (const b of bots) {
    for (const n of [b.name, b.name.split(/\s+/)[0]]) {
      const k = ` ${n.toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').trim()} `;
      if (k.trim().length < 3) continue;
      const i = t.indexOf(k); if (i >= 0 && i < at) { at = i; best = b; }
    }
  }
  return best;
}

class CallSession {
  /**
   * @param {object} o
   *   ws       — the Workspace (bots, chats, tasks)
   *   brain    — has agentChat(messages, tools, onWait, opts)
   *   emit     — (event) => void   events for the call screen
   *   speak    — async (bot, voice, text) => void   says it out loud (resolves when the audio is sent)
   */
  constructor(o) {
    this.ws = o.ws; this.brain = o.brain; this.emit = o.emit || (() => {}); this.speakFn = o.speak || (async () => {});
    this.id = `call_${Date.now().toString(36)}`; this.startedAt = Date.now();
    this.botIds = []; this.lines = []; this.gen = 0; this.ended = false; this.lastBot = null;
    for (const id of o.botIds || []) this.add(id, true);
  }

  bots() { return this.botIds.map((id) => this.ws.bot(id)).filter(Boolean); }
  voice(bot) { const all = this.ws.data.bots; return voiceOf(bot, Math.max(0, all.indexOf(bot))); }

  state() {
    return { id: this.id, startedAt: this.startedAt, bots: this.bots().map((b) => ({ id: b.id, name: b.name, shape: b.shape, color: b.color, role: b.role, ...this.voice(b) })), lines: this.lines.slice(-40) };
  }

  add(botId, quiet = false) {
    const b = this.ws.bot(botId); if (!b || this.botIds.includes(botId) || this.botIds.length >= 6) return false;
    this.botIds.push(botId);
    if (!quiet) { this.line('event', `${b.name} joined the call`); this.emit({ type: 'state', state: this.state() }); }
    return true;
  }

  remove(botId) {
    const b = this.ws.bot(botId); const i = this.botIds.indexOf(botId); if (i < 0) return false;
    this.botIds.splice(i, 1); if (this.lastBot === botId) this.lastBot = null;
    if (b) this.line('event', `${b.name} left the call`);
    this.emit({ type: 'state', state: this.state() }); return true;
  }

  line(who, text, extra = {}) {
    const l = { who, text, at: Date.now(), ...extra }; this.lines.push(l); if (this.lines.length > 200) this.lines.shift();
    this.emit({ type: 'line', line: l }); return l;
  }

  /** You talked over them: stop what's queued. */
  interrupt() { this.gen++; this.emit({ type: 'stop' }); }

  /** You said something (already transcribed). Bots answer, possibly to each other, until it's your turn again. */
  async hear(text) {
    text = String(text || '').trim(); if (!text || this.ended) return;
    const gen = ++this.gen;
    this.line('user', text);
    const bots = this.bots(); if (!bots.length) return;
    let bot = addressed(text, bots) || (this.lastBot && this.ws.bot(this.lastBot) && this.botIds.includes(this.lastBot) ? this.ws.bot(this.lastBot) : null) || bots.find((b) => b.primary) || bots[0];
    let tasks = 0;
    for (let turn = 0; turn < MAX_BOT_TURNS && bot; turn++) {
      this.emit({ type: 'thinking', botId: bot.id });
      let r;
      try { r = parseReply(await this.reply(bot, turn)); } catch (e) { if (gen === this.gen) { this.emit({ type: 'thinking', botId: null }); this.line('error', e.message || 'Something went wrong.', { botId: bot.id }); } return; }
      if (gen !== this.gen || this.ended) return;
      if (r.task && tasks < MAX_TASKS) {
        tasks++;
        try {
          const conv = this.ws.mainConv(bot.id);
          this.ws.chat(bot.id, conv.id, `${r.task}\n\n(Asked on a call.)`);
          this.line('task', `${bot.name} started: ${r.task.split('\n')[0].slice(0, 120)}`, { botId: bot.id });
        } catch (e) { r.say = r.say || "I couldn't start that just now."; }
      }
      if (!r.say) { this.emit({ type: 'thinking', botId: null }); break; }
      this.lastBot = bot.id;
      this.line('bot', r.say, { botId: bot.id });
      await this.speakFn(bot, this.voice(bot), r.say).catch(() => {});
      if (gen !== this.gen || this.ended) return;
      const next = r.next && !/^(user|you|me|none|)$/i.test(r.next) ? addressed(r.next, this.bots().filter((b) => b.id !== bot.id)) : null;
      bot = next;
    }
    this.emit({ type: 'thinking', botId: null });
  }

  /** One bot's next line, as {say, next, task} JSON. */
  async reply(bot, turn) {
    const others = this.bots().filter((b) => b.id !== bot.id);
    const ins = bot.ins || {};
    const job = [ins.resp && `Your job: ${ins.resp}`, ins.do && `Always: ${ins.do}`, ins.never && `Never: ${ins.never}`].filter(Boolean).join('\n');
    const mem = (bot.memories || []).slice(0, 8).map((m) => `- ${m.text}`).join('\n');
    const me = this.ws.data.profile || {};
    const { tone } = this.voice(bot);
    const sys = [
      `You are ${bot.name}${bot.role ? `, ${bot.role}` : ''}, one of the user's bots in Nebula. You are on a live voice call${others.length ? ` with the user and ${others.map((b) => `${b.name}${b.role ? ` (${b.role})` : ''}`).join(', ')}` : ' with the user'}.`,
      me.name ? `The user is ${me.name}${me.work ? `, ${me.work}` : ''}.` : '',
      job, mem ? `You remember:\n${mem}` : '',
      `Your speaking tone is ${tone}.`,
      'How to talk on a call:',
      '- Speak like a person on the phone: one to three short sentences, plain words. No lists, markdown, links, emoji or stage directions.',
      '- Never pretend you did something. If it needs real work (research, writing, a file, anything that takes more than a moment), put it in "task" as a clear instruction to yourself and say you are starting on it — the result will land in your chat with the user.',
      '- If you need a fact you do not know, say so rather than guessing, or start a task to find out.',
      others.length ? `- You can hand over to another bot on the call when they are better placed, by naming them in "next" (one of: ${others.map((b) => b.name).join(', ')}). Only do that when it truly helps; otherwise "next" is "user". Do not repeat what another bot just said.` : '- "next" is always "user".',
      turn > 0 ? '- Another bot just handed to you: answer them or the user briefly, then usually give the floor back to the user.' : '',
      'Reply with JSON only: {"say": "what you say out loud", "next": "user", "task": ""}',
    ].filter(Boolean).join('\n');
    const msgs = [{ role: 'system', content: sys }];
    for (const l of this.lines.slice(-24)) {
      if (l.who === 'user') msgs.push({ role: 'user', content: l.text });
      else if (l.who === 'bot') { const b = this.ws.bot(l.botId); msgs.push(l.botId === bot.id ? { role: 'assistant', content: JSON.stringify({ say: l.text, next: 'user', task: '' }) } : { role: 'user', content: `[${b ? b.name : 'Another bot'} said] ${l.text}` }); }
      else if (l.who === 'task') msgs.push({ role: 'user', content: `[Call note] ${l.text}` });
    }
    const r = await this.brain.agentChat(msgs, null, () => {}, { effort: 'low', maxTokens: 400, temperature: 0.6, json: true });
    return r.content;
  }

  /** Ends the call and leaves a note in each bot's chat with how long it was and what was said. */
  end() {
    if (this.ended) return null; this.ended = true; this.gen++;
    const secs = Math.round((Date.now() - this.startedAt) / 1000); const dur = `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
    const said = this.lines.filter((l) => l.who === 'user' || l.who === 'bot');
    const transcript = said.map((l) => `${l.who === 'user' ? 'You' : (this.ws.bot(l.botId)?.name || 'Bot')}: ${l.text}`).join('\n').slice(0, 20000);
    for (const b of this.bots()) {
      const others = this.bots().filter((x) => x.id !== b.id).map((x) => x.name);
      const conv = this.ws.mainConv(b.id);
      this.ws.event(conv.id, 'call', `Call${others.length ? ` with ${others.join(', ')}` : ''} · ${dur}${said.length ? ` · ${said.length} lines` : ''}`, { transcript });
    }
    this.ws.changed();
    return { secs, transcript };
  }
}

module.exports = { CallSession, voiceOf, speakable, parseReply, addressed, VOICES, TONES, MAX_BOT_TURNS };
