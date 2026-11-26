'use strict';
// Mood PLAY (desktop): what's playing on this PC, on the TV — the same TV screen as the phone app
// (shared/moodplay/tv.html), served over your Wi-Fi, opened on Samsung / LG TVs for you (after "Allow" on the
// TV the first time), or by typing the short address on any TV or computer. The TV remote controls the music.

const http = require('http');
const dgram = require('dgram');
const net = require('net');
const tls = require('tls');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ABC = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const newCode = () => Array.from(crypto.randomBytes(6), (b) => ABC[b % ABC.length]).join('');
const isLan = (ip) => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|127\.)/.test(String(ip || '').replace(/^::ffff:/, ''));

// ---------------------------------------------------------------- a tiny WebSocket client (TV remote sockets only)
function wsConnect(url, { timeout = 4000 } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url); const secure = u.protocol === 'wss:'; const port = +u.port || (secure ? 443 : 80);
    if (!isLan(u.hostname)) return reject(new Error('Only TVs on your own network'));
    // TVs use self-signed certificates on their LAN ports; only ever used for a private address the user picked.
    const sock = secure ? tls.connect({ host: u.hostname, port, rejectUnauthorized: false }) : net.connect({ host: u.hostname, port });
    const key = crypto.randomBytes(16).toString('base64');
    let buf = Buffer.alloc(0), open = false; const queue = [], waiters = [];
    const t = setTimeout(() => { sock.destroy(); reject(new Error('The TV didn’t answer')); }, timeout);
    sock.once(secure ? 'secureConnect' : 'connect', () => sock.write(`GET ${u.pathname}${u.search} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nOrigin: http://${u.hostname}\r\n\r\n`));
    sock.on('error', (e) => { clearTimeout(t); if (!open) reject(e); waiters.splice(0).forEach((w) => w(null)); });
    sock.on('close', () => waiters.splice(0).forEach((w) => w(null)));
    const frame = (op, data) => {
      const m = crypto.randomBytes(4); const len = data.length;
      let head;
      if (len < 126) head = Buffer.from([0x80 | op, 0x80 | len]);
      else if (len < 65536) head = Buffer.from([0x80 | op, 0x80 | 126, len >> 8, len & 255]);
      else { head = Buffer.alloc(10); head[0] = 0x80 | op; head[1] = 0x80 | 127; head.writeBigUInt64BE(BigInt(len), 2); }
      const body = Buffer.from(data.map((b, i) => b ^ m[i % 4]));
      sock.write(Buffer.concat([head, m, body]));
    };
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!open) {
        const end = buf.indexOf('\r\n\r\n'); if (end < 0) return;
        const head = buf.subarray(0, end).toString(); buf = buf.subarray(end + 4);
        if (!/^HTTP\/1\.1 101/.test(head)) { clearTimeout(t); sock.destroy(); return reject(new Error('The TV refused the connection')); }
        open = true; clearTimeout(t);
        resolve({
          send: (s) => frame(1, Buffer.from(s)),
          next: (ms) => new Promise((res) => { if (queue.length) return res(queue.shift()); const w = (v) => { clearTimeout(tt); res(v); }; const tt = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); res(null); }, ms); waiters.push(w); }),
          close: () => { try { frame(8, Buffer.alloc(0)); } catch { /* */ } sock.end(); },
        });
      }
      while (buf.length >= 2) {
        let len = buf[1] & 127, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; } else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = buf[0] & 15, data = buf.subarray(off, off + len); buf = buf.subarray(off + len);
        if (op === 1) { const s = data.toString(); const w = waiters.shift(); if (w) w(s); else queue.push(s); } else if (op === 9) frame(10, data); else if (op === 8) sock.end();
      }
    });
  });
}

