'use strict';
// Nebula desktop widgets: small glass cards that live on your desktop (Windows and Linux) — Voice, Music, Ask,
// Tasks, Sports and Daily — the same set as the Android home-screen widgets.
//
// Each widget is its own small frameless window you can drag anywhere; where you leave it is remembered.
// They show Nebula's real state (what's playing, your tasks, your teams) — pushed from the main process,
// never polled from the internet by the widgets themselves.
//
// (The Windows 11 Widgets board only accepts widgets from Store-packaged apps, so these sit on the desktop instead.)

const { BrowserWindow, screen } = require('electron');

const KINDS = {
  voice: { w: 168, h: 168, title: 'Voice' },
  music: { w: 380, h: 172, title: 'Music' },
  ask: { w: 380, h: 136, title: 'Ask Nebula' },
  tasks: { w: 320, h: 268, title: 'Tasks' },
  sports: { w: 360, h: 172, title: 'Sports' },
  daily: { w: 340, h: 236, title: 'Daily' },
};
const ORDER = ['daily', 'music', 'sports', 'tasks', 'ask', 'voice'];

class Widgets {
  /** @param {{asset:(f:string)=>string, webPrefs:object, config:()=>object, save:(patch:object)=>void}} o */
  constructor({ asset, webPrefs, config, save }) {
    Object.assign(this, { asset, webPrefs, config, save });
    this.wins = new Map();
    this.last = null;
  }

  enabled() { return (this.config().widgets || []).filter((k) => KINDS[k]); }

  /** Open the widgets that are switched on, close the rest. */
  sync() {
    const on = new Set(this.enabled());
    for (const [k, w] of this.wins) if (!on.has(k) || w.isDestroyed()) { if (!w.isDestroyed()) w.destroy(); this.wins.delete(k); }
    for (const k of ORDER) if (on.has(k) && !this.wins.has(k)) this.open(k);
    const top = !!this.config().widgetsOnTop;
    for (const w of this.wins.values()) if (!w.isDestroyed()) w.setAlwaysOnTop(top, top ? 'floating' : 'normal');
  }

  /** Where a widget goes the first time: stacked down the right of the main screen, out of the way. */
  defaultPos(k) {
    const wa = screen.getPrimaryDisplay().workArea;
    const gap = 14, top = wa.y + 70, colW = 400;
    let col = 0, y = top;
    for (const o of ORDER.filter((x) => x === k || this.enabled().includes(x))) {
      if (y + KINDS[o].h > wa.y + wa.height - 20 && y > top) { col++; y = top; } // out of room: next column leftwards
      if (o === k) return { x: wa.x + wa.width - 24 - col * colW - KINDS[k].w, y };
      y += KINDS[o].h + gap;
    }
    return { x: wa.x + wa.width - 24 - KINDS[k].w, y: top };
  }

  /** Keep a saved spot on a screen that still exists (monitors get unplugged). */
  onScreen(p, k) {
    const { w, h } = KINDS[k];
    const ok = screen.getAllDisplays().some((d) => p.x >= d.workArea.x - w / 2 && p.y >= d.workArea.y - 10 &&
      p.x + w / 2 <= d.workArea.x + d.workArea.width && p.y + h / 2 <= d.workArea.y + d.workArea.height);
    return ok ? p : null;
  }

  open(k) {
    const saved = (this.config().widgetPos || {})[k];
    const p = (saved && this.onScreen(saved, k)) || this.defaultPos(k);
    const w = new BrowserWindow({
      x: Math.round(p.x), y: Math.round(p.y), width: KINDS[k].w, height: KINDS[k].h,
      frame: false, transparent: true, resizable: false, maximizable: false, minimizable: false, fullscreenable: false,
      skipTaskbar: true, hasShadow: false, show: false, title: `Nebula ${KINDS[k].title}`,
      backgroundColor: '#00000000', webPreferences: this.webPrefs,
    });
    w.setMenuBarVisibility(false);
    w.loadFile(this.asset('widgets.html'), { query: { w: k } });
    w.once('ready-to-show', () => { w.showInactive(); if (this.last) w.webContents.send('widget:state', this.last); });
    let t = null;
    w.on('moved', () => { clearTimeout(t); t = setTimeout(() => this.remember(k, w), 300); });
    w.on('closed', () => this.wins.delete(k));
    this.wins.set(k, w);
  }

  remember(k, w) {
    if (w.isDestroyed()) return;
    const [x, y] = w.getPosition();
    this.save({ widgetPos: { ...(this.config().widgetPos || {}), [k]: { x, y } } });
  }

  resetPositions() {
    this.save({ widgetPos: {} });
    for (const [k, w] of this.wins) if (!w.isDestroyed()) { const p = this.defaultPos(k); w.setPosition(Math.round(p.x), Math.round(p.y)); }
  }

  /** New state for every open widget. Cheap: one small message per window, only when something changed. */
  push(state) {
    const key = JSON.stringify(state, (k, v) => (k === 'art' && typeof v === 'string' ? v.length : v));
    if (key === this.lastKey) return;
    this.lastKey = key; this.last = state;
    for (const w of this.wins.values()) if (!w.isDestroyed()) w.webContents.send('widget:state', state);
  }

  closeAll() { for (const w of this.wins.values()) if (!w.isDestroyed()) w.destroy(); this.wins.clear(); }
}

module.exports = { Widgets, KINDS, ORDER };
