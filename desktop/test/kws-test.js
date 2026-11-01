// Cloud-build check: loads the real wake word engine on Windows with Nebula's model
// and makes sure "Hey Nebula" wakes it and "Hey Nebraska" doesn't.
'use strict';
const path = require('path');
const sherpa = require('sherpa-onnx-node');
const { WakeWord, wakeThreshold } = require('../src/voice.js');

const modelDir = path.join(__dirname, '..', 'models', 'kws');

function run(file) {
  const ww = new WakeWord(modelDir, wakeThreshold(0.5));
  const wave = sherpa.readWave(path.join(__dirname, file));
  if (wave.sampleRate !== 16000) throw new Error(`${file}: expected 16 kHz, got ${wave.sampleRate}`);
  const samples = Float32Array.from(wave.samples);
  let heard = false;
  for (let i = 0; i < samples.length; i += 800) {
    if (ww.accept(samples.subarray(i, i + 800))) heard = true;
  }
  if (ww.accept(new Float32Array(16000 * 0.6))) heard = true; // trailing silence
  return heard;
}

const yes = run('hey-nebula.wav');
const no = run('hey-nebraska.wav');
console.log(`"Hey Nebula" detected: ${yes}`);
console.log(`"Hey Nebraska" detected: ${no}`);
if (!yes || no) { console.error('Wake word check FAILED'); process.exit(1); }
console.log('Wake word check passed');