// ---------------------------------------------------------------- finding TVs (SSDP) — real replies only
const SEARCH = ['urn:samsung.com:device:RemoteControlReceiver:1', 'urn:lge-com:service:webos-second-screen:1', 'urn:dial-multiscreen-org:service:dial:1'];
function getText(url, ms = 1500) {
  return new Promise((res) => { const r = http.get(url, { timeout: ms }, (s) => { let b = ''; s.on('data', (d) => { b += d; if (b.length > 200000) r.destroy(); }); s.on('end', () => res(b)); }); r.on('error', () => res('')); r.on('timeout', () => { r.destroy(); res(''); }); });
}
const tag = (x, t) => ((new RegExp(`<${t}>([^<]*)</${t}>`)).exec(x) || [])[1]?.trim() || '';
async function describe(ip, location, hint) {
  let name = '', maker = '', model = '', type = '';
  try { const u = new URL(location); if (u.hostname === ip) { const x = await getText(location); name = tag(x, 'friendlyName'); maker = tag(x, 'manufacturer'); model = tag(x, 'modelName'); type = tag(x, 'deviceType'); } } catch { /* */ }
  const kind = hint || (/samsung/i.test(maker) ? 'samsung' : /lg/i.test(maker) && /webos|tv/i.test(`${model} ${type} ${name}`) ? 'lg' : 'tv');
  if (!name) { if (kind === 'samsung') name = 'Samsung TV'; else if (kind === 'lg') name = 'LG TV'; else return null; }
  if (kind === 'tv' && /speaker|soundbar|sonos|audio|receiver/i.test(`${name} ${model} ${type}`)) return null;
  return { id: `${kind}:${ip}`, name: name.replace(/&amp;/g, '&').replace(/^\[TV\]\s*/, '').trim().slice(0, 40), kind, ip, model };
}
function findTvs(seconds, onFound) {
  return new Promise((resolve) => {
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true }); const seen = new Map();
    sock.on('message', async (msg, rinfo) => {
      const text = msg.toString(); const ip = rinfo.address; if (!isLan(ip)) return;
      const h = (k) => ((new RegExp(`^${k}:\\s*(.*)$`, 'im')).exec(text) || [])[1]?.trim() || '';
      const st = h('ST'); const hint = /samsung/i.test(st) ? 'samsung' : /lge/i.test(st) ? 'lg' : null;
      const prev = seen.get(ip); if (prev && (!hint || prev === hint)) return;
      seen.set(ip, hint || 'tv');
      const tv = await describe(ip, h('LOCATION'), hint); if (tv) onFound(tv);
    });
    sock.on('error', () => resolve());
    sock.bind(() => {
      for (let i = 0; i < 2; i++) for (const st of SEARCH) {
        const m = Buffer.from(`M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${st}\r\n\r\n`);
        sock.send(m, 1900, '239.255.255.250');
      }
    });
    setTimeout(() => { try { sock.close(); } catch { /* */ } resolve(); }, seconds * 1000);
  });
}

