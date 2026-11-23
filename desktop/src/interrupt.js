'use strict';
// Voice Interrupt (desktop): talk over Nebula and it stops and listens — same rules as the Android app.
//
// SPEAKING → INTERRUPT_DETECTED (voice stopped at once) → CAPTURING_INTERRUPT (your words, until you pause)
// → PROCESSING_INTERRUPT (Whisper + what kind of interruption) → RESUMING (same conversation) or CANCELLED ("stop").
//
// Detection is deliberately light: no speech model runs while Nebula talks, only a voice-activity check on the mic
// (Chromium's echo canceller + noise suppressor are on), which needs sustained, voice-loud sound well above the
// learned echo/room level. Only once it fires does Whisper run on what you said.

/** Thresholds from the sensitivity setting (0..1). PCs are treated as loudspeaker setups: stricter. */
function tuning(sens = 0.5, speaker = true) {
  const s = Math.min(1, Math.max(0, Number(sens) || 0));
  const ratio = 4.2 - 2.0 * s;      // how far above the room/echo level your voice has to be
  const minRms = 0.030 - 0.016 * s; // and an absolute floor (≈ -30 to -40 dBFS)
  const frames = Math.trunc(9 - 4 * s); // sustained for 450 → 250 ms (50 ms frames)
  return speaker ? { ratio: ratio * 1.35, minRms: minRms * 1.4, frames: frames + 1 } : { ratio, minRms, frames };
}

/**
 * Fed one 50 ms frame's RMS at a time. Learns the echo/room floor from frames that aren't speech, and fires after
 * `frames` loud frames in a row (one slightly quieter frame is forgiven, as between syllables).
 */
class Detector {
  constructor(t, warmupFrames = 8) { this.t = t; this.warm = warmupFrames; this.floor = -1; this.run = 0; this.gap = 0; this.seen = 0; }
  push(rms) {
    this.seen++;
    if (this.floor < 0) this.floor = rms;
    const thr = Math.max(this.t.minRms, this.floor * this.t.ratio);
    if (this.seen <= this.warm) { this.floor = this.floor * 0.8 + rms * 0.2; return false; } // voice starting up: learn
    if (rms > thr) { this.run++; this.gap = 0; }
    else {
      if (this.run > 0 && this.gap === 0 && rms > thr * 0.6) this.gap = 1; else { this.run = 0; this.gap = 0; }
      this.floor = this.floor * 0.95 + rms * 0.05;
    }
    return this.run >= this.t.frames;
  }
}

const KIND = { STOP: 'stop', SHORTEN: 'shorten', EXPAND: 'expand', VISUALISE: 'visualise', CLARIFY: 'clarify', REDIRECT: 'redirect' };

/** What kind of interruption this is. Short phrases only — anything else is a new direction. */
function classify(text) {
  const t = ` ${String(text).toLowerCase().replace(/[^a-z' ]/g, ' ').replace(/\s+/g, ' ').trim()} `;
  const words = t.trim().split(' ').length;
  const has = (...p) => p.some((x) => t.includes(` ${x} `));
  if (words <= 4 && has('stop', "that's enough", 'thats enough', 'enough', 'quiet', 'be quiet', 'cancel', 'shut up', 'never mind', 'nevermind', 'okay stop', 'ok stop')) return KIND.STOP;
  if (has('shorter', 'quickly', 'short version', 'one sentence', 'tldr', 'tl dr', 'in short', 'summarise', 'summarize', 'keep it short', 'too long', 'get to the point')) return KIND.SHORTEN;
  if (has('explain more', 'go deeper', 'more detail', 'the detail', 'details', 'tell me more', 'elaborate', 'expand', 'keep going', 'go on')) return KIND.EXPAND;
  if (has('show me', 'draw it', 'draw that', 'visualise', 'visualize', 'give me an example', 'an example', 'diagram', 'picture')) return KIND.VISUALISE;
  if (words <= 8 && has('what do you mean', 'explain that bit', 'why', 'what does that mean', 'how so', 'wait what', 'which one', "what's that")) return KIND.CLARIFY;
  return KIND.REDIRECT;
}

/** What Nebula is asked to do next, in the same conversation (so it keeps all the context). */
function followUp(kind, heard, saying, ack = true) {
  const cut = String(saying || '').trim().slice(-220);
  const a = ack ? ' Begin with a short, natural acknowledgement (like "Got you —" or "Sure —"), never a robotic one.' : '';
  switch (kind) {
    case KIND.SHORTEN: return `${heard}\n(I cut in while you were answering. Give me the short version of your last answer: one or two sentences.${a})`;
    case KIND.EXPAND: return `${heard}\n(I cut in: go deeper on your last answer with more detail.${a})`;
    case KIND.VISUALISE: return `${heard}\n(I cut in: show it visually if you can — e.g. a concrete worked example, or make a document with a table or diagram — otherwise walk me through an example.${a})`;
    case KIND.CLARIFY: return `${heard}\n(I cut in right after you said: "${cut}". Answer exactly that point, briefly.${a})`;
    case KIND.REDIRECT: return `${heard}\n(I interrupted your last answer — follow what I just said instead.${a})`;
    default: return heard;
  }
}

/** Whisper's usual "heard nothing" outputs — treated as unclear, so Nebula asks instead of guessing. */
const WHISPER_NOISE = new Set(['you', 'thank you', 'thanks for watching', 'thank you for watching', 'bye', 'uh', 'um', 'hmm', 'oh', 'ah']);

module.exports = { tuning, Detector, classify, followUp, KIND, WHISPER_NOISE };
