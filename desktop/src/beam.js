'use strict';
// Nebula Beam for Windows and Linux: the same protocol as the phone (see BeamTransport.kt), so PCs and phones
// can Beam to each other on the same Wi-Fi.
//  - Discovery: DNS-SD over multicast DNS (_nebulabeam._tcp.local), a tiny built-in responder/browser (no deps).
//  - Transfer: TCP. P-256 ECDH per transfer → SHA-256 → AES-256-GCM in 64 KB frames, a 6-digit code on both
//    screens, and nothing is accepted until the person here says yes.
// Only real devices are ever listed; nothing is simulated.

const crypto = require('crypto');
const dgram = require('dgram');
const net = require('net');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

const SERVICE = '_nebulabeam._tcp.local';
const MDNS = '224.0.0.251';
const PORT = 5353;

// ------------------------------------------------------------------ crypto (mirror of BeamSecurity.kt)

function keyPair() { return crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }); }
function pub(kp) { return kp.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'); }

class Session {
  constructor(kp, theirs) {
    const other = crypto.createPublicKey({ key: Buffer.from(theirs, 'base64'), format: 'der', type: 'spki' });
    const secret = crypto.diffieHellman({ privateKey: kp.privateKey, publicKey: other });
    this.key = crypto.createHash('sha256').update(Buffer.concat([Buffer.from('nebula-beam-key'), secret])).digest();
    const c = crypto.createHash('sha256').update(Buffer.concat([Buffer.from('nebula-beam-code'), secret])).digest();
    this.code = String(((c[0] << 16) | (c[1] << 8) | c[2]) % 1000000).padStart(6, '0');
    this.sendN = 0n; this.recvN = 0n;
  }
  nonce(n) { const b = Buffer.alloc(12); b.writeInt32BE(1, 0); b.writeBigInt64BE(n, 4); return b; }
  frame(data) {
    if (!data || data.length === 0) { const z = Buffer.alloc(4); return z; }
    const c = crypto.createCipheriv('aes-256-gcm', this.key, this.nonce(this.sendN++));
    const ct = Buffer.concat([c.update(data), c.final(), c.getAuthTag()]);
    const len = Buffer.alloc(4); len.writeInt32BE(ct.length, 0);
    return Buffer.concat([len, ct]);
  }
  open(ct) {
    const d = crypto.createDecipheriv('aes-256-gcm', this.key, this.nonce(this.recvN++));
    d.setAuthTag(ct.subarray(ct.length - 16));
    return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
  }
}

// ------------------------------------------------------------------ a socket reader for lines and frames

class Reader {
  constructor(sock) {
    this.buf = Buffer.alloc(0); this.wait = null; this.err = null;
    sock.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this.kick(); });
    sock.on('close', () => { this.err = this.err || new Error('closed'); this.kick(); });
    sock.on('error', (e) => { this.err = e; this.kick(); });
  }
  kick() { const w = this.wait; if (w) { this.wait = null; w(); } }
  async need(test) {
    for (;;) {
      const r = test(); if (r !== undefined) return r;
      if (this.err) throw this.err;
      await new Promise((res) => { this.wait = res; });
    }
  }
  line() {
    return this.need(() => {
      const i = this.buf.indexOf(10);
      if (i < 0) { if (this.buf.length > 200000) throw new Error('header too long'); return undefined; }
      const s = this.buf.subarray(0, i).toString('utf8'); this.buf = this.buf.subarray(i + 1); return s;
    });
  }
  bytes(n) { return this.need(() => { if (this.buf.length < n) return undefined; const b = this.buf.subarray(0, n); this.buf = this.buf.subarray(n); return b; }); }
  async frame() {
    const n = (await this.bytes(4)).readInt32BE(0);
    if (n === 0) return null;
    if (n < 1 || n > (1 << 20)) throw new Error('bad frame');
    return this.bytes(n);
  }
}

const json = (s) => { try { return JSON.parse(s); } catch { return {}; } };
const writeLine = (sock, o) => new Promise((res) => sock.write(JSON.stringify(o) + '\n', res));
const write = (sock, b) => new Promise((res) => { if (sock.write(b)) res(); else sock.once('drain', res); });

// ------------------------------------------------------------------ mDNS / DNS-SD