// ---------------------------------------------------------------- opening the page on the TV
async function openSamsung(tv, url, token, onAsk) {
  const name = Buffer.from('Nebula').toString('base64'); let last = 'No answer from the TV';
  for (const [scheme, port] of [['wss', 8002], ['ws', 8001]]) {
    let ws;
    try {
      ws = await wsConnect(`${scheme}://${tv.ip}:${port}/api/v2/channels/samsung.remote.control?name=${name}${token ? `&token=${token}` : ''}`);
      let asked = false; let newToken = token; const until = Date.now() + 32000;
      while (Date.now() < until) {
        const msg = await ws.next(asked ? 30000 : 2500);
        if (msg == null) { if (!asked) { asked = true; onAsk(); continue; } break; }
        let j; try { j = JSON.parse(msg); } catch { continue; }
        if (j.event === 'ms.channel.connect') {
          if (j.data && j.data.token) newToken = j.data.token;
          ws.send(JSON.stringify({ method: 'ms.channel.emit', params: { event: 'ed.apps.launch', to: 'host', data: { appId: 'org.tizen.browser', action_type: 'NATIVE_LAUNCH', metaTag: url } } }));
          await new Promise((r) => setTimeout(r, 600));
          return { ok: true, token: newToken };
        }
        if (j.event === 'ms.channel.unauthorized') return { declined: true };
        if (j.event === 'ms.channel.timeOut') return { error: 'The TV stopped waiting for an answer' };
      }
      last = 'The TV didn’t answer';
    } catch (e) { last = e.message; } finally { if (ws) ws.close(); }
  }
  return { error: last };
}
async function openLg(tv, url, key, onAsk) {
  let last = 'No answer from the TV';
  const perms = ['LAUNCH', 'LAUNCH_WEBAPP', 'APP_TO_APP', 'CONTROL_AUDIO', 'CONTROL_INPUT_MEDIA_PLAYBACK'];
  for (const [scheme, port] of [['wss', 3001], ['ws', 3000]]) {
    let ws;
    try {
      ws = await wsConnect(`${scheme}://${tv.ip}:${port}/`);
      ws.send(JSON.stringify({ type: 'register', id: 'reg0', payload: { forcePairing: false, pairingType: 'PROMPT', ...(key ? { 'client-key': key } : {}),
        manifest: { manifestVersion: 1, appVersion: '1.0', signed: { appId: 'com.nebula.moodplay', vendorId: 'com.nebula', localizedAppNames: { '': 'Nebula' }, permissions: perms, serial: 'nebula' }, permissions: perms } } }));
      let clientKey = key; const until = Date.now() + 40000;
      while (Date.now() < until) {
        const msg = await ws.next(35000); if (msg == null) break;
        let j; try { j = JSON.parse(msg); } catch { continue; }
        if (j.type === 'response' && j.payload && j.payload.pairingType === 'PROMPT') onAsk();
        else if (j.type === 'registered') {
          if (j.payload && j.payload['client-key']) clientKey = j.payload['client-key'];
          ws.send(JSON.stringify({ type: 'request', id: 'open1', uri: 'ssap://system.launcher/open', payload: { target: url } }));
          await ws.next(6000);
          return { ok: true, token: clientKey };
        } else if (j.type === 'error') return /reject|denied/i.test(j.error || '') ? { declined: true } : { error: j.error || 'The TV said no' };
      }
      last = 'The TV didn’t answer';
    } catch (e) { last = e.message; } finally { if (ws) ws.close(); }
  }
  return { error: last };
}

// ---------------------------------------------------------------- the session
class MoodPlay {
  /** @param {{lanIp:()=>string|null, media:()=>object|null, control:(c:string)=>void, deviceName:string, pagePath:string, getTvs:()=>object, saveTvs:(o:object)=>void, getTheme:()=>string, setTheme:(t:string)=>void, onChange:(s:object)=>void}} o */
  constructor(o) { Object.assign(this, o); this.server = null; this.streams = new Set(); this.ui = { mode: 'idle' }; this.tvs = []; this.misses = new Map(); }

  set(ui) { this.ui = ui; this.onChange(this.status()); }
  status() { return { ...this.ui, url: this.url || '', theme: this.getTheme(), viewers: this.streams.size, tvs: this.tvs }; }

  async start() {
    if (this.server) return true;
    const ip = this.lanIp(); if (!ip) { this.set({ mode: 'nowifi' }); return false; }
    this.code = newCode();
    this.server = http.createServer((q, r) => this.handle(q, r));
    await new Promise((res) => { this.server.once('error', () => { this.server.listen(0, '0.0.0.0', res); }); this.server.listen(8765, '0.0.0.0', res); });
    this.port = this.server.address().port;
    this.url = `http://${ip}:${this.port}/${this.code}/`;
    this.ping = setInterval(() => this.push(null), 15000);
    return true;
  }

