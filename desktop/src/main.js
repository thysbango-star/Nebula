'use strict';
// Nebula for Windows — main process.
// Owns the windows (island, glow, settings, hidden mic), the hotkey, the tray,
// the "Hey Nebula" wake word, and the listen → think → speak flow.

const {
  app, BrowserWindow, Tray, Menu, ipcMain, globalShortcut, screen, nativeImage, safeStorage, Notification, shell,
} = require('electron');
const fs = require('fs');
const path = require('path');
const { Brain, cleanKey } = require('./brain');
const { WakeWord, Utterance, wakeThreshold, levelOf, toWav } = require('./voice');

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.setAppUserModelId('com.nebula.desktop');

// ------------------------------------------------------------------ config

const CONFIG_PATH = () => path.join(app.getPath('userData'), 'config.json');
const DEFAULTS = {
  wakeEnabled: true,
  sensitivity: 0.5,
  intensity: 0.75,
  hotkey: 'Alt+Space',
  speak: true,
  voice: '',
  startWithWindows: true,
  islandMode: 'always', // 'always' | 'active'
  model: '',
  noSearchModel: '',
  groqKeyEnc: '',
};
let configData = { ...DEFAULTS };
const config = {
  get: (k) => configData[k],
  set: (k, v) => { configData[k] = v; saveConfig(); },
};
function loadConfig() {
  try { configData = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(CONFIG_PATH(), 'utf8')) }; } catch { /* first run */ }
}
function saveConfig() {
  try { fs.mkdirSync(path.dirname(CONFIG_PATH()), { recursive: true }); fs.writeFileSync(CONFIG_PATH(), JSON.stringify(configData, null, 2)); } catch { /* ignore */ }
}
function getKey() {
  const enc = configData.groqKeyEnc;
  if (!enc) return '';
  try {
    return safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(Buffer.from(enc, 'base64'))
      : Buffer.from(enc, 'base64').toString('utf8');
  } catch { return ''; }
}
function setKey(raw) {
  const key = cleanKey(raw);
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(key) : Buffer.from(key, 'utf8');
  configData.groqKeyEnc = key ? buf.toString('base64') : '';
  configData.noSearchModel = '';
  saveConfig();
}

// ------------------------------------------------------------------ windows

let island; let glow; let ear; let settings; let tray;
const ISLAND_W = 780;
const ISLAND_H = 520;
const asset = (f) => path.join(__dirname, f);
const iconPath = () => asset('icon.png');

const baseWebPrefs = {
  preload: asset('preload.js'),
  contextIsolation: true,
  nodeIntegration: false,
  backgroundThrottling: false,
};

function primaryBounds() { return screen.getPrimaryDisplay().bounds; }

function createIsland() {
  const b = primaryBounds();
  island = new BrowserWindow({
    x: Math.round(b.x + (b.width - ISLAND_W) / 2), y: b.y, width: ISLAND_W, height: ISLAND_H,
    transparent: true, frame: false, resizable: false, movable: false, hasShadow: false,
    skipTaskbar: true, alwaysOnTop: true, show: false, fullscreenable: false, focusable: true,
    backgroundColor: '#00000000', webPreferences: baseWebPrefs,
  });
  island.setAlwaysOnTop(true, 'screen-saver');
  island.setVisibleOnAllWorkspaces(true);
  island.setIgnoreMouseEvents(true, { forward: true });
  island.loadFile(asset('island.html'));
  island.once('ready-to-show', () => {
    if (configData.islandMode === 'always') island.showInactive();
    pushIslandState();
  });
}

function createGlow() {
  const b = primaryBounds();
  glow = new BrowserWindow({
    x: b.x, y: b.y, width: b.width, height: b.height,
    transparent: true, frame: false, resizable: false, movable: false, hasShadow: false,
    skipTaskbar: true, alwaysOnTop: true, show: false, focusable: false, fullscreenable: false,
    backgroundColor: '#00000000', webPreferences: baseWebPrefs,
  });
  glow.setAlwaysOnTop(true, 'screen-saver');
  glow.setVisibleOnAllWorkspaces(true);
  glow.setIgnoreMouseEvents(true);
  glow.loadFile(asset('glow.html'));
}