function lanAddress() {
  const skip = /(vethernet|virtualbox|vmware|docker|br-|veth|wsl|hyper-v|tailscale|zerotier|loopback)/i;
  const all = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal && !skip.test(name)) all.push(a.address);
  }
  return all.find((a) => /^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))/.test(a)) || all[0] || '';
}

function encName(name) {
  const parts = name.split('.').filter(Boolean);
  // The instance label may contain dots-free text with spaces and '#'; service labels are plain.
  return Buffer.concat([...parts.map((p) => { const b = Buffer.from(p, 'utf8').subarray(0, 63); return Buffer.concat([Buffer.from([b.length]), b]); }), Buffer.from([0])]);
}
function encInstance(inst) { const b = Buffer.from(inst, 'utf8').subarray(0, 63); return Buffer.concat([Buffer.from([b.length]), b, encName(SERVICE)]); }

function readName(msg, off) {
  const labels = []; let jumped = false; let end = off; let guard = 0;
  for (;;) {
    if (off >= msg.length || guard++ > 64) throw new Error('bad name');
    const len = msg[off];
    if (len === 0) { if (!jumped) end = off + 1; break; }
    if ((len & 0xc0) === 0xc0) { if (!jumped) end = off + 2; off = ((len & 0x3f) << 8) | msg[off + 1]; jumped = true; continue; }
    labels.push(msg.subarray(off + 1, off + 1 + len).toString('utf8')); off += 1 + len;
  }
  return { labels, end };
}

function parse(msg) {
  const qd = msg.readUInt16BE(4), an = msg.readUInt16BE(6), ns = msg.readUInt16BE(8), ar = msg.readUInt16BE(10);
  const flags = msg.readUInt16BE(2);
  let off = 12; const questions = []; const records = [];
  for (let i = 0; i < qd; i++) { const n = readName(msg, off); off = n.end; questions.push({ labels: n.labels, type: msg.readUInt16BE(off) }); off += 4; }
  for (let i = 0; i < an + ns + ar; i++) {
    const n = readName(msg, off); off = n.end;
    const type = msg.readUInt16BE(off), ttl = msg.readUInt32BE(off + 4), len = msg.readUInt16BE(off + 8); off += 10;
    const r = { labels: n.labels, type, ttl };
    if (type === 12) r.ptr = readName(msg, off).labels;
    else if (type === 33) { r.port = msg.readUInt16BE(off + 4); r.target = readName(msg, off + 6).labels; }
    else if (type === 16) { r.txt = {}; let p = off; while (p < off + len) { const l = msg[p]; const s = msg.subarray(p + 1, p + 1 + l).toString('utf8'); const i = s.indexOf('='); if (i > 0) r.txt[s.slice(0, i)] = s.slice(i + 1); p += 1 + l; } }
    else if (type === 1 && len === 4) r.a = `${msg[off]}.${msg[off + 1]}.${msg[off + 2]}.${msg[off + 3]}`;
    records.push(r); off += len;
  }
  return { response: (flags & 0x8000) !== 0, questions, records };
}

function rr(nameBuf, type, ttl, data, flush) {
  const h = Buffer.alloc(10); h.writeUInt16BE(type, 0); h.writeUInt16BE(flush ? 0x8001 : 1, 2); h.writeUInt32BE(ttl, 4); h.writeUInt16BE(data.length, 8);
  return Buffer.concat([nameBuf, h, data]);
}
function packet(flags, questions, answers, additional) {
  const h = Buffer.alloc(12); h.writeUInt16BE(flags, 2); h.writeUInt16BE(questions.length, 4); h.writeUInt16BE(answers.length, 6); h.writeUInt16BE(additional.length, 10);
  return Buffer.concat([h, ...questions, ...answers, ...additional]);
}
const lower = (labels) => labels.join('.').toLowerCase();

class Beam extends EventEmitter {
  /** opts: { name, type, downloads, maxMb() } */
  constructor(opts) {
    super();
    this.opts = opts; this.devices = new Map(); this.sock = null; this.legacy = null;
    this.server = null; this.inst = null; this.scanTimer = null; this.busy = false; this.pending = null;
    this.host = `nebula-${crypto.randomBytes(3).toString('hex')}.local`;
  }