  async find() {
    if (!(await this.start())) return;
    this.tvs = []; this.set({ mode: 'finding', searching: true });
    await findTvs(5, (tv) => { if (!this.tvs.some((x) => x.ip === tv.ip && x.kind !== 'tv' && tv.kind === 'tv')) { this.tvs = [...this.tvs.filter((x) => x.ip !== tv.ip), tv]; this.set({ ...this.ui }); } });
    if (this.ui.mode === 'finding') this.set({ mode: 'finding', searching: false });
  }

  async connect(id) {
    const tv = this.tvs.find((x) => x.id === id); if (!tv || !this.server) return;
    this.tvName = tv.name;
    if (tv.kind !== 'samsung' && tv.kind !== 'lg') return this.manual(`Open this in ${tv.name}'s web browser`);
    this.set({ mode: 'opening', tv: tv.name, asking: false });
    const saved = (this.getTvs() || {})[tv.id];
    const ask = () => this.set({ mode: 'opening', tv: tv.name, asking: true });
    const r = tv.kind === 'samsung' ? await openSamsung(tv, this.url, saved, ask) : await openLg(tv, this.url, saved, ask);
    if (r.ok) { if (r.token) this.saveTvs({ ...(this.getTvs() || {}), [tv.id]: r.token }); this.set({ mode: 'live', tv: tv.name }); }
    else if (r.declined) this.manual(`${tv.name} said no — you can still open it yourself`);
    else this.manual(`Couldn’t open it on ${tv.name} (${String(r.error).slice(0, 60)}). Open it on the TV instead`);
  }

  async manual(why) { if (!(await this.start())) return; this.set({ mode: 'manual', why: why || 'Open this in your TV’s web browser' }); }
  done() { this.set({ mode: 'live', tv: this.tvName || 'your TV' }); }
  theme(t) { this.setTheme(t); this.themeChanged = true; this.push(this.state()); this.set({ ...this.ui }); }

  stop() {
    clearInterval(this.ping);
    for (const s of this.streams) { try { s.end(); } catch { /* */ } }
    this.streams.clear();
    if (this.server) { try { this.server.close(); } catch { /* */ } }
    this.server = null; this.url = ''; this.tvName = ''; this.set({ mode: 'idle' });
  }

  /** Call when what's playing changes: every TV that's watching follows straight away. */
  update() { if (this.server) this.push(this.state()); }

  state() {
    const m = this.media();
    const j = { now: Date.now(), phone: this.deviceName, theme: this.getTheme() };
    if (this.themeChanged) { j.themeChanged = true; this.themeChanged = false; }
    if (m && m.title) {
      j.media = { title: m.title, artist: m.artist || '', playing: !!m.playing, pos: m.pos || 0, dur: m.dur || 0, at: m.at || Date.now(),
        art: m.art ? `/${this.code}/art?v=${crypto.createHash('md5').update(m.title + '|' + (m.artist || '') + '|' + m.art.length).digest('hex').slice(0, 8)}` : '',
        repeat: null, shuffle: null }; // Windows / Linux media controls don't expose repeat or shuffle to other apps
    }
    return j;
  }

  push(obj) {
    const line = obj == null ? 'event: ping\ndata: 1\n\n' : `data: ${JSON.stringify(obj)}\n\n`; // a real event, so the TV knows it's still connected
    for (const s of this.streams) { try { s.write(line); } catch { this.streams.delete(s); } }
  }

