'use strict';
// Nebula Workspace (powered by Rovian Intelligence) — RovBots.
// Bots with identities, jobs, knowledge, memory, skills and permissions; a real task queue on top of Nebula's
// task engine (agent.js); approval cards; routines that run on a schedule while Nebula is running; teams that
// delegate to each other. Everything here is real: a bot is only "working" when its task is actually running.
//
// Storage: <userData>/workspace.json (bots, skills, routines, teams, activity, profile) + Workspace/knowledge/*.txt.
// Conversations, tasks and files live in Nebula's existing Store, tagged with botId — so tasks also show in the
// island and widgets.

const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');
const { id } = require('./store');
const { McpHub } = require('./mcp');

// ------------------------------------------------------------------ catalogue

/** What each tool does, how risky it is, and where it acts. risk: none · low · medium · high. */
const TOOL_INFO = {
  plan: { risk: 'none' }, ask_user: { risk: 'none' }, finish_task: { risk: 'none' },
  search_knowledge: { risk: 'none', label: 'Search its knowledge', app: 'Knowledge' },
  remember: { risk: 'none', label: 'Save a memory', app: 'Memory' },
  update_self: { risk: 'none', label: 'Update its own job', app: 'Settings' },
  create_routine: { risk: 'none', label: 'Schedule a routine', app: 'Routines' },
  manage_routine: { risk: 'none', label: 'Change a routine', app: 'Routines' },
  web_search: { risk: 'none', label: 'Search the web', app: 'Web search' },
  get_document: { risk: 'none', label: 'Read a file', app: 'Files' },
  create_document: { risk: 'low', label: 'Create a file', app: 'Files', reversible: true, consequence: 'A new file is saved in Nebula Files. Nothing else changes.' },
  update_document: { risk: 'high', label: 'Overwrite a file', app: 'Files', reversible: false, consequence: 'The file\'s current content is replaced. The old version is not kept.' },
  set_timer: { risk: 'low', label: 'Start a timer', app: 'Island', reversible: true, consequence: 'A countdown appears in the island.' },
  open_app: { risk: 'medium', label: 'Open an app', app: 'This PC', reversible: true, consequence: 'An app opens on your screen.' },
  open_url: { risk: 'medium', label: 'Open a link', app: 'Browser', reversible: true, consequence: 'Your browser (or the linked app) opens this address.' },
  play_music: { risk: 'medium', label: 'Find music', app: 'Spotify / YouTube Music', reversible: true, consequence: 'Spotify or YouTube Music opens with a search.' },
  copy_to_clipboard: { risk: 'medium', label: 'Replace the clipboard', app: 'Clipboard', reversible: false, consequence: 'Whatever is on your clipboard now is replaced.' },
  autoclicker: { risk: 'high', label: 'Auto-click', app: 'Mouse', reversible: false, consequence: 'Nebula clicks wherever your mouse pointer is until stopped.' },
  delegate_task: { risk: 'low', label: 'Hand work to another bot', app: 'Workspace', reversible: true, consequence: 'Another bot starts a task with the context given.' },
};

/** The tool groups a user switches on per bot (Step 7 — Tools & Applications). */
const CAPABILITIES = [
  { id: 'web', label: 'Web research', desc: 'Search the web and cite sources.', tools: ['web_search'] },
  { id: 'files', label: 'Create & edit files', desc: 'Word, Excel, PowerPoint, PDF, CSV, Markdown, HTML.', tools: ['create_document', 'get_document', 'update_document'] },
  { id: 'apps', label: 'Open apps & websites', desc: 'Open programs and links on this PC.', tools: ['open_app', 'open_url'] },
  { id: 'music', label: 'Music', desc: 'Find music in Spotify or YouTube Music.', tools: ['play_music'] },
  { id: 'timer', label: 'Timers', desc: 'Start countdowns in the island.', tools: ['set_timer'] },
  { id: 'clipboard', label: 'Clipboard', desc: 'Put text on your clipboard.', tools: ['copy_to_clipboard'] },
  { id: 'clicker', label: 'Auto-clicker', desc: 'R3cket auto-click where your pointer is.', tools: ['autoclicker'] },
  { id: 'delegate', label: 'Work with other bots', desc: 'Hand parts of a task to bots you allow.', tools: ['delegate_task'] },
];
const CORE_TOOLS = ['plan', 'ask_user', 'finish_task', 'search_knowledge', 'remember', 'update_self', 'create_routine', 'manage_routine'];

const AUTONOMY = {
  assistant: { label: 'Assistant', desc: 'Asks before every action, even creating a file.', approve: ['low', 'medium', 'high'] },
  copilot: { label: 'Copilot', desc: 'Makes files on its own; asks before anything that touches your PC.', approve: ['medium', 'high'] },
  supervised: { label: 'Supervised agent', desc: 'Plans and works step by step; asks before actions on your PC.', approve: ['medium', 'high'] },
  independent: { label: 'Independent agent', desc: 'Works on its own; asks only before risky or irreversible actions.', approve: ['high'] },
  high: { label: 'High autonomy', desc: 'Never stops except for irreversible actions — those always need you.', approve: ['high'] },
};
const PRIORITY = { urgent: 0, high: 1, normal: 2, low: 3 };
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

const PERSONALITY = ['Professional', 'Friendly', 'Concise', 'Detailed', 'Analytical', 'Creative', 'Casual', 'Formal', 'Direct', 'Humorous', 'Encouraging'];
const SLIDERS = ['formality', 'verbosity', 'creativity', 'initiative', 'proactiveness', 'confidence', 'caution'];

/** Ready-made bots (the Marketplace). Local templates — no ratings or reviews, because none exist. */
const TEMPLATES = [
  { key: 'chief', name: 'Chief of Staff', role: 'Chief of Staff', shape: 0, color: '#7A3CFF', cat: 'Productivity', desc: 'Organises work, priorities and briefings, and hands work to your other bots.', caps: ['web', 'files', 'delegate'], autonomy: 'supervised', primary: true,
    resp: 'Keep track of what I am working on, what is due and what needs my attention. Prepare briefings. Coordinate my other bots.', approach: 'Start with what matters most today. Delegate research and writing to the right bot when that is faster.', output: 'Short briefings with a "Needs you" section at the top.', samples: ['Prepare my morning briefing', 'What should I focus on this afternoon?'] },
  { key: 'research', name: 'Researcher', role: 'Researcher', shape: 3, color: '#FACB34', cat: 'Research', desc: 'Researches a topic across sources, cross-checks claims and writes a cited report.', caps: ['web', 'files'], autonomy: 'independent',
    resp: 'Answer research questions with evidence.', approach: 'Search several sources, prefer primary and credible ones, cross-check important claims, keep every source URL.', never: 'Never invent a source, quote or number. Never bypass paywalls or logins.', output: 'A report with findings, disagreements between sources, open questions and a Sources list.', samples: ['Research the five biggest AI developments this month', 'Compare three budget laptops for students'] },
  { key: 'pm', name: 'Project Manager', role: 'Project manager', shape: 4, color: '#10AA6E', cat: 'Business', desc: 'Breaks projects into tasks, tracks deadlines and risks, writes status updates.', caps: ['files', 'delegate'], autonomy: 'supervised',
    resp: 'Plan projects, track progress and surface blockers.', output: 'Plans as tables (task, owner, due, status); status updates in five bullets or fewer.', samples: ['Plan the launch of my app over 4 weeks', 'Write this week\'s project update'] },
  { key: 'writer', name: 'Content Writer', role: 'Writer', shape: 7, color: '#FD715F', cat: 'Writing', desc: 'Drafts and revises articles, posts, emails and scripts in your voice.', caps: ['web', 'files'], autonomy: 'copilot',
    resp: 'Write clear, engaging content.', style: 'Plain, warm, active voice. Short paragraphs. No filler.', samples: ['Draft a blog post about my product launch', 'Rewrite this paragraph to be punchier'] },
  { key: 'dev', name: 'Developer', role: 'Developer', shape: 5, color: '#2DBFCD', cat: 'Development', desc: 'Helps with code, debugging and technical docs.', caps: ['web', 'files'], autonomy: 'copilot',
    resp: 'Explain, write and review code.', output: 'Code in fenced blocks with the language named. Explain changes briefly.', samples: ['Explain this error message', 'Write a Python script that renames files by date'] },
  { key: 'analyst', name: 'Data Analyst', role: 'Analyst', shape: 8, color: '#2DBFCD', cat: 'Data', desc: 'Analyses tables and spreadsheets, finds trends and anomalies, builds charts and reports.', caps: ['files', 'web'], autonomy: 'supervised',
    resp: 'Turn data into findings.', never: 'Never overwrite the source data — always save results as a new file.', output: 'Findings first, then the numbers, then a spreadsheet with the working.', samples: ['Build a monthly budget spreadsheet', 'Analyse these sales figures I paste in'] },
  { key: 'marketing', name: 'Marketing Strategist', role: 'Marketing specialist', shape: 1, color: '#CA1D4B', cat: 'Marketing', desc: 'Researches audiences and competitors and plans campaigns.', caps: ['web', 'files', 'delegate'], autonomy: 'supervised',
    resp: 'Plan campaigns that fit the audience and budget.', samples: ['Plan a launch campaign for my product', 'Who are my competitors and how do they market?'] },
  { key: 'planner', name: 'Personal Planner', role: 'Scheduler', shape: 6, color: '#10AA6E', cat: 'Personal assistance', desc: 'Organises your tasks, plans your days and sets timers.', caps: ['timer', 'files'], autonomy: 'copilot',
    resp: 'Turn what I need to do into a realistic plan.', samples: ['Plan my Saturday around a 2pm match', 'Make me a revision timetable for exams'] },
  { key: 'meeting', name: 'Meeting Assistant', role: 'Meeting assistant', shape: 9, color: '#CA1D4B', cat: 'Productivity', desc: 'Prepares agendas and turns notes you paste into summaries, decisions and action items.', caps: ['files', 'web'], autonomy: 'copilot',
    resp: 'Prepare meetings and write follow-ups from notes the user gives you.', never: 'Never claim to have attended a meeting — Nebula cannot join calls yet.', samples: ['Make an agenda for a 30-minute kickoff', 'Turn these notes into action items'] },
  { key: 'watch', name: 'Competitor Watch', role: 'Analyst', shape: 2, color: '#2F2562', cat: 'Research', desc: 'Checks on companies or topics you follow and reports only what changed. Pair it with a routine.', caps: ['web', 'files'], autonomy: 'independent',
    resp: 'Find significant news about the companies or topics I list.', approach: 'Filter out noise. Report only meaningful changes, each with its source and date.', samples: ['Check what changed at OpenAI, Google and Meta this week'] },
  { key: 'inbox', name: 'Inbox Manager', role: 'Personal assistant', shape: 3, color: '#FACB34', cat: 'Productivity', desc: 'Sorts email and drafts replies.', caps: ['files'], autonomy: 'assistant', needs: 'Email (Gmail / Outlook) — not connected yet, so it can only work on emails you paste in.',
    resp: 'Prioritise and draft replies to emails the user pastes in.', never: 'Never say an email was sent — sending is not available.', samples: ['Draft a polite reply to this email'] },
];

const SKILL_TEMPLATES = [
  ['Research a topic', 'Research', 'Search several credible sources, cross-check, and write a cited summary with open questions.', ['web_search', 'create_document']],
  ['Prepare a weekly report', 'Reporting', 'Collect this week\'s completed tasks and files, then write a one-page report: done, in progress, blocked, next.', ['create_document']],
  ['Create a presentation', 'Documents', 'Outline first (title, 6–10 slides, one idea each), then build the deck with short bullets and speaker notes. No walls of text.', ['create_document']],
  ['Analyse a spreadsheet', 'Data', 'Read the data, check for gaps, compute totals and trends, flag anomalies, and save findings as a new spreadsheet.', ['get_document', 'create_document']],
  ['Prepare meeting notes', 'Meetings', 'From notes the user gives: summary, decisions, action items with owners and dates.', ['create_document']],
  ['Monitor a competitor', 'Research', 'Search for news on the companies listed from the last 7 days. Report only significant changes with sources.', ['web_search']],
  ['Draft social posts', 'Marketing', 'Write 3 variations per platform requested, within each platform\'s length limits, with a clear call to action.', []],
  ['Generate a project update', 'Reporting', 'Summarise progress against the plan: on track, at risk, blocked, with next steps.', ['create_document']],
  ['Prepare morning briefing', 'Personal', 'Today: tasks waiting, what finished overnight, what needs approval, and one suggested focus.', []],
  ['Review a website', 'Research', 'Look up the site, describe its purpose, audience, strengths and weaknesses, with sources.', ['web_search']],
];

