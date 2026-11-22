'use strict';
// Checks the background listening process (ears.js) without Electron: hears "Hey Nebula",
// ignores "Hey Nebraska", records a question, and treats silence as "nothing said".
// Run with WAKE=0 to skip the wake word engine (machines without sherpa-onnx).
const { EventEmitter } = require('events');
const fs = require('fs');
const path = require('path');

const parent = new EventEmitter();
const got = [];
parent.postMessage = (m) => got.push(m);
process.parentPort = parent;
require('../src/ears.js');
const send = (data) => parent.emit('message', { data, ports: [] });

function wav16(file) {
  const b = fs.readFileSync(path.join(__dirname, file));
  const start = b.indexOf('data') + 8; const n = (b.length - start) / 2;
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = b.readInt16LE(start + i * 2) / 32768;
  return out;
}
function feed(samples) { for (let i = 0; i < samples.length; i += 800) send({ type: 'audio', samples: samples.slice(i, i + 800) }); }
const fail = (m) => { console.error('FAIL:', m, JSON.stringify(got.map((x) => x.type))); process.exit(1); };

const useWake = process.env.WAKE !== '0';
if (useWake) {
  send({ type: 'config', modelDir: path.join(__dirname, '..', 'models', 'kws'), threshold: 0.19, wake: true });
  if (!got.some((m) => m.type === 'ready' && m.wake)) fail('wake word did not load');
  feed(wav16('hey-nebraska.wav')); feed(new Float32Array(16000));
  if (got.some((m) => m.type === 'wake')) fail('woke on "Hey Nebraska"');
  feed(wav16('hey-nebula.wav')); feed(new Float32Array(16000));
  if (!got.some((m) => m.type === 'wake')) fail('did not wake on "Hey Nebula"');
}
// Record a question: quiet, speech, then a pause.
got.length = 0;
send({ type: 'listen', token: 7, opts: {} });
send({ type: 'levels', on: true });
feed(new Float32Array(4800)); feed(wav16('hey-nebula.wav')); feed(new Float32Array(16000 * 1.5));
const u = got.find((m) => m.type === 'utterance');
if (!u || u.token !== 7 || !u.wav || u.wav.length < 10000) fail('question was not recorded');
if (!got.some((m) => m.type === 'level')) fail('no mic levels');
// Follow-up window: nothing said within 5 s -> null.
got.length = 0;
send({ type: 'listen', token: 8, opts: { waitSeconds: 5 } });
feed(new Float32Array(16000 * 5.5));
const s = got.find((m) => m.type === 'utterance');
if (!s || s.wav !== null) fail('silence should end the follow-up');
console.log(`Listening process OK${useWake ? ' (wake word included)' : ''}`);
process.exit(0);
