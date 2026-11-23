'use strict';
// Linux: what's playing, through MPRIS (every mainstream player — Spotify, Firefox/Chrome media, VLC,
// Rhythmbox… — publishes it) read with `playerctl`. Same shape as the Windows helper's media().

const { execFile } = require('child_process');
const fs = require('fs');

const run = (args) => new Promise((resolve) => {
  execFile('playerctl', args, { timeout: 3000 }, (err, out) => resolve(err ? null : String(out).trim()));
});

class LinuxMedia {
  constructor() { this.lastKey = ''; this.available = null; }

  async media() {
    if (this.available === false) return { none: true, missing: 'playerctl' };
    const sep = '\u001f';
    const out = await run(['metadata', '--format', ['{{title}}', '{{artist}}', '{{status}}', '{{position}}', '{{mpris:length}}', '{{mpris:artUrl}}', '{{playerName}}'].join(sep)]);
    if (out === null) {
      // Missing tool vs. nothing playing: `playerctl --version` tells them apart.
      if (this.available === null) this.available = (await run(['--version'])) !== null;
      return this.available ? { none: true } : { none: true, missing: 'playerctl' };
    }
    this.available = true;
    const [title, artist, status, pos, len, artUrl, app] = out.split(sep);
    if (!title) return { none: true };
    const m = { title, artist, app, playing: status === 'Playing', pos: Math.round((+pos || 0) / 1000), dur: Math.round((+len || 0) / 1000), at: Date.now() };
    const key = `${title}|${artist}`;
    if (key !== this.lastKey) {
      this.lastKey = key;
      m.art = await this.art(artUrl).catch(() => '');
    }
    return m;
  }

  async art(url) {
    if (!url) return '';
    if (url.startsWith('file://')) {
      const p = decodeURIComponent(url.slice(7));
      const b = await fs.promises.readFile(p);
      return `data:${/\.png$/i.test(p) ? 'image/png' : 'image/jpeg'};base64,${b.toString('base64')}`;
    }
    if (/^https?:/.test(url)) {
      const r = await fetch(url, { signal: AbortSignal.timeout(6000) }); if (!r.ok) return '';
      return `data:${r.headers.get('content-type') || 'image/jpeg'};base64,${Buffer.from(await r.arrayBuffer()).toString('base64')}`;
    }
    return '';
  }

  mediaControl(what) { return run([{ toggle: 'play-pause', next: 'next', prev: 'previous' }[what] || 'play-pause']); }
  mediaReset() { this.lastKey = ''; return Promise.resolve('ok'); }
}

module.exports = { LinuxMedia };