  // ---- multicast socket (shared by the browser and the responder)
  async socket() {
    if (this.sock) return this.sock;
    const ip = lanAddress();
    const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    await new Promise((res, rej) => { s.once('error', rej); s.bind(PORT, () => { s.removeListener('error', rej); res(); }); }).catch(async () => {
      // 5353 is taken and can't be shared: we can still browse with one-shot queries from a random port.
      this.cantAdvertise = true;
    });
    if (this.cantAdvertise) {
      try { s.close(); } catch {}
      const l = dgram.createSocket({ type: 'udp4' });
      await new Promise((res) => l.bind(0, res));
      l.on('message', (m, r) => this.onMessage(m, r));
      this.sock = l; this.legacy = true; return l;
    }
    try { s.addMembership(MDNS, ip || undefined); } catch { try { s.addMembership(MDNS); } catch {} }
    try { if (ip) s.setMulticastInterface(ip); s.setMulticastTTL(255); s.setMulticastLoopback(true); } catch {}
    s.on('message', (m, r) => this.onMessage(m, r));
    s.on('error', (e) => this.emit('log', 'mdns ' + e.message));
    this.sock = s; this.ip = ip; return s;
  }

  sendPacket(buf) { if (this.sock) this.sock.send(buf, PORT, MDNS, () => {}); }