// ------------------------------------------------------------------ pure helpers (tested)

const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const words = (s) => (String(s).toLowerCase().match(/[a-z0-9À-ɏ]{3,}/g) || []).filter((w) => !STOP.has(w));
const STOP = new Set('the and for with that this from have what your you are was were will would could should about into they them their there then than when which while been being also just more most some such only over very can not but all any our out who how why its it\'s'.split(' '));

/** Split knowledge text into overlapping chunks. */
function chunk(text, size = 900, overlap = 150) {
  const t = String(text).replace(/\r/g, '');
  const out = [];
  for (let i = 0; i < t.length && out.length < 4000; i += size - overlap) out.push(t.slice(i, i + size));
  return out;
}

/** Keyword retrieval (BM25-ish) over chunks: [{title, text}] → best matches for the query. */
function retrieve(chunks, query, k = 4) {
  const q = [...new Set(words(query))];
  if (!q.length || !chunks.length) return [];
  const df = {}; const toks = chunks.map((c) => { const w = words(c.text); const s = new Set(w); for (const x of s) df[x] = (df[x] || 0) + 1; return w; });
  const avg = toks.reduce((a, w) => a + w.length, 0) / toks.length || 1;
  const N = chunks.length;
  const scored = chunks.map((c, i) => {
    const tf = {}; for (const w of toks[i]) tf[w] = (tf[w] || 0) + 1;
    let s = 0;
    for (const w of q) if (tf[w]) { const idf = Math.log(1 + (N - df[w] + 0.5) / (df[w] + 0.5)); s += idf * (tf[w] * 2.2) / (tf[w] + 1.2 * (0.25 + 0.75 * toks[i].length / avg)); }
    if (words(c.title).some((w) => q.includes(w))) s += 0.6;
    return { ...c, score: s };
  }).filter((c) => c.score > 0).sort((a, b) => b.score - a.score);
  return scored.slice(0, k);
}

const pad2 = (n) => String(n).padStart(2, '0');
/** When a routine runs next (local time), or null. schedule: {type, time 'HH:MM', days [0-6], dom 1-31, date 'YYYY-MM-DD', every minutes}. */
function nextRun(s, from = Date.now(), last = 0) {
  if (!s || !s.type) return null;
  const [hh, mm] = String(s.time || '09:00').split(':').map((x) => parseInt(x, 10) || 0);
  if (s.type === 'interval') { const ev = Math.max(15, s.every | 0 || 60) * 60000; return last ? Math.max(from, last + ev) : from + 60000; }
  if (s.type === 'once') { const d = new Date(`${s.date || ''}T${pad2(hh)}:${pad2(mm)}:00`); const t = d.getTime(); return isNaN(t) || t <= from || (last && last >= t) ? null : t; }
  const d = new Date(from); d.setSeconds(0, 0); d.setHours(hh, mm);
  for (let i = 0; i < 400; i++) {
    const t = d.getTime();
    if (t > from) {
      const wd = d.getDay();
      const ok = s.type === 'daily' || (s.type === 'weekdays' && wd >= 1 && wd <= 5)
        || (s.type === 'weekly' && (s.days && s.days.length ? s.days : [1]).includes(wd))
        || (s.type === 'monthly' && d.getDate() === Math.min(s.dom || 1, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()))
        || (s.type === 'yearly' && `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` === String(s.date || '').slice(5));
      if (ok) return t;
    }
    d.setDate(d.getDate() + 1); d.setHours(hh, mm);
  }
  return null;
}

function describeSchedule(s) {
  if (!s) return 'Not scheduled';
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const at = `at ${s.time || '09:00'}`;
  switch (s.type) {
    case 'once': return `Once on ${s.date} ${at}`;
    case 'daily': return `Every day ${at}`;
    case 'weekdays': return `Weekdays ${at}`;
    case 'weekly': return `Every ${(s.days && s.days.length ? s.days : [1]).map((d) => DAYS[d]).join(', ')} ${at}`;
    case 'monthly': return `Monthly on day ${s.dom || 1} ${at}`;
    case 'yearly': return `Every year on ${String(s.date || '').slice(5)} ${at}`;
    case 'interval': return `Every ${Math.max(15, s.every | 0 || 60)} minutes`;
    case 'task': return 'When a task finishes';
    default: return 'Manual only';
  }
}

/** Does this bot need the user's OK for this tool? */
function needsApproval(bot, tool, riskOverride) {
  const info = TOOL_INFO[tool]; const risk = riskOverride || (info ? info.risk : 'medium');
  if (risk === 'none') return false;
  const lvl = AUTONOMY[bot.autonomy] || AUTONOMY.supervised;
  let need = lvl.approve.includes(risk);
  if (need && risk !== 'high' && bot.alwaysAllow && bot.alwaysAllow[tool]) need = false; // "always allow" never covers high-risk actions
  return need;
}

function allowedTools(bot) {
  const set = new Set(CORE_TOOLS);
  for (const c of CAPABILITIES) if ((bot.caps || []).includes(c.id)) c.tools.forEach((t) => set.add(t));
  return set;
}

function inTerms(v, lo, hi) { return v < 0.34 ? lo : v > 0.66 ? hi : null; }
function personalityText(p = {}) {
  const parts = [];
  if (p.preset && p.preset !== 'Custom') parts.push(`Tone: ${p.preset}.`);
  if (p.custom) parts.push(p.custom);
  const map = { formality: ['casual', 'formal'], verbosity: ['brief', 'thorough and detailed'], creativity: ['conventional', 'inventive'], initiative: ['wait for direction', 'take initiative and suggest next steps'], proactiveness: ['only do what is asked', 'point out things the user did not ask about but should know'], confidence: ['hedge when unsure', 'be decisive'], caution: ['move fast', 'double-check before acting'] };
  for (const k of SLIDERS) { const v = p[k]; if (typeof v === 'number') { const t = inTerms(v, map[k][0], map[k][1]); if (t) parts.push(`Be ${t}.`.replace('Be wait', 'Wait').replace('Be only', 'Only').replace('Be point', 'Point').replace('Be take', 'Take').replace('Be hedge', 'Hedge').replace('Be move', 'Move').replace('Be double', 'Double')); } }
  return parts.join(' ');
}

function newBot(o = {}) {
  return {
    id: id(), handle: '', name: 'New bot', shape: 0, color: '#7A3CFF', description: '', welcome: '', statusNote: '',
    role: 'Personal assistant',
    ins: { resp: '', do: '', never: '', approach: '', communication: '', style: '', boundaries: '', priorities: '', escalation: '', output: '', quality: '', notes: '' },
    personality: { preset: 'Friendly', formality: 0.5, verbosity: 0.5, creativity: 0.5, initiative: 0.5, proactiveness: 0.5, confidence: 0.5, caution: 0.5, custom: '' },
    caps: ['web', 'files'], skills: [], knowledge: [], memories: [], autonomy: 'supervised', alwaysAllow: {},
    collab: { allowed: [] }, notify: { done: true, failed: true, approval: true }, memoryOn: true,
    primary: false, paused: false, archived: false, draft: false, owner: 'you', template: '', createdAt: Date.now(), updatedAt: Date.now(),
    ...o,
  };
}

function fromTemplate(t, extra = {}) {
  return newBot({ name: t.name, handle: slug(t.name), role: t.role, shape: t.shape, color: t.color, description: t.desc, caps: [...t.caps], autonomy: t.autonomy, primary: !!t.primary, template: t.key,
    welcome: `Hi, I'm ${t.name}. ${t.desc}`,
    ins: { resp: t.resp || '', do: '', never: t.never || '', approach: t.approach || '', communication: '', style: t.style || '', boundaries: '', priorities: '', escalation: 'If something is unclear or risky, ask before going on.', output: t.output || '', quality: '', notes: '' },
    ...extra });
}
const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'bot';

function botStatus(bot, tasks, activity, running) {
  if (bot.archived) return { key: 'archived', label: 'Archived' };
  if (bot.draft) return { key: 'draft', label: 'Draft' };
  const mine = tasks.filter((t) => t.botId === bot.id);
  if (mine.some((t) => t.status === 'REQUIRES_INPUT' && t.input && t.input.kind === 'approval')) return { key: 'approval', label: 'Waiting for approval' };
  if (mine.some((t) => t.status === 'REQUIRES_INPUT')) return { key: 'user', label: 'Waiting for you' };
  const live = mine.find((t) => t.status === 'IN_PROGRESS' && running.has(t.id)) || mine.find((t) => t.status === 'IN_PROGRESS' && activity[t.conversationId]);
  const chatLive = Object.keys(activity).find((cid) => (bot._convs || new Set()).has(cid));
  const act = live ? activity[live.conversationId] : chatLive ? activity[chatLive] : null;
  if (bot.paused) return { key: 'paused', label: live ? 'Pausing…' : 'Paused' };
  if (live || chatLive) {
    const a = String(act || '');
    if (/search/i.test(a)) return { key: 'research', label: 'Researching', detail: a };
    if (/creat|updat|file/i.test(a)) return { key: 'writing', label: 'Writing', detail: a };
    if (/open|music/i.test(a)) return { key: 'browsing', label: 'Opening', detail: a };
    if (/hand|deleg/i.test(a)) return { key: 'collab', label: 'Collaborating', detail: a };
    if (/think|plan/i.test(a) || !a) return { key: 'thinking', label: 'Thinking', detail: a };
    return { key: 'working', label: 'Working', detail: a };
  }
  if (mine.some((t) => t.status === 'QUEUED')) return { key: 'queued', label: 'Queued' };
  const last = mine.find((t) => TERMINAL.has(t.status));
  if (last && last.status === 'FAILED' && !last.seen && Date.now() - (last.updatedAt || 0) < 86400000) return { key: 'attention', label: 'Needs attention' };
  return { key: 'idle', label: 'Idle' };
}

function stripHtml(h) {
  return String(h).replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>|<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<(br|p|div|li|h[1-6]|tr)[^>]*>/gi, '\n').replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim();
}

const TEXT_EXT = /\.(txt|md|markdown|csv|tsv|json|html?|xml|log|ya?ml|js|ts|py|java|kt|c|cpp|cs|go|rs|rb|php|sql|css|ini|cfg|toml|srt|vtt)$/i;

// ------------------------------------------------------------------ the bot runtime (extends the task engine)

