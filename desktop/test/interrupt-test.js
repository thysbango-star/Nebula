'use strict';
// Voice Interrupt (desktop): the detector ignores Nebula's own echo, catches you talking over it, and the
// interruption is understood in context. Mirrors the Android tests' expectations.
const assert = require('assert');
const { tuning, Detector, classify, followUp, KIND } = require('../src/interrupt');
const { Utterance, SAMPLE_RATE } = require('../src/voice');

const t = tuning(0.5, true);
// 1. Nebula's own voice leaking back (echo, ≈0.02 RMS, wobbling) for 4 seconds: never fires.
let d = new Detector(t); let fired = false;
for (let i = 0; i < 80; i++) if (d.push(0.02 + 0.006 * Math.sin(i))) fired = true;
assert.strictEqual(fired, false, 'echo of Nebula must not interrupt');
// 2. Then you speak, clearly louder, for ~0.6 s: fires within the sustained window.
let at = -1;
for (let i = 0; i < 14; i++) if (d.push(0.16) && at < 0) at = i;
assert.ok(at >= t.frames - 1 && at <= t.frames + 1, `fires after ~${t.frames} frames (got ${at})`);
// 3. A cough (2 loud frames) doesn't.
d = new Detector(t); for (let i = 0; i < 20; i++) d.push(0.01);
assert.strictEqual([0.2, 0.2, 0.01, 0.01, 0.01].some((r) => d.push(r)), false, 'a short bump is not an interruption');
// 4. A brief dip between syllables is forgiven.
d = new Detector(t); for (let i = 0; i < 20; i++) d.push(0.01);
const seq = [0.15, 0.15, 0.15, 0.08, 0.15, 0.15, 0.15, 0.15, 0.15, 0.15, 0.15];
assert.ok(seq.some((r) => d.push(r)), 'speech with a dip still counts');
// 5. Stricter at low sensitivity.
assert.ok(tuning(0, true).ratio > tuning(1, true).ratio && tuning(0, true).frames > tuning(1, true).frames);

// Classification.
const cases = { 'Stop.': KIND.STOP, "That's enough": KIND.STOP, 'Wait, shorter.': KIND.SHORTEN, 'Give me the short version': KIND.SHORTEN,
  'Go deeper': KIND.EXPAND, 'Show me.': KIND.VISUALISE, 'Wait, why?': KIND.CLARIFY, 'What do you mean?': KIND.CLARIFY,
  'Actually, make a reminder for 5pm': KIND.REDIRECT, 'No, I meant the other one': KIND.REDIRECT, 'Stop talking about football and tell me about rugby instead': KIND.REDIRECT };
for (const [text, kind] of Object.entries(cases)) assert.strictEqual(classify(text), kind, text);
assert.match(followUp(KIND.CLARIFY, 'Why?', 'the second step changes the outcome'), /second step changes the outcome/);
assert.match(followUp(KIND.SHORTEN, 'Shorter', '', true), /short version/);
assert.doesNotMatch(followUp(KIND.SHORTEN, 'Shorter', '', false), /acknowledgement/);

// The capture after an interruption keeps your first words and ends when you pause.
let got = null;
const u = new Utterance((a) => { got = a; }, { maxSeconds: 12, waitSeconds: 3, silenceMs: 800, noiseFloor: 0.02 });
const frame = (rms) => new Float32Array(SAMPLE_RATE / 20).fill(rms);
for (let i = 0; i < 12; i++) u.push(frame(0.15)); // the frames that set it off
for (let i = 0; i < 20 && !got; i++) u.push(frame(0.005)); // then you pause
assert.ok(got && got.length >= 12 * SAMPLE_RATE / 20, 'interruption captured, including its first words');
console.log('Voice Interrupt OK');
