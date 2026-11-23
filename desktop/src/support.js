'use strict';
// "Report to Claude" and "update available" for the desktop app (Windows and Linux).
//  - Reports open a pre-filled GitHub issue (label nebula-report) in the browser; the user checks it and presses
//    Submit. A scheduled Claude session reads new reports daily, fixes them and ships an update.
//  - Updates: the "desktop" release carries latest.json with the version; we recommend updating, never force it.

const os = require('os');
const REPO = 'thysbango-star/Nebula';
const trail = [];

function note(line) { trail.push(`${new Date().toISOString().slice(11, 19)} ${String(line).slice(0, 300)}`); while (trail.length > 30) trail.shift(); }

function scrub(s) {
  return String(s || '')
    .replace(/gsk_[A-Za-z0-9]{10,}/g, 'gsk_••••')
    .replace(/(sk|pk|ghp|gho|github_pat)_[A-Za-z0-9_]{10,}/g, '••••')
    .replace(/(api[_-]?key|token|authorization|bearer)\s*[:=]\s*\S+/gi, '$1: ••••')
    .replace(/[\w.+-]+@[\w-]+\.[\w.]+/g, '(email)')
    .replace(new RegExp(os.homedir().replace(/[\\^$.*+?()[\]{}|]/g, '\\$&'), 'gi'), '~');
}

function issueUrl(version, what, detail = '') {
  const t = trail.slice(-12).join('\n');
  const body = [
    '### What happened', scrub(what), '',
    detail ? ['### Details', '```', scrub(detail).slice(0, 3000), '```', ''].join('\n') : '',
    t ? ['### Recent errors', '```', scrub(t), '```', ''].join('\n') : '',
    '### Device', `- Nebula ${version} (${process.platform === 'win32' ? 'Windows' : process.platform === 'linux' ? 'Linux' : process.platform} app)`,
    `- ${os.type()} ${os.release()} ${os.arch()}`, '', '_Sent with **Report to Claude** from inside Nebula._',
  ].filter((x) => x !== '').join('\n');
  const title = `[Report] ${scrub(what).split('\n')[0].slice(0, 80)}`;
  return `https://github.com/${REPO}/issues/new?labels=nebula-report&title=${encodeURIComponent(title)}&body=${encodeURIComponent(body.slice(0, 6000))}`;
}

const newer = (a, b) => { const x = String(a).split('.').map(Number), y = String(b).split('.').map(Number); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; };

async function checkUpdate(current) {
  const r = await fetch(`https://github.com/${REPO}/releases/download/desktop/latest.json`, { signal: AbortSignal.timeout(10000) });
  if (!r.ok) return null;
  const j = await r.json();
  if (!j.version || !newer(j.version, current)) return null;
  return { version: j.version, notes: j.notes || '', url: `https://github.com/${REPO}/releases/tag/desktop` };
}

module.exports = { note, scrub, issueUrl, checkUpdate, newer, REPO };