function makeBotAgent(Base, ws, extraSchemas) {
  const { step, toolMsg, TOOLS } = ws.engine;
  return class BotAgent extends Base {
    botFor(run) { const c = this.store.conversation(run.cid); return c && ws.bot(c.botId); }
    isAction(name) { return name === 'delegate_task' || name.startsWith('mcp__'); }

    toolsFor(run) {
      const bot = this.botFor(run); if (!bot) return TOOLS;
      const allow = allowedTools(bot);
      return [...[...TOOLS, ...extraSchemas].filter((t) => allow.has(t.function.name)), ...ws.mcpFor(bot).map((x) => x.schema)];
    }

    guard(name, args, run) {
      const bot = this.botFor(run); if (!bot) return null;
      if (name.startsWith('mcp__')) {
        const x = ws.mcpFor(bot).find((y) => y.key === name);
        if (!x) return { deny: `${bot.name} isn't allowed to use that connected app tool. The user can switch it on in this bot's Tools settings.` };
        if (run.routineMode === 'always' && x.risk !== 'none') return { approve: true, card: ws.card(bot, name, args, run) };
        if (!needsApproval(bot, name, x.risk)) return null;
        return { approve: true, card: ws.card(bot, name, args, run) };
      }
      if (!allowedTools(bot).has(name)) return { deny: `${bot.name} isn't allowed to use "${TOOL_INFO[name]?.label || name}". The user can switch it on in this bot's Tools settings. Continue without it.` };
      if (run.routineMode === 'always' && TOOL_INFO[name] && TOOL_INFO[name].risk !== 'none') return { approve: true, card: ws.card(bot, name, args, run) };
      if (!needsApproval(bot, name)) return null;
      return { approve: true, card: ws.card(bot, name, args, run) };
    }

    labelFor(name, a) {
      if (name === 'search_knowledge') return 'Searching its knowledge…';
      if (name === 'remember') return 'Saving to memory…';
      if (name === 'delegate_task') return `Handing work to ${a.bot}…`;
      if (name.startsWith('mcp__')) { const x = ws.mcp.find(name); return x ? `Using ${x.server.name}: ${x.tool.title || x.tool.name}…` : 'Using a connected app…'; }
      return null;
    }

    taskExtras(run) {
      const bot = this.botFor(run);
      return bot ? { botId: bot.id, source: 'chat', priority: 'normal', startedAt: Date.now(), depth: run.depth || 0 } : {};
    }

    async extraExec(name, a, run) {
      const bot = this.botFor(run);
      if (!bot) return null;
      if (name === 'search_knowledge') {
        const hits = ws.searchKnowledge(bot, String(a.query || ''), 5);
        return { content: JSON.stringify({ ok: true, results: hits.map((h) => ({ source: h.title, text: h.text })) }), step: run.taskId ? step(`Searched its knowledge for “${String(a.query).slice(0, 40)}”`, true, true, `${hits.length} passages`) : null };
      }
      if (name === 'remember') {
        if (bot.memoryOn === false) return { content: JSON.stringify({ ok: false, result: 'Memory is switched off for this bot.' }) };
        const text = String(a.fact || '').trim().slice(0, 400);
        if (!text) return { content: JSON.stringify({ ok: false, result: 'Nothing to remember.' }) };
        if (/password|passcode|secret|api[ _-]?key|token|pin\b|private key/i.test(text)) return { content: JSON.stringify({ ok: false, result: 'Not saved: memories must never hold passwords, keys or secrets.' }) };
        ws.addMemory(bot.id, text, 'bot');
        return { content: JSON.stringify({ ok: true, verified: true, result: 'Saved.' }), step: run.taskId ? step('Saved a memory', true, true, text.slice(0, 80)) : null };
      }
      if (name === 'delegate_task') return ws.delegate(bot, a, run);
      if (name === 'update_self') return ws.updateSelf(bot, a, run);
      if (name === 'create_routine') return ws.routineFromChat(bot, a, run);
      if (name === 'manage_routine') return ws.manageRoutineFromChat(bot, a, run);
      if (name.startsWith('mcp__')) {
        const x = ws.mcp.find(name);
        if (!x || !ws.mcpFor(bot).some((y) => y.key === name)) return { content: JSON.stringify({ ok: false, result: 'That tool isn\'t available.' }) };
        const label = `${x.server.name}: ${x.tool.title || x.tool.name}`;
        try {
          const r = await ws.mcp.call(name, a);
          return { content: JSON.stringify({ ok: r.ok, verified: r.ok, result: r.text }), step: step(label, r.ok, r.ok, r.text.replace(/\s+/g, ' ').slice(0, 140)) };
        } catch (e) {
          return { content: JSON.stringify({ ok: false, result: `${x.server.name} failed: ${e.message}` }), step: step(label, false, false, e.message.slice(0, 140)) };
        }
      }
      return null;
    }

    systemPrompt(cid) {
      const conv = cid && this.store.conversation(cid);
      const bot = conv && ws.bot(conv.botId);
      return bot ? ws.prompt(bot, conv) : super.systemPrompt(cid);
    }

    onTurn(run) { ws.checkpoint(run); }
    onHeld(run) { ws.checkpoint(run); ws.log(this.botFor(run), 'paused', 'Paused', run.taskId); }
    onApproval(run, ap, choice, edited) { ws.approvalDecided(this.botFor(run), run, ap, choice, edited); }

    restore(t) { return ws.restoreRun(t); }

    record(run, out) {
      super.record(run, out);
      if (out.step && run.taskId) ws.log(this.botFor(run), out.step.status === 'DONE' ? 'step' : out.step.status === 'WAITING' ? 'waiting' : 'error', out.step.label + (out.step.detail ? ` — ${out.step.detail}` : ''), run.taskId);
    }

    createTask(run, first) {
      super.createTask(run, first);
      ws.log(this.botFor(run), 'task', `Started “${this.store.task(run.taskId)?.title}” from chat`, run.taskId);
    }

    pause(run, input) {
      const r = super.pause(run, input);
      ws.checkpoint(run);
      ws.paused(this.botFor(run), run, input);
      return r;
    }

    finalize(run, finish, text) {
      const bot = this.botFor(run);
      // A task that was answered in writing (research, drafting) is genuinely complete: its deliverable is the answer.
      if (run.taskId && !finish && text) {
        const t = this.store.task(run.taskId);
        if (t && !t.steps.some((s) => s.status !== 'WAITING')) this.store.updateTask(run.taskId, (x) => { x.steps.push({ label: 'Wrote the answer', status: 'DONE', detail: `${text.length} characters, in the chat`, verified: true, at: Date.now() }); });
      }
      const r = super.finalize(run, finish, text);
      if (run.taskId) ws.finished(bot, run.taskId);
      return r;
    }

    fail(run, e) {
      const r = super.fail(run, e);
      if (run.taskId) ws.finished(this.botFor(run), run.taskId);
      return r;
    }

    /** Run a workspace task (assigned, routine, delegated or resumed). */
    async runTask(task, note) {
      const cid = task.conversationId;
      let run = this.held.get(task.id) || ws.restoreRun(task);
      this.held.delete(task.id);
      if (!run) {
        run = { cid, messages: this.buildMessages(cid), taskId: task.id, sources: [], fileIds: [], turns: 0, cancelled: false, details: null };
      }
      run.cancelled = false; run.hold = false; run.depth = task.depth || 0; run.routineMode = task.approvalMode || null;
      if (note) run.messages.push({ role: 'user', content: note });
      this.store.updateTask(task.id, (t) => { t.status = 'IN_PROGRESS'; t.input = null; t.startedAt = t.startedAt || Date.now(); t.interrupted = false; });
      this.runs.set(cid, run);
      try { return await this.loop(run); } catch (e) { return this.fail(run, e); } finally { if (this.runs.get(cid) === run) this.runs.delete(cid); }
    }
  };
}

// ------------------------------------------------------------------ the workspace

class Workspace extends EventEmitter {
  /**
   * @param {{dir:string, store, brain, config, AgentClass, agentDeps:object, engine:{step,toolMsg,TOOLS}, notify?:Function, fetchText?:Function}} o
   */
  constructor(o) {
    super();
    Object.assign(this, { notify: () => {}, fetchText: null }, o);
    this.file = path.join(this.dir, 'workspace.json');
    this.kDir = path.join(this.dir, 'Workspace', 'knowledge');
    try { fs.mkdirSync(this.kDir, { recursive: true }); } catch { /* */ }
    this.data = { v: 1, profile: { name: '', heard: '', work: '', team: '', onboarded: false }, settings: { proactivity: 'balanced', quietFrom: '22:00', quietTo: '07:00', notifyOn: true },
      bots: [], skills: [], routines: [], teams: [], activity: [], pins: [], dismissed: [], mcp: [] };
    try { const d = JSON.parse(fs.readFileSync(this.file, 'utf8')); this.data = { ...this.data, ...d, profile: { ...this.data.profile, ...d.profile }, settings: { ...this.data.settings, ...d.settings } }; } catch { /* first run */ }
    this.running = new Set();
    this.kCache = new Map();
    if (!Array.isArray(this.data.mcp)) this.data.mcp = [];
    this.mcp = new McpHub({ list: () => this.data.mcp, save: () => this.save(), encrypt: this.encrypt || ((s) => s), decrypt: this.decrypt || ((s) => s), fetch: this.fetchImpl });
    this.mcp.on('change', () => this.changed());
    if (this.mcpAutoConnect !== false) setTimeout(() => this.mcp.connectAll(), 2500).unref?.();
    const extra = [
      fnSchema('search_knowledge', 'Search the knowledge the user gave you (files, notes, links). Use it before answering questions about their material.', { query: { type: 'string', description: 'What to look for' } }, ['query']),
      fnSchema('remember', 'Save a lasting fact about the user or their work for future tasks (preferences, names, decisions). Never passwords or secrets.', { fact: { type: 'string', description: 'One short fact' } }, ['fact']),
      fnSchema('update_self', 'Save your own job description, rules and style (what the user told you your job is). Call it once you have read the scope back and the user agreed, and whenever they change your job.', {
        role: { type: 'string', description: 'Short job title, e.g. "Inbox manager"' }, job: { type: 'string', description: 'What you are responsible for, in 1-3 sentences' },
        do: { type: 'string', description: 'Habits and must-dos' }, never: { type: 'string', description: 'Hard limits' }, sources: { type: 'string', description: 'Sources and apps to use' },
        check_in: { type: 'string', description: 'When to check in or ask first' }, output: { type: 'string', description: 'Output format' }, style: { type: 'string', description: 'Tone and writing style' } }, ['job']),
      fnSchema('create_routine', 'Make this work repeat on a schedule (a routine). Use when the user says things like "every weekday at 8", "every Monday", "every 2 hours", "on the 1st of each month", or "when a task finishes".', {
        name: { type: 'string', description: 'Short name, e.g. "Morning digest"' }, instructions: { type: 'string', description: 'Exactly what to do each run, self-contained' },
        type: { type: 'string', enum: ['once', 'daily', 'weekdays', 'weekly', 'monthly', 'yearly', 'interval', 'task'], description: 'Schedule kind' },
        time: { type: 'string', description: '24h HH:MM, e.g. "08:00"' }, days: { type: 'array', items: { type: 'integer' }, description: 'weekly only: 0=Sun..6=Sat' },
        day_of_month: { type: 'integer' }, date: { type: 'string', description: 'YYYY-MM-DD for once/yearly' }, every_minutes: { type: 'integer', description: 'interval only, >= 15' } }, ['name', 'instructions', 'type']),
      fnSchema('manage_routine', 'List, pause, resume, run now or delete your routines.', {
        action: { type: 'string', enum: ['list', 'pause', 'resume', 'run', 'delete'] }, name: { type: 'string', description: 'Routine name (not needed for list)' } }, ['action']),
      fnSchema('delegate_task', 'Hand part of the work to another bot and wait for its result. Give full context — it cannot see this conversation.', {
        bot: { type: 'string', description: 'The bot\'s name' }, task: { type: 'string', description: 'What it must do' }, context: { type: 'string', description: 'Everything it needs to know' },
        expected_output: { type: 'string', description: 'What to hand back' }, deadline: { type: 'string', description: 'Optional' } }, ['bot', 'task']),
    ];
    const BotAgent = makeBotAgent(this.AgentClass, this, extra);
    // Bots never take Nebula's local shortcuts ("open spotify" etc.): everything goes through their own permissions.
    const botBrain = Object.assign(Object.create(this.brain), { handleLocal: () => null });
    this.agent = new BotAgent({ ...this.agentDeps, store: this.store, brain: botBrain, config: this.config });
    this.store.on('change', () => this.changed());
    this.timer = setInterval(() => this.tick(), 30000);
    if (this.timer.unref) this.timer.unref();
    setTimeout(() => { this.tick(); for (const b of this.data.bots) this.pump(b.id); }, 3000).unref?.();
  }

  close() { clearInterval(this.timer); try { this.mcp.closeAll(); } catch { /* */ } }

  /** Connected-app tools this bot may use (servers it's allowed, tools switched on). */
  mcpFor(bot) { const allow = bot.mcp || {}; return this.mcp.catalogue().filter((x) => allow[x.server.id]); }

  mcpAct(act, a = {}) {
    const h = this.mcp;
    switch (act) {
      case 'add': { const s = h.add(a); this.log(null, 'connect', `Added ${s.name}`); if (a.bot) { const b = this.bot(a.bot); if (b) { b.mcp = { ...(b.mcp || {}), [s.id]: true }; } } return h.connect(s.id).then(() => { this.log(null, 'connect', `${s.name} connected — ${s.tools.length} tools`); return s.id; }, (e) => { this.log(null, 'error', `${s.name}: ${e.message}`); return s.id; }); }
      case 'update': h.update(a.id, a); return h.connect(a.id).then(() => a.id, () => a.id);
      case 'connect': return h.connect(a.id).then((s) => s.tools.length);
      case 'disconnect': h.disconnect(a.id); return true;
      case 'toggle': { const s = h.server(a.id); s.enabled = !s.enabled; if (s.enabled) h.connect(s.id).catch(() => {}); else h.disconnect(s.id); this.save(); return s.enabled; }
      case 'tool': h.toolCfg(a.id, a.tool, a.patch); return true;
      case 'remove': { const s = h.server(a.id); h.remove(a.id); for (const b of this.data.bots) if (b.mcp) delete b.mcp[a.id]; if (s) this.log(null, 'connect', `Removed ${s.name}`); this.save(); return true; }
      case 'secrets': { const s = h.server(a.id); const sec = h.secrets(s); return { env: Object.keys(sec.env), headers: Object.keys(sec.headers) }; }
      default: throw new Error('Unknown action');
    }
  }

