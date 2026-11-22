'use strict';
// Nebula's ears: runs in its own background process (Electron utilityProcess), so listening for
// "Hey Nebula" and recording questions never slows down the windows or the rest of the PC.
//
// Audio arrives from the hidden mic window over a direct MessagePort (16 kHz mono, 50 ms chunks).
// Main process → here:  {type:'config', modelDir, threshold, wake}  {type:'listen', token, opts}
//                       {type:'finish'}  {type:'cancel'}  {type:'levels', on}
// Here → main process:  {type:'ready'|'error'|'wake'|'level'|'utterance'}

const { WakeWord, Utterance, levelOf, toWav } = require('./voice');

const parent = process.parentPort;
let wake = null;
let wakeOn = false;
let utter = null;       // the question being recorded
let levels = false;     // send mic levels (only while a session is on screen)
let lastLevel = 0;
let busy = false;       // drop audio if decoding ever falls behind, instead of piling up

function post(msg) { try { parent.postMessage(msg); } catch { /* main is gone */ } }

function configure({ modelDir, threshold, wake: enabled }) {
  wakeOn = !!enabled;
  if (!wakeOn) { wake = null; post({ type: 'ready', wake: false }); return; }
  try {
    wake = new WakeWord(modelDir, threshold);
    post({ type: 'ready', wake: true });
  } catch (e) {
    wake = null;
    post({ type: 'error', message: `Wake word unavailable: ${e.message}` });
  }
}

function onAudio(samples) {
  if (busy) return;
  busy = true;
  try {
    if (levels) {
      const now = Date.now();
      if (now - lastLevel > 45) { lastLevel = now; post({ type: 'level', level: levelOf(samples).level }); }
    }
    if (utter) utter.push(samples);
    else if (wakeOn && wake && wake.accept(samples)) post({ type: 'wake' });
  } catch (e) {
    post({ type: 'error', message: `Listening hiccup: ${e.message}` });
  } finally { busy = false; }
}

function listen(token, opts = {}) {
  if (utter) utter.cancel();
  utter = new Utterance((audio) => {
    utter = null;
    if (wake) wake.reset();
    post({ type: 'utterance', token, wav: audio ? toWav(audio) : null });
  }, opts);
}

parent.on('message', (e) => {
  const m = e.data || {};
  if (m.type === 'port' && e.ports && e.ports[0]) {
    const port = e.ports[0];
    port.on('message', (ev) => { const d = ev.data; onAudio(d instanceof Float32Array ? d : new Float32Array(d)); });
    port.start();
  } else if (m.type === 'audio') onAudio(m.samples instanceof Float32Array ? m.samples : new Float32Array(m.samples));
  else if (m.type === 'config') configure(m);
  else if (m.type === 'listen') listen(m.token, m.opts);
  else if (m.type === 'finish') { if (utter) utter.stop(); }
  else if (m.type === 'cancel') { if (utter) { utter.cancel(); utter = null; } if (wake) wake.reset(); }
  else if (m.type === 'levels') levels = !!m.on;
});
