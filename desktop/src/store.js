'use strict';
// Nebula for Windows — conversations, tasks and files, saved as JSON in the user's app data.
// Same shapes as the phone app so the two feel identical.

const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

const id = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

class Store extends EventEmitter {
  constructor(dir) {
    super();
    this.dir = dir;
    this.file = path.join(dir, 'nebula-data.json');
    this.filesDir = path.join(dir, 'Files');
    this.data = { conversations: [], tasks: [], files: [] };
    try { fs.mkdirSync(this.filesDir, { recursive: true }); } catch { /* */ }
    try { Object.assign(this.data, JSON.parse(fs.readFileSync(this.file, 'utf8'))); } catch { /* first run */ }
    // Anything that was running when the app closed can't still be running.
    for (const t of this.data.tasks) if (t.status === 'IN_PROGRESS') { t.status = 'FAILED'; t.summary = t.summary || 'Nebula was closed while this was running.'; }
    this.files = this.data.files.filter((f) => fs.existsSync(f.path));
    this.data.files = this.files;
    this.timer = null;
  }

  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      try { fs.writeFileSync(this.file + '.tmp', JSON.stringify(this.data)); fs.renameSync(this.file + '.tmp', this.file); } catch { /* */ }
    }, 250);
    this.emit('change', this.snapshot());
  }

  snapshot() { return { conversations: this.data.conversations, tasks: this.data.tasks, files: this.data.files }; }

  // ------------------------------------------------------------ conversations
  conversation(cid) { return this.data.conversations.find((c) => c.id === cid); }
  newConversation() {
    const c = { id: id(), title: 'New chat', messages: [], createdAt: Date.now(), updatedAt: Date.now() };
    this.data.conversations.unshift(c); this.save(); return c;
  }
  addMessage(cid, m) {
    const c = this.conversation(cid) || this.newConversation();
    const msg = { id: id(), role: 'assistant', text: '', sources: [], fileIds: [], taskId: null, at: Date.now(), ...m };
    c.messages.push(msg);
    c.updatedAt = Date.now();
    if (c.title === 'New chat' && m.role === 'user') c.title = m.text.replace(/\s+/g, ' ').slice(0, 48);
    this.data.conversations.sort((a, b) => b.updatedAt - a.updatedAt);
    this.save();
    return msg;
  }
  deleteConversation(cid) { this.data.conversations = this.data.conversations.filter((c) => c.id !== cid); this.save(); }

  // ------------------------------------------------------------ tasks
  task(tid) { return this.data.tasks.find((t) => t.id === tid); }
  createTask(t) {
    const task = { id: id(), title: 'Task', kind: 'general', status: 'IN_PROGRESS', progress: 0, steps: [], plan: [], summary: '',
      input: null, fileIds: [], conversationId: null, createdAt: Date.now(), updatedAt: Date.now(), ...t };
    this.data.tasks.unshift(task); this.save(); return task;
  }
  updateTask(tid, fn) {
    const t = this.task(tid); if (!t) return null;
    fn(t); t.updatedAt = Date.now();
    const done = t.steps.filter((s) => s.status === 'DONE').length;
    t.progress = t.plan.length ? Math.min(1, done / t.plan.length) : Math.min(0.9, t.steps.length * 0.18);
    if (t.status === 'COMPLETED') t.progress = 1;
    this.save(); return t;
  }
  deleteTask(tid) { this.data.tasks = this.data.tasks.filter((t) => t.id !== tid); this.save(); }

  // ------------------------------------------------------------ files
  file(fid) { return this.data.files.find((f) => f.id === fid); }
  addFile(f) {
    const rec = { id: id(), createdAt: Date.now(), ...f };
    this.data.files.unshift(rec); this.save(); return rec;
  }
  updateFile(fid, patch) { const f = this.file(fid); if (f) { Object.assign(f, patch, { updatedAt: Date.now() }); this.save(); } return f; }
  deleteFile(fid) {
    const f = this.file(fid);
    if (f) { try { fs.unlinkSync(f.path); } catch { /* */ } }
    this.data.files = this.data.files.filter((x) => x.id !== fid); this.save();
  }
  /** A free file name like "Budget 2026 (2).xlsx" in the Files folder. */
  freePath(title, ext) {
    const base = (title || 'Untitled').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '').replace(/\s+/g, ' ').trim().slice(0, 80) || 'Untitled';
    let p = path.join(this.filesDir, `${base}.${ext}`); let n = 2;
    while (fs.existsSync(p)) p = path.join(this.filesDir, `${base} (${n++}).${ext}`);
    return p;
  }
}

module.exports = { Store, id };