  save() {
    clearTimeout(this.saveT);
    this.saveT = setTimeout(() => this.flush(), 200);
    this.changed();
  }
  flush() { try { fs.writeFileSync(this.file + '.tmp', JSON.stringify(this.data)); fs.renameSync(this.file + '.tmp', this.file); } catch { /* */ } }
  changed() { if (this.pushT) return; this.pushT = setTimeout(() => { this.pushT = null; this.emit('change'); }, 250); } // at most 4 updates a second to the window

  // ------------------------------------------------------------ lookups
  bot(bid) { return this.data.bots.find((b) => b.id === bid); }
  botByName(n) { const s = String(n || '').toLowerCase().replace(/^@/, '').trim(); return this.data.bots.find((b) => !b.archived && (b.name.toLowerCase() === s || b.handle === s)) || this.data.bots.find((b) => !b.archived && b.name.toLowerCase().includes(s)); }
  tasks() { return this.store.data.tasks.filter((t) => t.botId); }
  primary() { return this.data.bots.find((b) => b.primary && !b.archived); }

  // ------------------------------------------------------------ activity
  log(bot, kind, text, taskId = null) {
    this.data.activity.unshift({ id: id(), at: Date.now(), botId: bot ? bot.id : null, kind, text: String(text).slice(0, 300), taskId });
    if (this.data.activity.length > 600) this.data.activity.length = 600;
    this.save();
  }

  // ------------------------------------------------------------ profile + settings
  setProfile(p) { Object.assign(this.data.profile, p); this.save(); }
  setSettings(p) { Object.assign(this.data.settings, p); this.save(); }

  // ------------------------------------------------------------ bots
  saveBot(b) {
    const name = String(b.name || '').trim().slice(0, 40) || 'New bot';
    let bot = b.id && this.bot(b.id);
    const fresh = !bot;
    if (!bot) { bot = newBot(); this.data.bots.push(bot); }
    const keep = { id: bot.id, createdAt: bot.createdAt, knowledge: bot.knowledge, memories: bot.memories, alwaysAllow: bot.alwaysAllow };
    Object.assign(bot, b, keep, { name, updatedAt: Date.now() });
    bot.handle = slug(b.handle || name);
    if (this.data.bots.some((x) => x !== bot && x.handle === bot.handle)) bot.handle = `${bot.handle}-${bot.id.slice(-3)}`;
    bot.caps = (bot.caps || []).filter((c) => CAPABILITIES.some((x) => x.id === c));
    if (!AUTONOMY[bot.autonomy]) bot.autonomy = 'supervised';
    if (bot.primary) for (const x of this.data.bots) if (x !== bot) x.primary = false;
    this.log(bot, fresh ? 'bot' : 'edit', fresh ? `${bot.draft ? 'Draft saved' : 'Created'}: ${bot.name}` : `Updated ${bot.name}`);
    this.save();
    if (!bot.draft) this.pump(bot.id);
    return bot;
  }

  installTemplate(key) {
    const t = TEMPLATES.find((x) => x.key === key); if (!t) return null;
    const bot = fromTemplate(t, { primary: !!t.primary && !this.primary() });
    this.data.bots.push(bot);
    if (bot.handle && this.data.bots.some((x) => x !== bot && x.handle === bot.handle)) bot.handle = `${bot.handle}-${bot.id.slice(-3)}`;
    this.log(bot, 'bot', `Added ${bot.name} from the Marketplace`);
    this.save();
    return bot;
  }

  botAct(bid, act, arg) {
    const bot = this.bot(bid); if (!bot) return null;
    switch (act) {
      case 'pause':
        bot.paused = true;
        for (const t of this.tasks()) if (t.botId === bid && t.status === 'IN_PROGRESS') this.agent.hold(t.id);
        this.log(bot, 'paused', `${bot.name} paused`); break;
      case 'resume':
        bot.paused = false; this.log(bot, 'resumed', `${bot.name} resumed`);
        for (const t of this.tasks()) if (t.botId === bid && t.status === 'PAUSED') this.taskAct(t.id, 'resume');
        this.pump(bid); break;
      case 'wake': this.pump(bid); break;
      case 'archive': bot.archived = true; bot.paused = true; this.log(bot, 'edit', `${bot.name} archived`); break;
      case 'unarchive': bot.archived = false; bot.paused = false; this.log(bot, 'edit', `${bot.name} restored`); break;
      case 'primary': for (const x of this.data.bots) x.primary = x.id === bid; this.log(bot, 'edit', `${bot.name} is now your Chief of Staff`); break;
      case 'duplicate': {
        const copy = JSON.parse(JSON.stringify(bot));
        Object.assign(copy, { id: id(), name: `${bot.name} copy`, handle: `${bot.handle}-copy`, primary: false, createdAt: Date.now(), memories: [], alwaysAllow: {} });
        copy.knowledge = bot.knowledge.map((k) => { const nk = { ...k, id: id() }; try { fs.copyFileSync(this.kPath(k.id), this.kPath(nk.id)); } catch { /* */ } return nk; });
        this.data.bots.push(copy); this.log(copy, 'bot', `Duplicated ${bot.name}`); this.save(); return copy;
      }
      case 'delete': {
        for (const t of this.tasks()) if (t.botId === bid && !TERMINAL.has(t.status)) this.agent.cancel(t.id);
        for (const k of bot.knowledge) try { fs.unlinkSync(this.kPath(k.id)); } catch { /* */ }
        if (arg && arg.work) {
          const tids = new Set(this.tasks().filter((t) => t.botId === bid).map((t) => t.id));
          for (const f of [...this.store.data.files]) if (tids.has(f.taskId)) this.store.deleteFile(f.id);
          this.store.data.tasks = this.store.data.tasks.filter((t) => t.botId !== bid);
          this.store.data.conversations = this.store.data.conversations.filter((c) => c.botId !== bid);
          this.store.save();
        }
        this.data.bots = this.data.bots.filter((b) => b.id !== bid);
        this.data.routines = this.data.routines.filter((r) => r.botId !== bid);
        for (const tm of this.data.teams) { tm.members = tm.members.filter((m) => m !== bid); if (tm.coordinator === bid) tm.coordinator = tm.members[0] || null; }
        this.log(null, 'edit', `Deleted ${bot.name}`); break;
      }
      case 'export': return exportBot(bot, this);
      case 'clear-memory': bot.memories = []; this.log(bot, 'edit', `Cleared ${bot.name}'s memory`); break;
      case 'forget-allow': bot.alwaysAllow = {}; this.log(bot, 'edit', `Reset “always allow” for ${bot.name}`); break;
      case 'seen': for (const t of this.tasks()) if (t.botId === bid && t.status === 'FAILED') t.seen = true; this.store.save(); break;
      default: return null;
    }
    this.save();
    return bot;
  }

  importBot(json) {
    const o = typeof json === 'string' ? JSON.parse(json) : json;
    if (!o || o.kind !== 'nebula-rovbot') throw new Error('That isn\'t a Nebula bot file.');
    const bot = newBot({ ...o.bot, mcp: {}, id: id(), primary: false, paused: false, archived: false, knowledge: [], memories: [], alwaysAllow: {}, createdAt: Date.now() });
    this.data.bots.push(bot);
    for (const k of o.knowledge || []) this.addKnowledge(bot.id, { kind: 'note', title: k.title, text: k.text, category: k.category });
    this.log(bot, 'bot', `Imported ${bot.name}`); this.save();
    return bot;
  }

