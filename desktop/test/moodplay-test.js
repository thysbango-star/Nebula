'use strict';
// Mood PLAY (desktop): the server, the codes, the live updates, the TV remote commands, and opening the page on a
// (fake) Samsung TV that makes you press Allow first. Runs without Electron.
const assert = require('assert');
const http = require('http');
const crypto = require('crypto');
const path = require('path');
const { MoodPlay, describe, isLan } = require('../src/moodplay');

const get = (url, opts = {}) => new Promise((res, rej) => {
  const r = http.request(url, { method: opts.method || 'GET' }, (s) => { const c = []; s.on('data', (d) => c.push(d)); s.on('end', () => res({ status: s.statusCode, headers: s.headers, body: Buffer.concat(c) })); });
  r.on('error', rej); r.end();
});

// A fake Samsung TV: the remote-control socket on 8001 that waits a moment ("Allow?") before saying yes.
function fakeSamsung(port) {
  const seen = { launched: null, asked: false, path: '' };
  const srv = http.createServer();
  srv.on('upgrade', (req, sock) => {
    seen.path = req.url;
    const acc = crypto.createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acc}\r\n\r\n`);
    const out = (s) => { const b = Buffer.from(s); sock.write(Buffer.concat([Buffer.from(b.length < 126 ? [0x81, b.length] : [0x81, 126, b.length >> 8, b.length & 255]), b])); };
    setTimeout(() => out(JSON.stringify({ event: 'ms.channel.connect', data: { token: 'TOKEN123' } })), 3200); // the user presses Allow
    let buf = Buffer.alloc(0);
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= 2) {
        let len = buf[1] & 127, off = 2; if (len === 126) { len = buf.readUInt16BE(2); off = 4; }
        const mask = buf.subarray(off, off + 4); off += 4; if (buf.length < off + len) return;
        const data = Buffer.from(buf.subarray(off, off + len).map((b, i) => b ^ mask[i % 4])); const op = buf[0] & 15; buf = buf.subarray(off + len);
        if (op === 1) { const j = JSON.parse(data.toString()); if (j.params && j.params.event === 'ed.apps.launch') seen.launched = j.params.data; }
      }
    });
    sock.on('error', () => {});
  });
  return new Promise((res) => srv.listen(port, '127.0.0.1', () => res({ srv, seen })));
}

(async () => {
  assert.ok(isLan('192.168.1.4') && isLan('10.0.0.2') && isLan('172.20.1.1') && !isLan('8.8.8.8') && !isLan('172.32.0.1'));

  let media = { title: 'GNX', artist: 'Kendrick Lamar', playing: true, pos: 70000, dur: 250000, at: Date.now(),
    art: 'data:image/png;base64,' + Buffer.from('fakepng').toString('base64') };
  const cmds = []; const changes = []; let saved = {}; let theme = 'auto';
  const mp = new MoodPlay({ lanIp: () => '127.0.0.1', media: () => media, control: (c) => cmds.push(c), deviceName: 'Test PC',
    pagePath: path.join(__dirname, '..', '..', 'shared', 'moodplay', 'tv.html'), getTvs: () => saved, saveTvs: (o) => { saved = o; },
    getTheme: () => theme, setTheme: (t) => { theme = t; }, onChange: (s) => changes.push(s) });
  assert.ok(await mp.start());
  const base = `http://127.0.0.1:${mp.port}`;
  assert.match(mp.url, /^http:\/\/127\.0\.0\.1:\d+\/[A-Z2-9]{6}\/$/);
  assert.ok(!/[01OIL]/.test(mp.code), 'codes avoid look-alike characters');

  // Pages, codes, rate limits.
  assert.match((await get(base + '/')).body.toString(), /6-letter code/);
  assert.strictEqual((await get(base + '/WRONG1/')).status, 404);
  const page = await get(`${base}/${mp.code}/`); assert.strictEqual(page.status, 200); assert.match(page.body.toString(), /Mood PLAY/);
  assert.strictEqual((await get(`${base}/${mp.code.toLowerCase()}/state`)).status, 200, 'codes are case-insensitive (typed on a remote)');
  const st = JSON.parse((await get(`${base}/${mp.code}/state`)).body.toString());
  assert.strictEqual(st.media.title, 'GNX'); assert.strictEqual(st.phone, 'Test PC'); assert.ok(st.media.art.startsWith(`/${mp.code}/art?v=`));
  assert.strictEqual(st.media.repeat, null, 'repeat/shuffle shown as unavailable, not faked');
  const art = await get(base + st.media.art); assert.strictEqual(art.body.toString(), 'fakepng'); assert.strictEqual(art.headers['content-type'], 'image/png');
  assert.strictEqual((await get(`${base}/${mp.code}/cmd?c=next`)).status, 405, 'commands need POST');
  assert.strictEqual((await get(`${base}/${mp.code}/cmd?c=next`, { method: 'POST' })).status, 204);
  assert.strictEqual((await get(`${base}/${mp.code}/cmd?c=rm`, { method: 'POST' })).status, 400);
  assert.deepStrictEqual(cmds, ['next']);

  // Live updates: the TV gets the new song as soon as it changes.
  const events = await new Promise((resolve) => {
    const got = [];
    http.get(`${base}/${mp.code}/events`, (s) => {
      s.on('data', (d) => {
        for (const m of String(d).split('\n\n')) if (m.startsWith('data: ')) got.push(JSON.parse(m.slice(6)));
        if (got.length === 1) { media = { ...media, title: 'Ordinary People', artist: 'Bugzy Malone' }; mp.update(); }
        if (got.length === 2) { s.destroy(); resolve(got); }
      });
    });
  });
  assert.strictEqual(events[0].media.title, 'GNX'); assert.strictEqual(events[1].media.title, 'Ordinary People');
  mp.theme('waves'); assert.strictEqual(theme, 'waves'); assert.strictEqual(mp.state().theme, 'waves');
  // The font travels with the page; anything else in that folder is refused.
  const font = await get(`${base}/${mp.code}/f/manrope_bold.ttf`); assert.strictEqual(font.status, 200); assert.ok(font.body.length > 50000);
  assert.strictEqual((await get(`${base}/${mp.code}/f/..%2Ftv.html`)).status, 404);
  // The heartbeat is a real event the TV page listens for (a comment line would be invisible to it).
  const beat = await new Promise((resolve) => {
    http.get(`${base}/${mp.code}/events`, (s) => { let all = ''; s.on('data', (d) => { all += d; if (all.includes('event: ping')) { s.destroy(); resolve(true); } }); setTimeout(() => mp.push(null), 200); });
  });
  assert.ok(beat);

  // Rate limit: 20 wrong codes in a minute and that address is told to stop.
  for (let i = 0; i < 20; i++) await get(base + '/ZZZZZZ/');
  assert.strictEqual((await get(`${base}/${mp.code}/state`)).status, 429);
  mp.misses.clear();

  // Device descriptions: a name from the TV's own XML, speakers left out.
  const xmlSrv = http.createServer((q, r) => r.end(q.url === '/tv.xml'
    ? '<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Living Room</friendlyName><manufacturer>Samsung Electronics</manufacturer><modelName>QA65Q80</modelName></device></root>'
    : '<root><device><friendlyName>Kitchen</friendlyName><manufacturer>Sonos</manufacturer><modelName>Sonos One Speaker</modelName></device></root>'));
  await new Promise((r) => xmlSrv.listen(0, '127.0.0.1', r));
  const xp = xmlSrv.address().port;
  const tv = await describe('127.0.0.1', `http://127.0.0.1:${xp}/tv.xml`, null);
  assert.deepStrictEqual([tv.kind, tv.name], ['samsung', 'Living Room']);
  assert.strictEqual(await describe('127.0.0.1', `http://127.0.0.1:${xp}/speaker.xml`, null), null, 'speakers are not TVs');
  assert.strictEqual(await describe('127.0.0.1', `http://127.0.0.2:${xp}/tv.xml`, null), null, 'descriptions must come from the device that answered');
  xmlSrv.close();

  // Opening on a (fake) Samsung TV: "Allow" prompt first, then the browser launches with our address; the token is kept.
  const fake = await fakeSamsung(8001);
  mp.tvs = [{ id: 'samsung:127.0.0.1', name: 'Living Room', kind: 'samsung', ip: '127.0.0.1' }];
  changes.length = 0;
  await mp.connect('samsung:127.0.0.1');
  assert.ok(changes.some((c) => c.mode === 'opening' && c.asking), 'asked the user to press Allow on the TV');
  assert.ok(fake.seen.launched && fake.seen.launched.appId === 'org.tizen.browser' && fake.seen.launched.metaTag === mp.url, 'TV browser opened on our page');
  assert.match(fake.seen.path, /samsung\.remote\.control\?name=/);
  assert.strictEqual(saved['samsung:127.0.0.1'], 'TOKEN123', 'the TV’s yes is remembered');
  assert.strictEqual(mp.ui.mode, 'live');
  fake.srv.close();

  mp.stop();
  assert.strictEqual(mp.server, null);
  console.log('Mood PLAY OK');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