  onMessage(msg, rinfo) {
    let p; try { p = parse(msg); } catch { return; }
    if (!p.response) { this.answer(p, rinfo); return; }
    // Browse: collect PTR → SRV/TXT for our service type.
    const now = Date.now(); let changed = false;
    const srv = {}; const txt = {};
    for (const r of p.records) {
      if (r.type === 33) srv[lower(r.labels)] = r;
      if (r.type === 16) txt[lower(r.labels)] = r.txt;
    }
    for (const r of p.records) {
      if (r.type !== 12 || lower(r.labels) !== SERVICE) continue;
      const inst = r.ptr[0]; const key = lower(r.ptr);
      if (inst === this.inst) continue;
      if (r.ttl === 0) { if (this.devices.delete(inst)) changed = true; continue; }
      const s = srv[key]; const t = txt[key] || {};
      const prev = this.devices.get(inst);
      const port = s ? s.port : prev && prev.port;
      if (!port) { this.resolve(r.ptr); continue; }
      this.devices.set(inst, { id: inst, name: t.name || (prev && prev.name) || inst.replace(/ #\d+$/, ''), type: t.type || (prev && prev.type) || 'android', host: rinfo.address, port, seenAt: now });
      changed = true;
    }
    // A bare SRV answer (to our resolve question).
    for (const [key, s] of Object.entries(srv)) {
      if (!key.endsWith('.' + SERVICE)) continue;
      const inst = s.labels[0]; if (inst === this.inst) continue;
      const t = txt[key] || {}; const prev = this.devices.get(inst);
      this.devices.set(inst, { id: inst, name: t.name || (prev && prev.name) || inst.replace(/ #\d+$/, ''), type: t.type || (prev && prev.type) || 'android', host: rinfo.address, port: s.port, seenAt: now });
      changed = true;
    }
    if (changed) this.emit('devices', this.list());
  }

  resolve(labels) {
    const name = Buffer.concat([...labels.map((l) => { const b = Buffer.from(l, 'utf8'); return Buffer.concat([Buffer.from([b.length]), b]); }), Buffer.from([0])]);
    const q = (t) => Buffer.concat([name, Buffer.from([0, t, 0, 1])]);
    this.sendPacket(packet(0, [q(33), q(16)], [], []));
  }

  list() {
    const cutoff = Date.now() - 150000;
    for (const [k, d] of this.devices) if (d.seenAt < cutoff) this.devices.delete(k);
    return [...this.devices.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  async scan(seconds = 30) {
    await this.socket();
    const ask = () => this.sendPacket(packet(0, [Buffer.concat([encName(SERVICE), Buffer.from([0, 12, 0, 1])])], [], []));
    clearInterval(this.scanTimer); ask();
    let n = 0; this.scanTimer = setInterval(() => { ask(); if (++n * 3 >= seconds) this.stopScan(); }, 3000);
    this.emit('devices', this.list());
  }
  stopScan() { clearInterval(this.scanTimer); this.scanTimer = null; }

  // ---- responder
  records(ttl) {
    const instName = encInstance(this.inst);
    const host = encName(this.host);
    const ptr = rr(encName(SERVICE), 12, ttl, instName, false);
    const srvData = Buffer.concat([Buffer.from([0, 0, 0, 0, this.port >> 8, this.port & 255]), host]);
    const srv = rr(instName, 33, ttl, srvData, true);
    const txtParts = [['name', this.opts.name()], ['type', this.opts.type], ['v', '1']].map(([k, v]) => { const b = Buffer.from(`${k}=${v}`, 'utf8').subarray(0, 255); return Buffer.concat([Buffer.from([b.length]), b]); });
    const txt = rr(instName, 16, ttl, Buffer.concat(txtParts), true);
    const extra = [srv, txt];
    if (this.ip) extra.push(rr(host, 1, ttl, Buffer.from(this.ip.split('.').map(Number)), true));
    return { ptr, extra };
  }
  answer(p, rinfo) {
    if (!this.inst || !this.port) return;
    const mine = [SERVICE, (this.inst + '.' + SERVICE).toLowerCase(), this.host.toLowerCase()];
    if (!p.questions.some((q) => mine.includes(lower(q.labels)))) return;
    const { ptr, extra } = this.records(120);
    const out = packet(0x8400, [], [ptr], extra);
    if (rinfo.port !== PORT) this.sock.send(out, rinfo.port, rinfo.address, () => {}); // legacy unicast query
    else this.sendPacket(out);
  }

  /** Be findable (start the receiver and announce). */
  async visible() {
    if (this.inst) return;
    await this.socket();
    if (!this.server) await this.listen();
    if (this.cantAdvertise) { this.emit('log', 'mdns port busy — can send, but other devices may not see this PC'); return; }
    this.inst = `${this.opts.name()} #${1000 + Math.floor(Math.random() * 9000)}`;
    const { ptr, extra } = this.records(120);
    const ann = packet(0x8400, [], [ptr], extra);
    this.sendPacket(ann); setTimeout(() => this.sendPacket(ann), 1000);
  }
  hide() {
    if (!this.inst) return;
    try { const { ptr } = this.records(0); this.sendPacket(packet(0x8400, [], [ptr], [])); } catch {}
    this.inst = null;
  }
  close() { this.hide(); this.stopScan(); try { this.server && this.server.close(); } catch {} this.server = null; try { this.sock && this.sock.close(); } catch {} this.sock = null; }

  // ---- receiving
  listen() {
    return new Promise((res) => {
      const srv = net.createServer((c) => {
        if (this.busy) { c.destroy(); return; }
        this.busy = true;
        this.receive(c).catch((e) => this.emit('state', { kind: 'failed', reason: e.message === 'closed' ? 'Connection lost' : 'Connection lost (' + e.message + ')' }))
          .finally(() => { this.busy = false; c.destroy(); });
      });
      srv.listen(0, () => { this.server = srv; this.port = srv.address().port; res(); });
    });
  }

  async receive(c) {
    c.setTimeout(90000, () => c.destroy(new Error('timeout')));
    const r = new Reader(c);
    const h = json(await r.line());
    const items = (h.items || []).map((o) => ({ name: String(o.name || 'file').slice(0, 120), size: Number(o.size) || 0, mime: String(o.mime || '') }));
    const total = items.reduce((a, b) => a + b.size, 0);
    if (total > this.opts.maxMb() * 1024 * 1024) { await writeLine(c, { ok: false, reason: "Too big for this device's Beam limit" }); return; }
    const kp = keyPair(); const session = new Session(kp, h.pub);
    const from = String(h.from || 'Someone nearby').slice(0, 60);
    const text = typeof h.text === 'string' && h.text.trim() ? h.text.slice(0, 20000) : null;
    await writeLine(c, { wait: true, pub: pub(kp) });
    const offer = { from, device: String(h.device || ''), items, total, code: session.code, text };
    const ok = await new Promise((res) => {
      const t = setTimeout(() => { this.pending = null; res(false); }, 60000);
      this.pending = (yes) => { clearTimeout(t); this.pending = null; res(!!yes); };
      this.emit('offer', offer);
    });
    if (!ok) { await writeLine(c, { ok: false, reason: 'Transfer declined' }); this.emit('state', { kind: 'idle' }); return; }
    await writeLine(c, { ok: true });
    const dir = path.join(this.opts.downloads(), 'Nebula Beam');
    fs.mkdirSync(dir, { recursive: true });
    const saved = []; let got = 0;
    try {
      for (const it of items) {
        const file = unique(dir, it.name.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^\.+/, '_') || 'file');
        const fd = fs.openSync(file, 'w'); saved.push(file);
        try {
          for (;;) {
            const f = await r.frame(); if (!f) break;
            const plain = session.open(f); fs.writeSync(fd, plain); got += plain.length;
            this.emit('state', { kind: 'receiving', from, progress: Math.min(1, got / Math.max(1, total)), count: items.length });
          }
        } finally { fs.closeSync(fd); }
      }
      await writeLine(c, { done: true, saved: saved.length });
      this.emit('state', { kind: 'received', from, count: saved.length, saved, text, dir });
    } catch (e) {
      for (const f of saved) { try { fs.unlinkSync(f); } catch {} }
      throw e;
    }
  }
  decide(yes) { if (this.pending) this.pending(yes); }

  // ---- sending
  async send(device, files, text) {
    const kp = keyPair();
    const items = files.map((f) => { const st = fs.statSync(f); return { path: f, name: path.basename(f), size: st.size, mime: mimeOf(f) }; });
    const c = net.connect({ host: device.host, port: device.port });
    const r = new Reader(c);
    try {
      await new Promise((res, rej) => { c.once('connect', res); c.once('error', rej); setTimeout(() => rej(Object.assign(new Error('unavailable'), { code: 'ECONNREFUSED' })), 6000); });
      this.emit('state', { kind: 'waiting', to: device.name, code: '' });
      const header = { v: 1, from: this.opts.name(), device: this.opts.type, pub: pub(kp), items: items.map(({ name, size, mime }) => ({ name, size, mime })) };
      if (text) header.text = text;
      await writeLine(c, header);
      let reply = json(await withTimeout(r.line(), 75000));
      let session = null;
      if (reply.wait) { session = new Session(kp, reply.pub); this.emit('state', { kind: 'waiting', to: device.name, code: session.code }); reply = json(await withTimeout(r.line(), 75000)); }
      if (!reply.ok) return { ok: false, reason: reply.reason || 'Transfer declined' };
      if (!session) session = new Session(kp, reply.pub);
      const total = Math.max(1, items.reduce((a, b) => a + b.size, 0)); let sent = 0;
      this.emit('state', { kind: 'sending', to: device.name, progress: 0, count: items.length || 1, code: session.code });
      for (const it of items) {
        const fd = fs.openSync(it.path, 'r'); const buf = Buffer.alloc(64 * 1024);
        try {
          for (;;) {
            const n = fs.readSync(fd, buf, 0, buf.length, null); if (n <= 0) break;
            await write(c, session.frame(buf.subarray(0, n))); sent += n;
            this.emit('state', { kind: 'sending', to: device.name, progress: Math.min(1, sent / total), count: items.length, code: session.code });
          }
        } finally { fs.closeSync(fd); }
        await write(c, session.frame(null));
      }
      const done = json(await withTimeout(r.line(), 60000));
      return done.done ? { ok: true } : { ok: false, reason: done.reason || 'Connection lost' };
    } catch (e) {
      if (e.code === 'ECONNREFUSED' || e.code === 'EHOSTUNREACH' || e.message === 'unavailable') return { ok: false, reason: 'Recipient unavailable' };
      if (e.message === 'timeout') return { ok: false, reason: "Recipient didn't answer" };
      return { ok: false, reason: 'Connection lost' };
    } finally { c.destroy(); }
  }
}

function withTimeout(p, ms) { return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]); }
function unique(dir, name) {
  let p = path.join(dir, name); const ext = path.extname(name); const base = name.slice(0, name.length - ext.length); let i = 1;
  while (fs.existsSync(p)) p = path.join(dir, `${base} (${i++})${ext}`);
  return p;
}
function mimeOf(f) {
  const e = path.extname(f).toLowerCase().slice(1);
  return ({ jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', heic: 'image/heic', mp4: 'video/mp4', mov: 'video/quicktime',
    mkv: 'video/x-matroska', webm: 'video/webm', mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', ogg: 'audio/ogg', pdf: 'application/pdf', txt: 'text/plain',
    zip: 'application/zip', doc: 'application/msword', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' })[e] || 'application/octet-stream';
}

module.exports = { Beam, Session, keyPair, pub, parse, packet, encName, encInstance, rr, Reader, lanAddress };