  // ------------------------------------------------------------ knowledge
  kPath(kid) { return path.join(this.kDir, `${kid}.txt`); }
  addKnowledge(bid, k) {
    const bot = this.bot(bid); if (!bot) throw new Error('No such bot');
    let text = String(k.text || ''); let title = String(k.title || '').trim();
    if (k.kind === 'file') {
      if (!TEXT_EXT.test(k.path)) throw new Error(`${path.basename(k.path)}: only text files can be read for now (txt, md, csv, json, html, code…). Paste the text of PDFs and Word files as a note.`);
      const st = fs.statSync(k.path); if (st.size > 5 * 1024 * 1024) throw new Error(`${path.basename(k.path)} is over 5 MB.`);
      text = fs.readFileSync(k.path, 'utf8'); if (/\.html?$/i.test(k.path)) text = stripHtml(text);
      title = title || path.basename(k.path);
    }
    text = text.replace(/\u0000/g, '').slice(0, 2_000_000);
    if (!text.trim()) throw new Error('There was no text in that.');
    const rec = { id: id(), kind: k.kind || 'note', title: title.slice(0, 120) || 'Note', category: String(k.category || 'General').slice(0, 40), chars: text.length, source: k.url || (k.kind === 'file' ? k.path : ''), addedAt: Date.now() };
    fs.writeFileSync(this.kPath(rec.id), text);
    bot.knowledge.unshift(rec); this.kCache.delete(bid);
    this.log(bot, 'knowledge', `Learned “${rec.title}” (${Math.round(rec.chars / 100) / 10}k characters)`);
    this.save();
    return rec;
  }
  async addUrl(bid, url, category) {
    if (!/^https?:\/\//i.test(url)) throw new Error('Links must start with http:// or https://');
    if (!this.fetchText) throw new Error('Reading links isn\'t available here.');
    const html = await this.fetchText(url);
    const title = (/<title[^>]*>([^<]{1,120})/i.exec(html) || [])[1] || url;
    return this.addKnowledge(bid, { kind: 'url', title: stripHtml(title), text: stripHtml(html), url, category });
  }
  knowledgeText(kid) { try { return fs.readFileSync(this.kPath(kid), 'utf8'); } catch { return ''; } }
  removeKnowledge(bid, kid) {
    const bot = this.bot(bid); if (!bot) return;
    const k = bot.knowledge.find((x) => x.id === kid);
    bot.knowledge = bot.knowledge.filter((x) => x.id !== kid); this.kCache.delete(bid);
    try { fs.unlinkSync(this.kPath(kid)); } catch { /* */ }
    if (k) this.log(bot, 'knowledge', `Removed “${k.title}” from knowledge`);
    this.save();
  }
  setKnowledge(bid, kid, patch) { const bot = this.bot(bid); const k = bot && bot.knowledge.find((x) => x.id === kid); if (k) { if (patch.category != null) k.category = String(patch.category).slice(0, 40); if (patch.title) k.title = String(patch.title).slice(0, 120); this.save(); } }
  searchKnowledge(bot, q, n = 4) {
    let chunks = this.kCache.get(bot.id);
    if (!chunks) { chunks = []; for (const k of bot.knowledge) chunk(this.knowledgeText(k.id)).forEach((text) => chunks.push({ title: k.title, text })); this.kCache.set(bot.id, chunks); }
    return retrieve(chunks, q, n);
  }

  // ------------------------------------------------------------ memory
  addMemory(bid, text, by = 'you') {
    const bot = this.bot(bid); if (!bot) return;
    if (bot.memories.some((m) => m.text.toLowerCase() === text.toLowerCase())) return;
    bot.memories.unshift({ id: id(), text, by, at: Date.now() });
    if (bot.memories.length > 200) bot.memories.length = 200;
    this.log(bot, 'memory', `Remembered: ${text}`); this.save();
  }
  memoryAct(bid, act, mid, text) {
    const bot = this.bot(bid); if (!bot) return;
    if (act === 'add' && text) return this.addMemory(bid, String(text).slice(0, 400));
    if (act === 'edit') { const m = bot.memories.find((x) => x.id === mid); if (m) m.text = String(text).slice(0, 400); }
    if (act === 'remove') bot.memories = bot.memories.filter((x) => x.id !== mid);
    if (act === 'toggle') bot.memoryOn = !bot.memoryOn;
    this.save();
  }

  // ------------------------------------------------------------ the prompt
  prompt(bot, conv) {
    const name = this.data.profile.name || this.config.get('userName') || '';
    const now = new Date().toLocaleString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    const I = bot.ins || {};
    const L = (k, v) => (v && String(v).trim() ? `${k}: ${String(v).trim()}\n` : '');
    const task = conv && conv.taskId ? this.store.task(conv.taskId) : null;
    const lastUser = conv ? [...conv.messages].reverse().find((m) => m.role === 'user') : null;
    const hits = lastUser && bot.knowledge.length ? this.searchKnowledge(bot, lastUser.text, 4) : [];
    const skills = (bot.skills || []).map((sid) => this.data.skills.find((s) => s.id === sid)).filter(Boolean);
    const team = task && task.teamId ? this.data.teams.find((t) => t.id === task.teamId) : null;
    const peers = this.delegatable(bot, task);
    const files = this.store.data.files.filter((f) => { const t = f.taskId && this.store.task(f.taskId); return t && t.botId === bot.id; }).slice(0, 10);
    return `You are ${bot.name}${bot.role ? `, ${bot.role}` : ''} — a RovBot in Nebula Workspace (powered by Rovian Intelligence), running on ${name ? `${name}'s` : 'the user\'s'} PC.
${bot.description ? `About you: ${bot.description}\n` : ''}Now: ${now} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
${name ? `The user's name is ${name}.${this.data.profile.work ? ` They work as/in: ${this.data.profile.work}.` : ''}\n` : ''}
## Your job
${L('Responsible for', I.resp)}${L('Do', I.do)}${L('Never', I.never)}${L('How to approach work', I.approach)}${L('How you communicate', I.communication)}${L('Writing style', I.style)}${L('Decision boundaries', I.boundaries)}${L('Priorities', I.priorities)}${L('When to escalate to the user', I.escalation)}${L('Output format', I.output)}${L('Quality bar', I.quality)}${L('Notes', I.notes)}
## Personality
${personalityText(bot.personality) || 'Friendly and clear.'} Personality shapes how you talk, never what you are allowed to do.
${task ? `\n## Current task\n${task.title}${task.description && task.description !== task.title ? `\n${task.description}` : ''}${task.dueAt ? `\nDue: ${new Date(task.dueAt).toLocaleString('en-GB')}` : ''}${task.parentId ? '\nThis was handed to you by another bot. Return a complete result with finish_task.' : ''}\n` : ''}${team ? `\n## Team: ${team.name}\nPurpose: ${team.purpose || '—'}\nHow the team works: ${teamMode(team.mode)}\n${team.instructions ? `Team instructions: ${team.instructions}\n` : ''}${team.coordinator === bot.id ? 'You coordinate this team: decide what each member should do, delegate with delegate_task, check their results, then combine them into one final result.\n' : ''}` : ''}${skills.length ? `\n## Skills you know\n${skills.map((s) => `- ${s.name} (v${s.version}): ${s.instructions}${s.steps && s.steps.length ? ` Steps: ${s.steps.join(' → ')}` : ''}`).join('\n')}\n` : ''}${bot.memories.length && bot.memoryOn !== false ? `\n## What you remember\n${bot.memories.slice(0, 30).map((m) => `- ${m.text}`).join('\n')}\n` : ''}${hits.length ? `\n## From your knowledge (most relevant passages)\n${hits.map((h) => `[${h.title}] ${h.text.replace(/\s+/g, ' ').slice(0, 700)}`).join('\n---\n')}\n` : bot.knowledge.length ? `\nYou have ${bot.knowledge.length} knowledge item(s): ${bot.knowledge.slice(0, 12).map((k) => k.title).join(', ')}. Use search_knowledge to read them.\n` : ''}${peers.length ? `\n## Bots you may hand work to (delegate_task)\n${peers.map((p) => `- ${p.name}: ${p.role}${p.description ? ` — ${p.description}` : ''}`).join('\n')}\n` : ''}${this.mcpFor(bot).length ? `\n## Connected apps you can use\n${[...new Set(this.mcpFor(bot).map((x) => x.server))].map((s) => `- ${s.name}: ${this.mcpFor(bot).filter((x) => x.server === s).length} tools (named mcp__…)${s.info && s.info.instructions ? ` — ${s.info.instructions.replace(/\s+/g, ' ').slice(0, 400)}` : ''}`).join('\n')}\nThese act on the user's real accounts. Results come back from the app itself — report them faithfully.\n` : ''}${bot.setup ? `\n## You were just created — learn your job first\nYou don't have a job yet. In this chat: if the user hasn't said what you're for, ask in one short message, offering 3-4 lettered options that fit your name (A, B, C… plus "something else"). Once they answer, read your scope back in a few bullets (job, sources/apps, limits, when you'll check in) and ask "Shall I go with this?" When they agree, call update_self, then offer to start the first task. Keep it warm and brief.\n` : ''}${files.length ? `\nFiles you made before:\n${files.map((f) => `- ${f.fileName} (id=${f.id})`).join('\n')}\n` : ''}
## Rules
- Do the work with your tools instead of describing it. Plain questions: answer directly.
- Multi-step work: call plan first, then act step by step.
- Files: write substantial, well-structured, professional content and call create_document once with the full spec.
- Research: prefer credible sources, cross-check important claims, keep source URLs, separate facts from your analysis, say what is uncertain. Never invent a source.
- Some actions need the user's approval. Just call the tool — Nebula shows them an approval card. If they deny it, do not try again or work around it.
- Tool results say "verified". Never claim something happened unless a tool verified it. You cannot send email, post online, buy things, join meetings or use apps that aren't in your tools — say so plainly.
- If you can't complete the task, call finish_task with outcome "failed" and explain why.
- When you used tools, end with finish_task(outcome, summary). The summary is 1-2 plain sentences; put long results (reports, tables, lists with sources) in details using Markdown.
- Save lasting facts about the user with remember. Never store passwords or secrets.
- Ask with ask_user only when you can't reasonably decide yourself.
- If the user wants something repeated on a schedule, call create_routine (it runs while Nebula is running on this PC). If they change your job or rules, call update_self.`;
  }

  delegatable(bot, task) {
    if (!(bot.caps || []).includes('delegate')) return [];
    const team = task && task.teamId ? this.data.teams.find((t) => t.id === task.teamId) : null;
    const ids = new Set(team ? team.members : (bot.collab && bot.collab.allowed && bot.collab.allowed.length ? bot.collab.allowed : this.data.bots.map((b) => b.id)));
    return this.data.bots.filter((b) => b.id !== bot.id && ids.has(b.id) && !b.archived && !b.draft);
  }

  // ------------------------------------------------------------ approval cards
  card(bot, name, a, run) {
    // "Why" is only what the bot said during this request — never an older reply from earlier in the chat.
    const whyText = (r) => { const ms = r.messages || []; let i = ms.length - 1; const out = []; while (i >= 0 && ms[i].role !== 'user') { if (ms[i].role === 'assistant' && ms[i].content) out.unshift(ms[i].content); i--; } return out.join(' ') || ''; };
    if (name.startsWith('mcp__')) {
      const x = this.mcp.find(name); const risk = x ? x.risk : 'medium';
      const lastText = whyText(run);
      const where = x ? (x.server.type === 'http' ? (() => { try { return new URL(x.server.url).host; } catch { return x.server.url; } })() : `a program on this PC (${x.server.command})`) : '';
      return { tool: name, title: x ? `${x.server.name}: ${x.tool.title || x.tool.name}` : name, why: lastText.replace(/\s+/g, ' ').trim().slice(0, 240), app: x ? x.server.name : 'Connected app', destination: where,
        content: JSON.stringify(a, null, 2).slice(0, 900), consequence: x ? (x.tool.description || '').slice(0, 220) : '', reversible: risk !== 'high',
        shares: `The details above are sent to ${where || 'that app'}.`, risk: risk === 'none' ? 'low' : risk, always: risk !== 'high', args: a, bot: bot.id };
    }
    const info = TOOL_INFO[name] || { label: name, risk: 'medium' };
    const lastText = whyText(run);
    const t = run.taskId ? this.store.task(run.taskId) : null;
    const dest = { open_app: a.name, open_url: a.url, play_music: a.query, update_document: (this.store.file(a.file_id) || {}).fileName, create_document: a.spec && a.spec.title, delegate_task: a.bot, set_timer: a.seconds ? `${Math.round(a.seconds / 60) || 1} min` : '', autoclicker: a.action }[name] || '';
    let content = '';
    if (name === 'copy_to_clipboard') content = String(a.text || '').slice(0, 600);
    else if (name === 'delegate_task') content = `${a.task}${a.context ? `\n\nContext: ${a.context}` : ''}`.slice(0, 800);
    else if (name === 'create_document' || name === 'update_document') content = a.spec ? `${(a.spec.type || '').toUpperCase()} · “${a.spec.title || ''}”` : '';
    return {
      tool: name, title: `${info.label || name}${dest ? `: ${String(dest).slice(0, 80)}` : ''}`, why: (lastText.replace(/\s+/g, ' ').trim().slice(0, 240)) || (t ? `Part of “${t.title}”` : ''),
      app: info.app || 'Nebula', destination: dest ? String(dest).slice(0, 200) : '', content, consequence: info.consequence || '', reversible: info.reversible !== false,
      shares: name === 'open_url' ? 'The address is sent to that website.' : name === 'play_music' ? 'The search is sent to Spotify or YouTube Music.' : name === 'delegate_task' ? 'The task and context are shared with that bot.' : 'Nothing leaves this PC.',
      risk: info.risk, always: info.risk !== 'high', args: a, bot: bot.id,
    };
  }

  approvalDecided(bot, run, ap, choice, edited) {
    if (!bot) return;
    const yes = /^(approve|always)/i.test(String(choice));
    const risk = ap.name.startsWith('mcp__') ? this.mcp.find(ap.name)?.risk : TOOL_INFO[ap.name]?.risk;
    if (/^always/i.test(String(choice)) && risk && risk !== 'high') { bot.alwaysAllow[ap.name] = true; }
    this.log(bot, yes ? 'approved' : 'denied', `${yes ? (edited ? 'Approved (edited)' : /^always/i.test(choice) ? 'Always allowed' : 'Approved') : 'Denied'}: ${ap.title || ap.name}`, run.taskId);
  }

  paused(bot, run, input) {
    if (!bot) return;
    const t = this.store.task(run.taskId);
    if (input.kind === 'approval') {
      this.log(bot, 'approval', `Asked to: ${input.card.title}`, run.taskId);
      if (bot.notify?.approval !== false) this.notify(`${bot.name} needs your OK`, input.card.title, { view: 'approvals', taskId: run.taskId }, { botId: bot.id, kind: 'need' });
    } else {
      this.log(bot, 'waiting', `Asked you: ${input.question}`, run.taskId);
      if (bot.notify?.approval !== false) this.notify(`${bot.name} has a question`, input.question, { view: 'task', taskId: run.taskId }, { botId: bot.id, kind: 'ask' });
    }
    void t;
  }

  finished(bot, taskId) {
    const t = this.store.task(taskId); if (!t) return;
    this.store.updateTask(taskId, (x) => { x.finishedAt = Date.now(); delete x.checkpoint; });
    if (bot) {
      this.log(bot, t.status === 'COMPLETED' ? 'done' : t.status === 'FAILED' ? 'failed' : 'cancelled', `${t.status === 'COMPLETED' ? 'Finished' : t.status === 'FAILED' ? 'Failed' : 'Stopped'}: ${t.title}`, taskId);
      const want = t.status === 'COMPLETED' ? bot.notify?.done !== false : t.status === 'FAILED' ? bot.notify?.failed !== false : false;
      if (want && !t.parentId) this.notify(`${bot.name} ${t.status === 'COMPLETED' ? 'finished' : 'couldn\'t finish'}`, t.summary || t.title, { view: 'task', taskId }, { botId: bot.id, kind: t.status === 'COMPLETED' ? 'done' : 'fail' });
      // Routines triggered by "a task finished" (never by a routine's own task, so they can't loop).
      if (!t.routineId && !t.parentId) for (const r of this.data.routines) if (r.active && r.schedule?.type === 'task' && (!r.schedule.bot || r.schedule.bot === bot.id) && (!r.schedule.when || r.schedule.when === t.status)) this.runRoutine(r.id, 'task');
    }
    const r = t.routineId && this.data.routines.find((x) => x.id === t.routineId);
    if (r) { const h = r.history.find((x) => x.taskId === taskId); if (h) { h.status = t.status; h.end = Date.now(); } }
    this.save();
    setTimeout(() => { if (bot) this.pump(bot.id); }, 50);
  }

  // ------------------------------------------------------------ checkpoints
  checkpoint(run) {
    if (!run.taskId) return;
    const t = this.store.task(run.taskId); if (!t) return;
    const msgs = run.messages.slice(1).slice(-60).map((m) => ({ ...m, content: typeof m.content === 'string' ? m.content.slice(0, 6000) : m.content }));
    t.checkpoint = { messages: msgs, sources: run.sources.slice(0, 20), fileIds: run.fileIds, turns: run.turns, pendingCallId: run.pendingCallId || null, pendingApproval: run.pendingApproval || null, at: Date.now() };
    this.store.save();
  }

  restoreRun(t) {
    if (!t || !t.checkpoint) return null;
    const cp = t.checkpoint;
    const sys = { role: 'system', content: this.agent.systemPrompt(t.conversationId) };
    // Drop a dangling assistant tool-call message with no answers (the app closed mid-step).
    const msgs = [...cp.messages];
    const last = msgs[msgs.length - 1];
    if (last && last.role === 'assistant' && last.tool_calls && last.tool_calls.length && !cp.pendingCallId && !cp.pendingApproval) msgs.pop();
    const run = { cid: t.conversationId, messages: [sys, ...msgs], taskId: t.id, sources: cp.sources || [], fileIds: cp.fileIds || [], turns: Math.min(cp.turns || 0, 20), cancelled: false, details: null, depth: t.depth || 0, pendingCallId: cp.pendingCallId, pendingApproval: cp.pendingApproval };
    if (t.status === 'REQUIRES_INPUT') this.agent.paused.set(t.id, run);
    return run;
  }

  // ------------------------------------------------------------ tasks + the queue
  /** Create a task for a bot. It runs as soon as the bot is free (one task at a time per bot). */
  assign(o) {
    const bot = this.bot(o.botId); if (!bot) throw new Error('Pick a bot');
    const text = String(o.description || o.title || '').trim(); if (!text) throw new Error('Describe the task');
    const title = String(o.title || text).replace(/\s+/g, ' ').trim().slice(0, 80);
    const conv = this.store.newConversation({ botId: bot.id, title, kind: 'task' });
    this.store.addMessage(conv.id, { role: 'user', text });
    const task = this.store.createTask({ title, description: text, botId: bot.id, conversationId: conv.id, status: 'QUEUED', priority: PRIORITY[o.priority] != null ? o.priority : 'normal',
      source: o.source || 'you', dueAt: o.dueAt || null, parentId: o.parentId || null, routineId: o.routineId || null, teamId: o.teamId || null, depth: o.depth || 0, approvalMode: o.approvalMode || null, kind: 'general' });
    conv.taskId = task.id; this.store.save();
    if (task.parentId) { const p = this.store.task(task.parentId); if (p) this.store.updateTask(p.id, (x) => { x.children = [...(x.children || []), task.id]; }); }
    this.log(bot, 'task', `${o.source === 'routine' ? 'Routine started' : o.source === 'bot' ? 'Delegated' : o.source === 'team' ? 'Team task' : 'New task'}: ${title}`, task.id);
    if (o.immediate) this.start(task); else this.pump(bot.id);
    return task;
  }

  pump(bid) {
    const bot = this.bot(bid); if (!bot || bot.paused || bot.archived || bot.draft) return;
    const mine = this.tasks().filter((t) => t.botId === bid);
    if (mine.some((t) => this.running.has(t.id) && !t.parentId)) return;
    const next = mine.filter((t) => t.status === 'QUEUED').sort((a, b) => (PRIORITY[a.priority] ?? 2) - (PRIORITY[b.priority] ?? 2) || a.createdAt - b.createdAt)[0];
    if (next) this.start(next);
  }

  start(task, note) {
    if (this.running.has(task.id)) return;
    this.running.add(task.id);
    this.agent.runTask(task, note).catch(() => {}).finally(() => { this.running.delete(task.id); this.changed(); this.pump(task.botId); });
  }

  async whenDone(taskId, timeoutMs = 30 * 60000) {
    const t0 = Date.now();
    for (;;) {
      const t = this.store.task(taskId);
      if (!t || TERMINAL.has(t.status)) return t;
      if (Date.now() - t0 > timeoutMs) return t;
      await new Promise((r) => setTimeout(r, 400));
    }
  }

  async delegate(bot, a, run) {
    const { step } = this.engine;
    const parent = run.taskId && this.store.task(run.taskId);
    const allowed = this.delegatable(bot, parent);
    const target = this.botByName(a.bot);
    if (!target || !allowed.includes(target)) return { content: JSON.stringify({ ok: false, result: `You can't hand work to "${a.bot}". You may use: ${allowed.map((b) => b.name).join(', ') || 'nobody'}.` }), step: step(`Hand work to ${a.bot}`, false, false, 'Not allowed') };
    if ((run.depth || 0) >= 2) return { content: JSON.stringify({ ok: false, result: 'Delegation limit reached (2 levels). Do this part yourself.' }), step: step(`Hand work to ${target.name}`, false, false, 'Too many levels') };
    run.delegations = (run.delegations || 0) + 1;
    if (run.delegations > 6) return { content: JSON.stringify({ ok: false, result: 'You already delegated 6 times in this task. Finish with what you have.' }) };
    if (target.paused) return { content: JSON.stringify({ ok: false, result: `${target.name} is paused.` }), step: step(`Hand work to ${target.name}`, false, false, 'Paused') };
    const desc = `${a.task}${a.context ? `\n\nContext: ${a.context}` : ''}${a.expected_output ? `\n\nHand back: ${a.expected_output}` : ''}${a.deadline ? `\n\nDeadline: ${a.deadline}` : ''}\n\n(Delegated by ${bot.name}${parent ? ` for “${parent.title}”` : ''}.)`;
    const child = this.assign({ botId: target.id, title: String(a.task).slice(0, 80), description: desc, source: 'bot', parentId: parent ? parent.id : null, depth: (run.depth || 0) + 1, teamId: parent ? parent.teamId : null, immediate: true });
    this.agent.setActivity(run.cid, `Waiting for ${target.name}…`);
    const done = await this.whenDone(child.id);
    const conv = done && this.store.conversation(done.conversationId);
    const answer = conv ? [...conv.messages].reverse().find((m) => m.role === 'assistant')?.text : '';
    const files = (done?.fileIds || []).map((fid) => this.store.file(fid)).filter(Boolean);
    for (const f of files) if (!run.fileIds.includes(f.id)) run.fileIds.push(f.id);
    const ok = done && done.status === 'COMPLETED';
    return { content: JSON.stringify({ ok, verified: ok, status: done ? done.status : 'UNKNOWN', result: (answer || done?.summary || '').slice(0, 9000), files: files.map((f) => `${f.fileName} id=${f.id}`) }),
      step: step(`${target.name}: ${String(a.task).slice(0, 50)}`, ok, ok, done ? done.summary?.slice(0, 120) || done.status : 'No result'), fileIds: files.map((f) => f.id) };
  }