function createEar() {
  ear = new BrowserWindow({ show: false, width: 200, height: 200, webPreferences: baseWebPrefs });
  ear.loadFile(asset('ear.html'));
}

function openSettings() {
  if (settings && !settings.isDestroyed()) { settings.show(); settings.focus(); return; }
  settings = new BrowserWindow({
    width: 560, height: 820, minWidth: 460, minHeight: 600, title: 'Nebula',
    backgroundColor: '#07060F', autoHideMenuBar: true, icon: iconPath(), show: false,
    webPreferences: baseWebPrefs,
  });
  settings.loadFile(asset('settings.html'));
  settings.once('ready-to-show', () => settings.show());
}

function repositionWindows() {
  const b = primaryBounds();
  if (island) island.setBounds({ x: Math.round(b.x + (b.width - ISLAND_W) / 2), y: b.y, width: ISLAND_W, height: ISLAND_H });
  if (glow) glow.setBounds({ x: b.x, y: b.y, width: b.width, height: b.height });
}

function send(win, channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, data);
}

// ------------------------------------------------------------------ tray + hotkeys

function createTray() {
  let img = nativeImage.createFromPath(iconPath());
  if (!img.isEmpty()) img = img.resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.setToolTip('Nebula');
  tray.on('click', () => toggleSession('tray'));
  refreshTrayMenu();
}

function refreshTrayMenu() {
  if (!tray) return;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: `Talk to Nebula   (${configData.hotkey})`, click: () => startSession('tray') },
    { type: 'separator' },
    {
      label: 'Listen for “Hey Nebula”', type: 'checkbox', checked: configData.wakeEnabled,
      click: (i) => { config.set('wakeEnabled', i.checked); updateEar(); broadcastSettings(); },
    },
    {
      label: 'Always show the island', type: 'checkbox', checked: configData.islandMode === 'always',
      click: (i) => { config.set('islandMode', i.checked ? 'always' : 'active'); applyIslandMode(); broadcastSettings(); },
    },
    { label: 'Settings…', click: openSettings },
    { type: 'separator' },
    { label: 'Quit Nebula', click: () => { app.isQuitting = true; app.quit(); } },
  ]));
}

let registeredHotkey = null;
function registerHotkeys() {
  globalShortcut.unregisterAll();
  registeredHotkey = null;
  const wanted = configData.hotkey || DEFAULTS.hotkey;
  try { if (globalShortcut.register(wanted, () => toggleSession('hotkey'))) registeredHotkey = wanted; } catch { /* invalid */ }
  if (!registeredHotkey && wanted !== 'Ctrl+Alt+N') {
    try { if (globalShortcut.register('Ctrl+Alt+N', () => toggleSession('hotkey'))) registeredHotkey = 'Ctrl+Alt+N'; } catch { /* */ }
  }
  // The Copilot key on newer keyboards sends Win+Shift+F23.
  try { globalShortcut.register('Super+Shift+F23', () => toggleSession('copilot')); } catch { /* not supported */ }
  refreshTrayMenu();
}

function applyIslandMode() {
  if (!island) return;
  if (configData.islandMode === 'always' || session.active) island.showInactive();
  else island.hide();
}

function applyLoginItem() {
  try { app.setLoginItemSettings({ openAtLogin: !!configData.startWithWindows, args: ['--hidden'] }); } catch { /* */ }
}

// ------------------------------------------------------------------ wake word + mic

let wake = null;
let wakeError = '';
function modelDir() {
  return app.isPackaged ? path.join(process.resourcesPath, 'kws') : path.join(__dirname, '..', 'models', 'kws');
}
function initWake() {
  wake = null; wakeError = '';
  if (!configData.wakeEnabled) return;
  try {
    wake = new WakeWord(modelDir(), wakeThreshold(configData.sensitivity));
  } catch (e) {
    wakeError = `Wake word unavailable: ${e.message}`;
    console.error(wakeError);
  }
}

let earRunning = false;
function updateEar() {
  if (configData.wakeEnabled && !wake) initWake();
  const shouldRun = configData.wakeEnabled || session.active;
  if (shouldRun !== earRunning) {
    earRunning = shouldRun;
    send(ear, shouldRun ? 'ear:start' : 'ear:stop');
  }
}

