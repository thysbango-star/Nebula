'use strict';
// Voice plumbing for the main process: "Hey Nebula" detection, end-of-speech detection, WAV encoding.

const path = require('path');

const SAMPLE_RATE = 16000;

/** Offline "Hey Nebula" detector (same model and keywords as the Android app). */
class WakeWord {
  constructor(modelDir, threshold) {
    // Loaded lazily so the app still runs if the native module fails to load.
    const sherpa = require('sherpa-onnx-node');
    const f = (n) => path.join(modelDir, n);
    this.kws = new sherpa.KeywordSpotter({
      featConfig: { sampleRate: SAMPLE_RATE, featureDim: 80 },
      modelConfig: {
        transducer: { encoder: f('encoder.int8.onnx'), decoder: f('decoder.int8.onnx'), joiner: f('joiner.int8.onnx') },
        tokens: f('tokens.txt'),
        modelType: 'zipformer2',
        numThreads: 1,
        provider: 'cpu',
        debug: 0,
      },
      maxActivePaths: 4,
      keywordsFile: f('keywords.txt'),
      keywordsScore: 1.5,
      keywordsThreshold: threshold,
      numTrailingBlanks: 1,
    });
    this.stream = this.kws.createStream();
  }

  /** Feed 16 kHz mono samples; returns the keyword when "Hey Nebula" is heard. */
  accept(samples) {
    this.stream.acceptWaveform({ sampleRate: SAMPLE_RATE, samples });
    while (this.kws.isReady(this.stream)) {
      this.kws.decode(this.stream);
      const keyword = this.kws.getResult(this.stream).keyword;
      if (keyword) {
        this.kws.reset(this.stream);
        return keyword;
      }
    }
    return null;
  }

  /** Start fresh so old audio can't trigger a wake. */
  reset() {
    this.stream = this.kws.createStream();
  }
}

/** Same sensitivity mapping as Android (tested: Balanced caught 9/9 with 0/33 false wakes). */
function wakeThreshold(sensitivity) {
  return 0.30 - 0.22 * Math.min(1, Math.max(0, sensitivity));
}

/** Loudness 0..1 (≈ -60 dBFS silence → 0, -10 dBFS loud speech → 1). */
function levelOf(samples) {
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  const rms = Math.sqrt(sum / Math.max(1, samples.length));
  const db = 20 * Math.log10(rms + 1e-9);
  return { rms, level: Math.min(1, Math.max(0, (db + 60) / 50)) };
}

/**
 * Collects one spoken question: waits for speech, then stops after a short pause.
 * Calls onDone(Float32Array | null) — null when nothing was said.
 */
class Utterance {
  constructor(onDone, { maxSeconds = 15, waitSeconds = 7, silenceMs = 850 } = {}) {
    this.onDone = onDone;
    this.max = maxSeconds * SAMPLE_RATE;
    this.wait = waitSeconds * SAMPLE_RATE;
    this.silenceSamples = (silenceMs / 1000) * SAMPLE_RATE;
    this.chunks = [];
    this.preroll = [];
    this.total = 0;
    this.heard = false;
    this.quiet = 0;
    this.noise = [];
    this.finished = false;
  }

  push(samples) {
    if (this.finished) return;
    const { rms } = levelOf(samples);
    this.total += samples.length;

    // Learn the room's noise level from the first moments.
    if (this.noise.length < 3 && !this.heard) this.noise.push(rms);
    const floor = this.noise.length ? this.noise.reduce((a, b) => a + b, 0) / this.noise.length : 0.005;
    const threshold = Math.max(0.014, floor * 2.8);

    if (!this.heard) {
      this.preroll.push(samples);
      if (this.preroll.length > 4) this.preroll.shift(); // keep ~400 ms before speech starts
      if (rms > threshold) {
        this.heard = true;
        this.chunks.push(...this.preroll);
        this.preroll = [];
      } else if (this.total > this.wait) {
        return this.finish(null);
      }
      return;
    }

    this.chunks.push(samples);
    this.quiet = rms < threshold * 0.8 ? this.quiet + samples.length : 0;
    if (this.quiet >= this.silenceSamples || this.total > this.max) this.finish(concat(this.chunks));
  }

  /** User pressed stop: use what we have. */
  stop() {
    this.finish(this.heard ? concat(this.chunks) : null);
  }

  cancel() { this.finished = true; }

  finish(audio) {
    if (this.finished) return;
    this.finished = true;
    this.onDone(audio);
  }
}

function concat(chunks) {
  const n = chunks.reduce((a, c) => a + c.length, 0);
  const out = new Float32Array(n);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

/** 16-bit PCM mono WAV. */
function toWav(samples, sampleRate = SAMPLE_RATE) {
  const buf = Buffer.alloc(44 + samples.length * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + samples.length * 2, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sampleRate, 24); buf.writeUInt32LE(sampleRate * 2, 28); buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(samples.length * 2, 40);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    buf.writeInt16LE(s < 0 ? s * 0x8000 : s * 0x7fff, 44 + i * 2);
  }
  return buf;
}

module.exports = { WakeWord, Utterance, wakeThreshold, levelOf, toWav, SAMPLE_RATE };