  taskAct(tid, act, arg) {
    const t = this.store.task(tid); if (!t || !t.botId) return null;
    const bot = this.bot(t.botId);
    switch (act) {
      case 'pause':
        if (t.status === 'QUEUED') { this.store.updateTask(tid, (x) => { x.status = 'PAUSED'; }); this.log(bot, 'paused', `Paused: ${t.title}`, tid); }
        else if (t.status === 'IN_PROGRESS') this.agent.hold(tid);
        break;
      case 'resume':
        if (t.status === 'PAUSED') {
          if (this.agent.held.has(tid) || t.checkpoint) { if (bot && !bot.paused) this.start(t, t.interrupted ? 'Nebula was restarted. Carry on from where you left off.' : null); else this.store.updateTask(tid, (x) => { x.status = 'QUEUED'; }); }
          else { this.store.updateTask(tid, (x) => { x.status = 'QUEUED'; }); this.pump(t.botId); }
          this.log(bot, 'resumed', `Resumed: ${t.title}`, tid);
        }
        break;
      case 'stop': case 'cancel':
        for (const c of t.children || []) { const ct = this.store.task(c); if (ct && !TERMINAL.has(ct.status)) this.taskAct(c, 'cancel'); }
        this.agent.cancel(tid); this.finished(bot, tid); break;
      case 'retry': {
        if (!['FAILED', 'CANCELLED'].includes(t.status)) break;
        const note = `The previous attempt ended as ${t.status}: ${t.summary || ''}. Try again — pick up from what already worked, and fix what failed.`;
        this.store.updateTask(tid, (x) => { x.status = 'QUEUED'; x.retries = (x.retries || 0) + 1; x.summary = ''; x.finishedAt = null; x.retryNote = note; });
        this.log(bot, 'task', `Retrying: ${t.title}`, tid);
        if (bot && !bot.paused) { this.start(t, note); } break;
      }
      case 'priority': if (PRIORITY[arg] != null) { this.store.updateTask(tid, (x) => { x.priority = arg; }); } break;
      case 'due': this.store.updateTask(tid, (x) => { x.dueAt = arg ? Number(arg) : null; }); break;
      case 'reassign': {
        const nb = this.bot(arg); if (!nb || this.running.has(tid) || t.status === 'IN_PROGRESS') break;
        this.store.updateTask(tid, (x) => { x.botId = nb.id; delete x.checkpoint; if (!TERMINAL.has(x.status) && x.status !== 'REQUIRES_INPUT') x.status = 'QUEUED'; });
        const conv = this.store.conversation(t.conversationId); if (conv) conv.botId = nb.id;
        this.agent.paused.delete(tid); this.agent.held.delete(tid);
        this.log(nb, 'task', `Reassigned to ${nb.name}: ${t.title}`, tid); this.pump(nb.id); break;
      }
      case 'steer': {
        const text = String(arg || '').trim(); if (!text) break;
        this.store.addMessage(t.conversationId, { role: 'user', text, taskId: tid });
        const run = this.agent.runs.get(t.conversationId) || this.agent.held.get(tid);
        if (run && run.taskId === tid) run.messages.push({ role: 'user', content: `[New instruction from the user while you work] ${text}` });
        else if (t.checkpoint) t.checkpoint.messages.push({ role: 'user', content: `[New instruction from the user] ${text}` });
        else this.store.updateTask(tid, (x) => { x.description = `${x.description || x.title}\n\nAlso: ${text}`; });
        this.log(bot, 'steer', `You added: ${text.slice(0, 120)}`, tid);
        break;
      }
      case 'title': this.store.updateTask(tid, (x) => { x.title = String(arg || x.title).slice(0, 80); }); break;
      case 'delete': if (TERMINAL.has(t.status) || t.status === 'QUEUED' || t.status === 'PAUSED') { this.agent.held.delete(tid); this.store.deleteTask(tid); } break;
      case 'seen': this.store.updateTask(tid, (x) => { x.seen = true; }); break;
      default: return null;
    }
    this.save();
    return t;
  }

  answer(tid, choices, edited) {
    const t = this.store.task(tid); if (!t) return;
    if (this.running.has(tid)) return;
    this.running.add(tid);
    this.agent.answer(tid, choices, true, edited).catch(() => {}).finally(() => { this.running.delete(tid); this.changed(); this.pump(t.botId); });
  }

  // ------------------------------------------------------------ chat-first (Grok-style) helpers
  /** A visible event line in the bot's chat (not sent to the AI). */
  event(cid, kind, text, extra = {}) {
    if (!cid || !this.store.conversation(cid)) return;
    this.store.addMessage(cid, { role: 'event', kind, text: String(text).slice(0, 300), ...extra });
  }

  /** The one ongoing chat with a bot (Grok-style): the newest non-task conversation, or a new one. */
  mainConv(bid, create = true) {
    const cs = this.store.data.conversations.filter((c) => c.botId === bid && !c.taskId && !c.archived);
    let c = cs.find((x) => x.main) || cs.sort((a, b) => b.updatedAt - a.updatedAt)[0];
    if (!c && create) { const b = this.bot(bid); c = this.store.newConversation({ botId: bid, main: true, title: b ? b.name : 'Chat' }); }
    if (c && !c.main) { c.main = true; this.store.save(); }
    return c;
  }

  /** The 3-second create: shape, colour, name. The bot then learns its job by chatting. */
  quickCreate(o) {
    const t = o.template && TEMPLATES.find((x) => x.key === o.template);
    const bot = t ? this.installTemplate(t.key) : this.saveBot({ name: o.name, shape: o.shape | 0, color: o.color || '#7A3CFF', role: '', caps: ['web', 'files', 'delegate'], autonomy: 'supervised', setup: true,
      ins: { resp: '', do: '', never: '', approach: '', communication: '', style: '', boundaries: '', priorities: '', escalation: 'If something is unclear or risky, ask before going on.', output: '', quality: '', notes: '' } });
    if (t && (o.name || o.color || o.shape != null)) Object.assign(bot, { name: String(o.name || bot.name).slice(0, 40), color: o.color || bot.color, shape: o.shape != null ? o.shape | 0 : bot.shape });
    if (!this.primary()) bot.primary = true;
    const c = this.mainConv(bot.id);
    const name = this.data.profile.name ? `, ${this.data.profile.name}` : '';
    this.store.addMessage(c.id, { role: 'assistant', text: t ? `Hi${name}! I'm ${bot.name}. ${t.desc}\n\nWhat should I start with? For example: ${(t.samples || []).slice(0, 2).map((x) => `“${x}”`).join(' or ')}.`
      : `Hi${name}! I'm ${bot.name}. 👋\n\nWhat's my job? Tell me what you'd like me to take off your plate — the result you want, the apps or sources to use, any limits, and when I should check in with you.` });
    this.save();
    return { id: bot.id, cid: c.id };
  }