let lastLevelSent = 0;
function onAudio(samples) {
  const { level } = levelOf(samples);
  const now = Date.now();
  if (session.active && now - lastLevelSent > 45) {
    lastLevelSent = now;
    send(glow, 'vibrance:level', level);
    send(island, 'island:level', level);
  }
  if (session.utterance) {
    session.utterance.push(samples);
  } else if (!session.active && wake) {
    try {
      if (wake.accept(samples)) startSession('wake');
    } catch (e) {
      console.error('wake word error', e);
    }
  }
}

// ------------------------------------------------------------------ the conversation

const brain = new Brain(config, getKey);
const session = {
  active: false, phase: 'idle', transcript: '', reply: '', sources: [], error: '',
  utterance: null, lastEnd: 0, endTimer: null, token: 0,
};
const timer = { endsAt: 0, label: '', handle: null };

function pushIslandState() {
  send(island, 'island:state', {
    active: session.active, phase: session.phase, transcript: session.transcript, reply: session.reply,
    sources: session.sources, timerEndsAt: timer.endsAt, intensity: configData.intensity,
    hasKey: !!getKey(), hotkey: registeredHotkey || configData.hotkey, wakeEnabled: configData.wakeEnabled && !!wake,
  });
  send(glow, 'vibrance:state', { active: session.active, phase: session.phase, intensity: configData.intensity });
}

function setPhase(phase, extra = {}) {
  Object.assign(session, { phase }, extra);
  pushIslandState();
}

function toggleSession(source) {
  if (session.active) {
    if (session.phase === 'listening' && session.utterance) session.utterance.stop();
    else endSession();
  } else {
    startSession(source);
  }
}

function startSession(source, typed) {
  clearTimeout(session.endTimer);
  if (Date.now() - session.lastEnd > 90_000) brain.resetConversation(); // follow-ups within 90 s keep context
  session.token += 1;
  Object.assign(session, { active: true, transcript: '', reply: '', sources: [], error: '' });
  island.showInactive();
  glow.showInactive();
  send(island, 'island:speak-stop');
  if (typed) {
    session.utterance = null;
    session.transcript = typed;
    think(typed, session.token);
    return;
  }
  setPhase('listening');
  const token = session.token;
  session.utterance = new Utterance((audio) => {
    session.utterance = null;
    if (token !== session.token) return;
    onUtterance(audio, token);
  });
  updateEar();
  if (wake) wake.reset();
}

async function onUtterance(audio, token) {
  if (!audio) { fail("I didn't catch that."); return; }
  setPhase('thinking');
  try {
    const text = await brain.transcribe(toWav(audio));
    if (token !== session.token) return;
    if (!text || /^[\s.,!?]*$/.test(text)) { fail("I didn't catch that."); return; }
    session.transcript = text;
    pushIslandState();
    await think(text, token);
  } catch (e) {
    if (token === session.token) fail(e.spoken || 'Voice input hiccup. Try again.');
  }
}

async function think(text, token) {
  setPhase('thinking');
  const r = await brain.respond(text);
  if (token !== session.token) return;
  if (r.action && r.action.type === 'timer') setTimer(r.action.seconds);
  session.reply = r.text;
  session.sources = r.sources || [];
  if (r.close) {
    setPhase('done');
    session.endTimer = setTimeout(endSession, 1400);
    return;
  }
  if (configData.speak) {
    setPhase('speaking');
    send(island, 'island:speak', { text: r.text, voice: configData.voice, token });
  } else {
    finishTurn(token);
  }
}

function finishTurn(token) {
  if (token !== session.token || !session.active) return;
  setPhase('done');
  const hold = Math.min(25_000, 7_000 + session.reply.length * 40);
  session.endTimer = setTimeout(endSession, hold);
}

function fail(message) {
  setPhase('error', { reply: message });
  session.endTimer = setTimeout(endSession, 4_500);
}

function endSession() {
  clearTimeout(session.endTimer);
  if (session.utterance) { session.utterance.cancel(); session.utterance = null; }
  session.token += 1;
  session.active = false;
  session.lastEnd = Date.now();
  setPhase('idle');
  send(island, 'island:speak-stop');
  setTimeout(() => {
    if (!session.active) {
      if (glow && !glow.isDestroyed()) glow.hide();
      applyIslandMode();
    }
  }, 900); // let the glow fade and the island settle first
  updateEar();
  if (wake) wake.reset();
}