  handle(q, r) {
    const ip = q.socket.remoteAddress || '';
    if (!isLan(ip)) { r.destroy(); return; }
    const u = new URL(q.url, 'http://x'); const seg = u.pathname.split('/').filter(Boolean);
    const send = (code, type, body, extra = {}) => { r.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store', ...extra }); r.end(body); };
    if (!seg.length) return send(200, 'text/html; charset=utf-8', CODE_PAGE);
    const hits = (this.misses.get(ip) || []).filter((t) => Date.now() - t < 60000); this.misses.set(ip, hits);
    if (hits.length >= 20) return send(429, 'text/plain', 'Too many tries');
    if (seg[0].toUpperCase() !== this.code) { hits.push(Date.now()); return send(404, 'text/html; charset=utf-8', CODE_PAGE.replace('<!--msg-->', 'That code didn’t match. Check the code in Nebula.')); }
    if (seg.length === 1 && !u.pathname.endsWith('/')) return send(302, 'text/plain', '', { Location: `/${seg[0]}/` });
    switch (seg[1]) {
      case undefined: return send(200, 'text/html; charset=utf-8', fs.readFileSync(this.pagePath));
      case 'state': return send(200, 'application/json', JSON.stringify(this.state()));
      case 'f': {
        const name = seg[2] || '';
        if (!/^[a-z_]+\.ttf$/.test(name)) return send(404, 'text/plain', '');
        try { return send(200, 'font/ttf', fs.readFileSync(path.join(path.dirname(this.pagePath), 'fonts', name)), { 'Cache-Control': 'max-age=604800' }); } catch { return send(404, 'text/plain', ''); }
      }
      case 'art': {
        const m = this.media(); const d = m && m.art && /^data:([^;]+);base64,(.*)$/.exec(m.art);
        return d ? send(200, d[1], Buffer.from(d[2], 'base64'), { 'Cache-Control': 'max-age=86400' }) : send(404, 'text/plain', '');
      }
      case 'cmd': {
        if (q.method !== 'POST') return send(405, 'text/plain', '');
        const c = u.searchParams.get('c');
        if (['toggle', 'next', 'prev'].includes(c)) { this.control(c); return send(204, 'text/plain', ''); }
        return send(400, 'text/plain', '');
      }
      case 'events':
        r.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
        r.write(`retry: 2000\n\ndata: ${JSON.stringify(this.state())}\n\n`);
        this.streams.add(r); q.on('close', () => { this.streams.delete(r); this.onChange(this.status()); });
        this.onChange(this.status());
        return undefined;
      default: return send(404, 'text/plain', '');
    }
  }
}

const CODE_PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mood PLAY</title>
<style>html,body{height:100%;margin:0;background:linear-gradient(180deg,#a7adba,#2c2f3b);font-family:"Open Sans","Samsung One",Roboto,Arial,sans-serif;color:#111;display:grid;place-items:center}
.b{text-align:center}h1{font-size:6vw;margin:0 0 2vw;letter-spacing:-.2vw}h1 i{display:inline-block;width:5.6vw;height:2.6vw;border:.35vw solid #111;border-radius:2vw;background:linear-gradient(90deg,#ff3a2a,#ff8f3f);vertical-align:-.2vw}
p{font-size:2.2vw;margin:0 0 2vw;opacity:.75}input{font-size:5vw;width:24vw;text-align:center;letter-spacing:1.2vw;text-transform:uppercase;border:0;border-radius:2vw;padding:1vw;background:#fff}
button{font-size:2.4vw;margin-left:1vw;padding:1.2vw 2.4vw;border:0;border-radius:2vw;background:#111;color:#fff}.m{color:#a00;font-weight:700}</style></head>
<body><div class="b"><h1>m<i></i>od PLAY</h1><p>Type the 6-letter code shown in Nebula</p><p class="m"><!--msg--></p>
<form onsubmit="location.href='/'+document.getElementById('c').value.trim().toUpperCase()+'/';return false"><input id="c" maxlength="6" autofocus autocomplete="off"><button>Open</button></form></div></body></html>`;

/** Where the shared TV page lives: next to the app when packaged, in the repo's shared/ folder in development. */
function pagePath(app) {
  const packaged = path.join(process.resourcesPath || '', 'moodplay', 'tv.html');
  if (app && app.isPackaged && fs.existsSync(packaged)) return packaged;
  return path.join(__dirname, '..', '..', 'shared', 'moodplay', 'tv.html');
}

module.exports = { MoodPlay, findTvs, wsConnect, describe, newCode, isLan, pagePath };