  markRead(bid) {
    const now = Date.now(); let changed = false;
    for (const c of this.store.data.conversations) if (c.botId === bid && (c.readAt || 0) < c.updatedAt) { c.readAt = now; changed = true; }
    for (const t of this.tasks()) if (t.botId === bid && t.status === 'FAILED' && !t.seen) { t.seen = true; changed = true; }
    if (changed) { this.store.save(); this.changed(); }
  }

  updateSelf(bot, a, run) {
    const { step } = this.engine;
    const I = bot.ins || (bot.ins = {});
    if (a.role) bot.role = String(a.role).slice(0, 60);
    if (a.job) I.resp = String(a.job).slice(0, 1200);
    for (const [k, f] of [['do', 'do'], ['never', 'never'], ['style', 'style'], ['output', 'output'], ['check_in', 'escalation']]) if (a[k]) I[f] = String(a[k]).slice(0, 1200);
    if (a.sources) I.approach = `Sources and apps: ${String(a.sources).slice(0, 1000)}`;
    if (!bot.description && a.job) bot.description = String(a.job).split(/(?<=[.!?])\s/)[0].slice(0, 160);
    bot.setup = false; bot.updatedAt = Date.now();
    this.log(bot, 'edit', `${bot.name} saved its job: ${String(a.role || a.job).slice(0, 80)}`);
    this.event(run.cid, 'job', `Saved its job${a.role ? ` as ${a.role}` : ''}`);
    this.save();
    return { content: JSON.stringify({ ok: true, verified: true, result: 'Saved. Your job and rules are now part of your instructions.' }), step: run.taskId ? step('Saved its job', true, true, String(a.role || '')) : null };
  }

  routineFromChat(bot, a, run) {
    const { step } = this.engine;
    try {
      const sched = { type: a.type, time: a.time || '08:00', days: Array.isArray(a.days) ? a.days.map((d) => d | 0) : undefined, dom: a.day_of_month || undefined, date: a.date || undefined, every: a.every_minutes || undefined, bot: a.type === 'task' ? bot.id : undefined };
      const id = this.saveRoutine({ name: a.name, botId: bot.id, instructions: a.instructions, schedule: sched }).id;
      const r = this.data.routines.find((x) => x.id === id);
      const when = describeSchedule(r.schedule);
      this.event(run.cid, 'routine', `Created routine “${r.name}” · ${when}`, { routineId: id });
      return { content: JSON.stringify({ ok: true, verified: true, result: `Routine “${r.name}” created: ${when}.${r.nextRun ? ` First run ${new Date(r.nextRun).toLocaleString('en-GB', { weekday: 'short', hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })}.` : ''} It runs while Nebula is running on this PC.` }), step: run.taskId ? step(`Created routine “${r.name}”`, true, true, when) : null };
    } catch (e) { return { content: JSON.stringify({ ok: false, result: e.message }) }; }
  }

  manageRoutineFromChat(bot, a, run) {
    const mine = this.data.routines.filter((r) => r.botId === bot.id);
    if (a.action === 'list') return { content: JSON.stringify({ ok: true, routines: mine.map((r) => ({ name: r.name, when: describeSchedule(r.schedule), active: r.active, next: r.nextRun ? new Date(r.nextRun).toISOString() : null })) }) };
    const q = String(a.name || '').toLowerCase();
    const r = mine.find((x) => x.name.toLowerCase() === q) || mine.find((x) => x.name.toLowerCase().includes(q));
    if (!r) return { content: JSON.stringify({ ok: false, result: `No routine called "${a.name}". You have: ${mine.map((x) => x.name).join(', ') || 'none'}.` }) };
    if (a.action === 'pause' && r.active) this.routineAct(r.id, 'toggle');
    if (a.action === 'resume' && !r.active) this.routineAct(r.id, 'toggle');
    if (a.action === 'run') this.runRoutine(r.id, 'manual');
    if (a.action === 'delete') this.routineAct(r.id, 'delete');
    const verb = { pause: 'Paused', resume: 'Resumed', run: 'Started', delete: 'Deleted' }[a.action];
    this.event(run.cid, 'routine', `${verb} routine “${r.name}”`);
    return { content: JSON.stringify({ ok: true, verified: true, result: `${verb} “${r.name}”.` }) };
  }

  // ------------------------------------------------------------ chat
  chat(bid, cid, text, replyTo) {
    const bot = this.bot(bid); if (!bot) throw new Error('No such bot');
    let conv = cid && this.store.conversation(cid);
    if (!conv) conv = this.store.newConversation({ botId: bid });
    if (bot.paused) { this.store.addMessage(conv.id, { role: 'user', text }); this.store.addMessage(conv.id, { role: 'assistant', text: `${bot.name} is paused. Resume it to continue.` }); return conv.id; }
    this.agent.submit(conv.id, text).catch(() => {});
    // submit() adds your message synchronously; tag it with what it replies to.
    const last = conv.messages[conv.messages.length - 1];
    if (replyTo && last && last.role === 'user') { last.replyTo = String(replyTo).slice(0, 40); this.store.save(); }
    return conv.id;
  }

  // ------------------------------------------------------------ skills
  saveSkill(s, applyAll = true) {
    let sk = s.id && this.data.skills.find((x) => x.id === s.id);
    const clean = { name: String(s.name || 'Skill').slice(0, 60), description: String(s.description || '').slice(0, 300), category: String(s.category || 'General').slice(0, 30),
      instructions: String(s.instructions || '').slice(0, 4000), steps: (s.steps || []).map(String).filter(Boolean).slice(0, 20), tools: (s.tools || []).filter((x) => TOOL_INFO[x]),
      inputs: String(s.inputs || '').slice(0, 300), outputs: String(s.outputs || '').slice(0, 300), failure: String(s.failure || '').slice(0, 300), examples: String(s.examples || '').slice(0, 1000), sharing: s.sharing || 'private' };
    if (/(password|passcode|api[ _-]?key|secret|private key)\s*[:=]\s*\S+/i.test(clean.instructions + clean.examples)) throw new Error('Skills must not contain passwords, keys or secrets.');
    if (!sk) { sk = { id: id(), owner: 'you', version: 1, versions: [], createdAt: Date.now(), ...clean }; this.data.skills.push(sk); this.log(null, 'skill', `New skill: ${sk.name}`); }
    else {
      sk.versions.unshift({ version: sk.version, at: Date.now(), snap: JSON.stringify({ ...sk, versions: undefined }) }); sk.versions = sk.versions.slice(0, 20);
      if (applyAll) { Object.assign(sk, clean); sk.version += 1; }
      else { const copy = { ...JSON.parse(JSON.stringify(sk)), ...clean, id: id(), name: `${clean.name} (v${sk.version + 1})`, version: 1, versions: [] }; this.data.skills.push(copy); sk = copy; }
      this.log(null, 'skill', `Updated skill: ${sk.name}`);
    }
    sk.updatedAt = Date.now();
    this.save(); return sk;
  }
  skillAct(sid, act, arg) {
    const sk = this.data.skills.find((x) => x.id === sid); if (!sk) return null;
    if (act === 'delete') { this.data.skills = this.data.skills.filter((x) => x.id !== sid); for (const b of this.data.bots) b.skills = (b.skills || []).filter((x) => x !== sid); }
    if (act === 'duplicate') { const c = { ...JSON.parse(JSON.stringify(sk)), id: id(), name: `${sk.name} copy`, version: 1, versions: [] }; this.data.skills.push(c); }
    if (act === 'rollback') { const v = sk.versions.find((x) => x.version === arg); if (v) { const snap = JSON.parse(v.snap); sk.versions.unshift({ version: sk.version, at: Date.now(), snap: JSON.stringify({ ...sk, versions: undefined }) }); Object.assign(sk, snap, { id: sk.id, versions: sk.versions, version: sk.version + 1 }); } }
    if (act === 'assign') { const b = this.bot(arg); if (b && !b.skills.includes(sid)) b.skills.push(sid); }
    if (act === 'unassign') { const b = this.bot(arg); if (b) b.skills = b.skills.filter((x) => x !== sid); }
    if (act === 'run') { const b = this.bot(arg.botId); if (!b) throw new Error('Pick a bot'); if (!b.skills.includes(sid)) b.skills.push(sid); this.save(); return this.assign({ botId: b.id, title: `${sk.name}${arg.input ? `: ${arg.input}` : ''}`, description: `Use your skill “${sk.name}”.${arg.input ? `\nInput: ${arg.input}` : ''}${arg.test ? '\n(This is a test run of the skill — report anything in the skill that was unclear.)' : ''}`, source: 'skill' }); }
    if (act === 'export') return { kind: 'nebula-skill', v: 1, skill: { ...sk, versions: undefined, id: undefined } };
    this.save(); return sk;
  }
  importSkill(o) { if (!o || o.kind !== 'nebula-skill') throw new Error('That isn\'t a Nebula skill file.'); return this.saveSkill({ ...o.skill, id: null }); }
  installSkillTemplate(i) { const t = SKILL_TEMPLATES[i]; if (!t) return null; return this.saveSkill({ name: t[0], category: t[1], description: t[2], instructions: t[2], tools: t[3] }); }

  /** "Create with AI": turns a description into a skill draft (the user reviews it before saving). */
  async draftSkill(desc) {
    const sys = 'You design reusable workflows ("skills") for AI assistant bots. Reply with ONLY a JSON object: {"name":"","description":"","category":"","instructions":"","steps":[""],"tools":[""],"inputs":"","outputs":"","failure":"","limitations":""}. tools may only contain: ' + Object.keys(TOOL_INFO).filter((k) => TOOL_INFO[k].risk !== undefined && !['plan', 'ask_user', 'finish_task'].includes(k)).join(', ') + '. Never include passwords or secrets.';
    const m = await this.brain.agentChat([{ role: 'system', content: sys }, { role: 'user', content: String(desc).slice(0, 3000) }], undefined);
    return parseJson(m.content);
  }
  /** Step 3: "Describe how you want this Bot to behave" → structured instructions the user can edit. */
  async draftInstructions(desc, role) {
    const sys = 'Turn the user\'s description of an AI assistant bot into structured instructions. Reply with ONLY a JSON object with these string fields (empty string if not mentioned, never invent policies the user did not imply): resp (what it is responsible for), do, never, approach, communication, style, boundaries (decisions it may make alone), priorities, escalation (when to ask the user), output (formats), quality. Keep each field 1-3 short sentences.';
    const m = await this.brain.agentChat([{ role: 'system', content: sys }, { role: 'user', content: `Role: ${role || '—'}\n\n${String(desc).slice(0, 4000)}` }], undefined);
    return parseJson(m.content);
  }