function setTimer(seconds) {
  clearTimeout(timer.handle);
  if (!seconds) { timer.endsAt = 0; pushIslandState(); return; }
  timer.endsAt = Date.now() + seconds * 1000;
  timer.handle = setTimeout(() => {
    timer.endsAt = 0;
    pushIslandState();
    send(island, 'island:timer-done');
    send(glow, 'vibrance:pulse');
    glow.showInactive();
    setTimeout(() => { if (!session.active) glow.hide(); }, 4000);
    if (Notification.isSupported()) new Notification({ title: 'Nebula', body: "Time's up!", icon: iconPath() }).show();
  }, seconds * 1000);
  pushIslandState();
}

// ------------------------------------------------------------------ IPC

function broadcastSettings() {
  const payload = settingsPayload();
  send(settings, 'settings:data', payload);
  pushIslandState();
}
function settingsPayload() {
  return {
    ...configData, groqKeyEnc: undefined, hasKey: !!getKey(), activeHotkey: registeredHotkey,
    wakeStatus: !configData.wakeEnabled ? 'off' : wake ? 'listening' : (wakeError || 'starting'),
    version: app.getVersion(),
  };
}

function wireIpc() {
  ipcMain.on('ear:audio', (_e, data) => onAudio(data instanceof Float32Array ? data : new Float32Array(data)));
  ipcMain.on('ear:error', (_e, msg) => { console.error('mic', msg); if (session.active) fail('I can’t reach the microphone. Check Windows microphone privacy settings.'); });

  ipcMain.on('island:interactive', (_e, on) => island && island.setIgnoreMouseEvents(!on, { forward: true }));
  ipcMain.on('island:talk', () => toggleSession('island'));
  ipcMain.on('island:ask', (_e, text) => { if (String(text || '').trim()) startSession('typed', String(text).trim()); });
  ipcMain.on('island:spoken', (_e, token) => finishTurn(token));
  ipcMain.on('island:close', () => endSession());
  ipcMain.on('island:cancel-timer', () => setTimer(0));
  ipcMain.on('island:focus', () => { if (island) island.focus(); });
  ipcMain.on('open:url', (_e, url) => { if (/^https?:\/\//.test(url)) shell.openExternal(url); });
  ipcMain.on('open:settings', openSettings);

  ipcMain.handle('settings:get', () => settingsPayload());
  ipcMain.handle('settings:set', (_e, patch) => {
    const before = { ...configData };
    for (const [k, v] of Object.entries(patch || {})) {
      if (k === 'groqKey') setKey(v);
      else if (k in DEFAULTS && k !== 'groqKeyEnc') configData[k] = v;
    }
    saveConfig();
    if (before.sensitivity !== configData.sensitivity || before.wakeEnabled !== configData.wakeEnabled) { initWake(); updateEar(); }
    if (before.hotkey !== configData.hotkey) registerHotkeys();
    if (before.startWithWindows !== configData.startWithWindows) applyLoginItem();
    if (before.islandMode !== configData.islandMode) applyIslandMode();
    refreshTrayMenu();
    broadcastSettings();
    return settingsPayload();
  });
  ipcMain.handle('settings:test', (_e, key) => brain.test(key));
  ipcMain.on('settings:preview', () => {
    startSession('preview');
  });
}

// ------------------------------------------------------------------ app lifecycle

app.on('second-instance', () => openSettings());

app.whenReady().then(() => {
  loadConfig();
  // Allow the mic for our own windows only.
  const { session: electronSession } = require('electron');
  electronSession.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(permission === 'media'));
  electronSession.defaultSession.setPermissionCheckHandler((wc, permission) => permission === 'media');

  wireIpc();
  createGlow();
  createIsland();
  createEar();
  createTray();
  registerHotkeys();
  applyLoginItem();
  initWake();
  ear.webContents.once('did-finish-load', () => { earRunning = false; updateEar(); });

  screen.on('display-metrics-changed', repositionWindows);
  screen.on('display-added', repositionWindows);
  screen.on('display-removed', repositionWindows);

  if (!getKey() && !process.argv.includes('--hidden')) openSettings();
});

app.on('window-all-closed', (e) => e.preventDefault()); // keep running in the tray
app.on('will-quit', () => globalShortcut.unregisterAll());
