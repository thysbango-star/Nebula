'use strict';
// Beam: a PC sends to another PC over loopback (real sockets, real crypto), and the mDNS packets round-trip.
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { Beam, parse, packet, encName, encInstance, rr } = require('../src/beam');

(async () => {
  // mDNS records encode → parse
  const inst = 'Thys PC #1234';
  const p = packet(0x8400, [], [rr(encName('_nebulabeam._tcp.local'), 12, 120, encInstance(inst), false)],
    [rr(encInstance(inst), 33, 120, Buffer.concat([Buffer.from([0, 0, 0, 0, 0x1f, 0x90]), encName('nebula-ab.local')]), true)]);
  const m = parse(p);
  assert.strictEqual(m.response, true);
  assert.deepStrictEqual(m.records[0].ptr, [inst, '_nebulabeam', '_tcp', 'local']);
  assert.strictEqual(m.records[1].port, 8080);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'beam-'));
  const inDir = path.join(tmp, 'in'); fs.mkdirSync(inDir);
  const a = new Beam({ name: () => 'Receiver', type: 'linux', downloads: () => inDir, maxMb: () => 50 });
  const b = new Beam({ name: () => 'Sender', type: 'windows', downloads: () => tmp, maxMb: () => 50 });
  await a.listen();
  let offerCode = null; let senderCode = null; let last = null;
  a.on('offer', (o) => { offerCode = o.code; assert.strictEqual(o.from, 'Sender'); assert.strictEqual(o.items.length, 2); a.decide(true); });
  a.on('state', (s) => { last = s; });
  b.on('state', (s) => { if (s.code) senderCode = s.code; });
  const f1 = path.join(tmp, 'photo.jpg'); fs.writeFileSync(f1, require('crypto').randomBytes(300 * 1024));
  const f2 = path.join(tmp, 'notes.txt'); fs.writeFileSync(f2, 'hello from Nebula Beam');
  const res = await b.send({ name: 'Receiver', host: '127.0.0.1', port: a.port }, [f1, f2]);
  assert.ok(res.ok, JSON.stringify(res));
  assert.ok(/^\d{6}$/.test(offerCode)); assert.strictEqual(offerCode, senderCode, 'both screens show the same code');
  assert.strictEqual(last.kind, 'received');
  assert.ok(fs.readFileSync(path.join(inDir, 'Nebula Beam', 'photo.jpg')).equals(fs.readFileSync(f1)));
  assert.strictEqual(fs.readFileSync(path.join(inDir, 'Nebula Beam', 'notes.txt'), 'utf8'), 'hello from Nebula Beam');

  // Declined
  a.removeAllListeners('offer'); a.on('offer', () => a.decide(false));
  const no = await b.send({ name: 'Receiver', host: '127.0.0.1', port: a.port }, [f2]);
  assert.deepStrictEqual(no, { ok: false, reason: 'Transfer declined' });

  // Too big
  const big = new Beam({ name: () => 'Tiny', type: 'linux', downloads: () => inDir, maxMb: () => 0 });
  await big.listen();
  const tb = await b.send({ name: 'Tiny', host: '127.0.0.1', port: big.port }, [f1]);
  assert.strictEqual(tb.ok, false); assert.ok(/Too big/.test(tb.reason));

  // Nobody there
  const gone = await b.send({ name: 'Gone', host: '127.0.0.1', port: 1 }, [f2]);
  assert.deepStrictEqual(gone, { ok: false, reason: 'Recipient unavailable' });

  a.close(); b.close(); big.close();
  console.log('beam ok');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