  // ------------------------------------------------------------ routines
  saveRoutine(r) {
    let ro = r.id && this.data.routines.find((x) => x.id === r.id);
    const s = r.schedule || { type: 'daily', time: '08:00' };
    if (s.type === 'interval') s.every = Math.max(15, s.every | 0 || 60);
    const clean = { name: String(r.name || 'Routine').slice(0, 60), botId: r.botId, instructions: String(r.instructions || '').slice(0, 4000), schedule: s, active: r.active !== false,
      approval: r.approval === 'always' ? 'always' : 'bot', notify: r.notify !== false, output: String(r.output || '').slice(0, 200), failure: r.failure === 'retry' ? 'retry' : 'stop' };
    if (!this.bot(clean.botId)) throw new Error('Pick a bot for this routine');
    if (!clean.instructions.trim()) throw new Error('Say what the routine should do');
    if (!ro) { ro = { id: id(), history: [], createdAt: Date.now(), lastRun: 0, ...clean }; this.data.routines.push(ro); this.log(this.bot(clean.botId), 'routine', `New routine: ${ro.name} (${describeSchedule(s)})`); }
    else { Object.assign(ro, clean); this.log(this.bot(clean.botId), 'routine', `Updated routine: ${ro.name}`); }
    ro.nextRun = ro.active ? nextRun(ro.schedule, Date.now(), ro.lastRun) : null;
    this.save(); return ro;
  }
  routineAct(rid, act) {
    const r = this.data.routines.find((x) => x.id === rid); if (!r) return null;
    if (act === 'run') return this.runRoutine(rid, 'manual');
    if (act === 'toggle') { r.active = !r.active; r.nextRun = r.active ? nextRun(r.schedule, Date.now(), r.lastRun) : null; }
    if (act === 'delete') this.data.routines = this.data.routines.filter((x) => x.id !== rid);
    if (act === 'duplicate') { const c = { ...JSON.parse(JSON.stringify(r)), id: id(), name: `${r.name} copy`, history: [], active: false, nextRun: null, lastRun: 0 }; this.data.routines.push(c); }
    this.save(); return r;
  }
  runRoutine(rid, trigger) {
    const r = this.data.routines.find((x) => x.id === rid); if (!r) return null;
    const bot = this.bot(r.botId); if (!bot) return null;
    const busy = r.history.find((h) => !h.status || !TERMINAL.has(this.store.task(h.taskId)?.status));
    if (busy && this.store.task(busy.taskId)) { this.log(bot, 'routine', `Skipped “${r.name}” — the last run hasn't finished`); return null; }
    const today = r.history.filter((h) => Date.now() - h.at < 86400000).length;
    if (today >= 48) { this.log(bot, 'routine', `“${r.name}” hit its limit of 48 runs a day`); r.active = false; this.save(); return null; }
    const task = this.assign({ botId: bot.id, title: `${r.name} — ${new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}`, description: `${r.instructions}${r.output ? `\n\nPut the result: ${r.output}` : ''}\n\n(Routine “${r.name}”, ${describeSchedule(r.schedule)}.)`, source: 'routine', routineId: r.id, approvalMode: r.approval === 'always' ? 'always' : null, priority: 'normal' });
    r.lastRun = Date.now();
    r.history.unshift({ taskId: task.id, at: Date.now(), trigger }); r.history = r.history.slice(0, 100);
    r.nextRun = r.active ? nextRun(r.schedule, Date.now(), r.lastRun) : null;
    this.save(); return task;
  }
  tick() {
    const now = Date.now();
    for (const r of this.data.routines) {
      if (!r.active || !r.schedule || r.schedule.type === 'task' || r.schedule.type === 'manual') continue;
      if (r.nextRun == null) r.nextRun = nextRun(r.schedule, now - 1, r.lastRun);
      if (r.nextRun && r.nextRun <= now) {
        // Missed while the PC was off by more than 6 hours? Skip it and log, rather than running stale work.
        if (now - r.nextRun > 6 * 3600000) { this.log(this.bot(r.botId), 'routine', `Missed “${r.name}” (Nebula wasn't running)`); r.history.unshift({ taskId: null, at: r.nextRun, trigger: 'schedule', status: 'MISSED' }); r.lastRun = r.nextRun; r.nextRun = nextRun(r.schedule, now, r.lastRun); this.save(); continue; }
        this.runRoutine(r.id, 'schedule');
      }
    }
  }

  // ------------------------------------------------------------ teams
  saveTeam(t) {
    let tm = t.id && this.data.teams.find((x) => x.id === t.id);
    const members = (t.members || []).filter((m) => this.bot(m));
    const clean = { name: String(t.name || 'Team').slice(0, 50), purpose: String(t.purpose || '').slice(0, 400), members, coordinator: members.includes(t.coordinator) ? t.coordinator : members[0] || null,
      mode: ['manager', 'pipeline', 'review', 'debate'].includes(t.mode) ? t.mode : 'manager', instructions: String(t.instructions || '').slice(0, 2000) };
    if (members.length < 2) throw new Error('A team needs at least two bots');
    if (!tm) { tm = { id: id(), createdAt: Date.now(), ...clean }; this.data.teams.push(tm); this.log(null, 'team', `New team: ${tm.name}`); } else Object.assign(tm, clean);
    // The coordinator needs permission to delegate.
    const c = this.bot(tm.coordinator); if (c && !c.caps.includes('delegate')) c.caps.push('delegate');
    this.save(); return tm;
  }
  teamAct(tid, act, arg) {
    const tm = this.data.teams.find((x) => x.id === tid); if (!tm) return null;
    if (act === 'delete') { this.data.teams = this.data.teams.filter((x) => x.id !== tid); this.save(); return null; }
    if (act === 'task') return this.assign({ botId: tm.coordinator, title: String(arg).slice(0, 80), description: String(arg), source: 'team', teamId: tm.id });
    return tm;
  }

  // ------------------------------------------------------------ pins + suggestions
  pin(kind, pid) {
    const i = this.data.pins.findIndex((p) => p.kind === kind && p.id === pid);
    if (i >= 0) this.data.pins.splice(i, 1); else this.data.pins.unshift({ kind, id: pid, at: Date.now() });
    this.save();
  }
  dismiss(key) { if (!this.data.dismissed.includes(key)) this.data.dismissed.push(key); this.data.dismissed = this.data.dismissed.slice(-300); this.save(); }

  /** Proactive suggestions — derived only from what really happened. */
  suggestions() {
    const lvl = this.data.settings.proactivity;
    if (lvl === 'off') return [];
    const out = [];
    const tasks = this.tasks();
    const add = (key, text, action, prio = 1) => { if (!this.data.dismissed.includes(key)) out.push({ key, text, action, prio }); };
    if (!this.data.bots.some((b) => !b.archived)) add('first-bot', 'Create your first bot — or add a ready-made one from the Marketplace.', { view: 'create' }, 0);
    else if (!this.primary()) add('primary', 'Choose a Chief of Staff — the bot that coordinates the others and prepares your briefings.', { view: 'bots' }, 2);
    for (const t of tasks.filter((x) => x.status === 'FAILED' && Date.now() - (x.finishedAt || x.updatedAt) < 2 * 86400000 && !x.parentId).slice(0, 3)) add(`retry-${t.id}`, `“${t.title}” failed${t.summary ? `: ${t.summary.slice(0, 90)}` : ''}. Retry it?`, { task: t.id, act: 'retry' }, 0);
    for (const t of tasks.filter((x) => x.status === 'REQUIRES_INPUT' && Date.now() - x.updatedAt > 10 * 60000).slice(0, 3)) add(`wait-${t.id}-${t.updatedAt}`, `${this.bot(t.botId)?.name || 'A bot'} has been waiting on you for ${Math.round((Date.now() - t.updatedAt) / 60000)} minutes.`, { task: t.id }, 0);
    for (const t of tasks.filter((x) => x.status === 'PAUSED' && x.interrupted).slice(0, 3)) add(`int-${t.id}`, `“${t.title}” was interrupted when Nebula closed. Resume from the checkpoint?`, { task: t.id, act: 'resume' }, 0);
    for (const t of tasks.filter((x) => x.dueAt && !TERMINAL.has(x.status) && x.dueAt - Date.now() < 86400000).slice(0, 3)) add(`due-${t.id}`, `“${t.title}” is due ${x(t.dueAt)}.`, { task: t.id }, 1);
    for (const r of this.data.routines) { const h = r.history[0]; if (h && h.status === 'FAILED') add(`rf-${r.id}-${h.at}`, `Routine “${r.name}” failed last time.`, { view: 'routines' }, 1); }
    if (lvl !== 'low') {
      for (const b of this.data.bots.filter((y) => !y.archived && !y.draft && !y.knowledge.length && !tasks.some((t) => t.botId === y.id)).slice(0, 2)) add(`kn-${b.id}`, `${b.name} hasn't had a task yet. Give it its first job, or teach it with some knowledge.`, { bot: b.id }, 2);
      if (lvl === 'high' && this.data.bots.length && !this.data.routines.length) add('routine', 'Nothing runs on a schedule yet. A morning briefing routine is a good first one.', { view: 'routines' }, 3);
    }
    return out.sort((a, b) => a.prio - b.prio).slice(0, lvl === 'low' ? 3 : 6);
    function x(ts) { const d = ts - Date.now(); return d < 0 ? 'overdue' : d < 3600000 ? `in ${Math.max(1, Math.round(d / 60000))} min` : `in ${Math.round(d / 3600000)} h`; }
  }

  inQuiet() {
    const { quietFrom: a, quietTo: b } = this.data.settings; if (!a || !b) return false;
    const n = new Date(); const m = n.getHours() * 60 + n.getMinutes(); const f = (s) => { const [h, mm] = s.split(':').map(Number); return h * 60 + mm; };
    return f(a) <= f(b) ? m >= f(a) && m < f(b) : m >= f(a) || m < f(b);
  }

  // ------------------------------------------------------------ the snapshot for the window
  snapshot() {
    const tasks = this.tasks();
    const convs = this.store.data.conversations.filter((c) => c.botId);
    const byBot = {}; for (const c of convs) (byBot[c.botId] ||= new Set()).add(c.id);
    const activity = this.agent.activity;
    const bots = this.data.bots.map((b) => {
      const st = botStatus({ ...b, _convs: byBot[b.id] }, tasks, activity, this.running);
      const mine = tasks.filter((t) => t.botId === b.id);
      const cur = mine.find((t) => t.status === 'IN_PROGRESS' || t.status === 'REQUIRES_INPUT');
      const bc = convs.filter((c) => c.botId === b.id);
      let last = null; let unread = 0;
      for (const c of bc) for (const m of c.messages) { if (m.role !== 'event' && (!last || m.at > last.at)) last = m; if (m.role === 'assistant' && m.at > (c.readAt || 0)) unread++; }
      const main = bc.filter((c) => !c.taskId && !c.archived).sort((x, y) => (y.main ? 1 : 0) - (x.main ? 1 : 0) || y.updatedAt - x.updatedAt)[0];
      return { ...b, mainCid: main ? main.id : null, last: last ? { text: last.text.slice(0, 140), role: last.role, at: last.at } : null, unread, status: st, current: cur ? { id: cur.id, title: cur.title } : null, counts: { done: mine.filter((t) => t.status === 'COMPLETED').length, failed: mine.filter((t) => t.status === 'FAILED').length, open: mine.filter((t) => !TERMINAL.has(t.status)).length } };
    });
    const taskIds = new Set(tasks.map((t) => t.id));
    return {
      profile: this.data.profile, settings: this.data.settings, bots, skills: this.data.skills,
      routines: this.data.routines.map((r) => ({ ...r, when: describeSchedule(r.schedule) })),
      teams: this.data.teams, activity: this.data.activity.slice(0, 300), pins: this.data.pins,
      tasks: tasks.map((t) => { const { checkpoint, ...rest } = t; return { ...rest, hasCheckpoint: !!checkpoint, running: this.running.has(t.id), activity: activity[t.conversationId] || null }; }),
      conversations: convs.map((c) => ({ ...c, messages: c.messages.slice(-200) })),
      files: this.store.data.files.filter((f) => f.taskId && taskIds.has(f.taskId)),
      mcp: this.data.mcp.map(({ envEnc, headersEnc, ...s }) => ({ ...s, botsUsing: this.data.bots.filter((b) => b.mcp && b.mcp[s.id]).map((b) => b.id) })),
      live: { ...this.agent.activity }, suggestions: this.suggestions(), keyOk: !!this.agentDeps.hasKey?.(),
      catalogue: { capabilities: CAPABILITIES, autonomy: AUTONOMY, tools: TOOL_INFO, templates: TEMPLATES, skillTemplates: SKILL_TEMPLATES.map((s, i) => ({ i, name: s[0], category: s[1], description: s[2], tools: s[3] })), personality: PERSONALITY, sliders: SLIDERS },
    };
  }
}

function teamMode(m) {
  return ({ manager: 'The coordinator assigns work to members, tracks results and combines them.', pipeline: 'Work passes through members in order; each builds on the previous result.', review: 'One member creates, another reviews and the creator fixes what the reviewer found.', debate: 'Members each give their own perspective; the coordinator weighs them and decides.' })[m] || '';
}
function fnSchema(name, description, properties, required) { return { type: 'function', function: { name, description, parameters: { type: 'object', properties, required } } }; }
function parseJson(s) {
  const t = String(s || '').replace(/^```(?:json)?|```$/gm, '').trim();
  const a = t.indexOf('{'); const b = t.lastIndexOf('}');
  if (a < 0 || b < a) throw new Error('The AI didn\'t return a usable draft. Try again.');
  return JSON.parse(t.slice(a, b + 1));
}
function exportBot(bot, ws) {
  const { status, counts, current, ...b } = bot; void status; void counts; void current;
  return { kind: 'nebula-rovbot', v: 1, exportedAt: new Date().toISOString(), bot: { ...b, id: undefined, memories: undefined, alwaysAllow: undefined, knowledge: undefined },
    knowledge: bot.knowledge.map((k) => ({ title: k.title, category: k.category, text: ws.knowledgeText(k.id) })) };
}

module.exports = { Workspace, makeBotAgent, nextRun, describeSchedule, needsApproval, allowedTools, retrieve, chunk, botStatus, personalityText, stripHtml, newBot, fromTemplate, TEMPLATES, CAPABILITIES, AUTONOMY, TOOL_INFO, SKILL_TEMPLATES };
