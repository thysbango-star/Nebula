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
const { WinHelper, NOT_GAMES } = require('./win32');

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
  voiceEngine: 'groq',   // 'groq' (natural Orpheus voice) | 'system' (Windows voice)
  groqVoice: 'troy',
  islandScale: 1,        // 0.7 – 1.5
  islandX: 0,            // px from centre
  islandY: 0,            // px down from the top
  r3cket: true,          // game mode
  r3cketQuiet: true,     // hide the resting island while gaming
  clickInterval: 100,    // ms between auto-clicks
  clickHotkey: 'Ctrl+Alt+K',
  games: [],             // extra .exe names that count as games
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
const clamp = (v, a, b) => Math.min(b, Math.max(a, Number(v) || 0));
function islandBounds() {
  const b = primaryBounds();
  const k = clamp(configData.islandScale || 1, 0.7, 1.5);
  const w = Math.round(ISLAND_W * k), h = Math.round(ISLAND_H * k);
  const x = Math.round(b.x + (b.width - w) / 2 + clamp(configData.islandX, -600, 600));
  return { x: clamp(x, b.x, b.x + b.width - w), y: b.y + Math.round(clamp(configData.islandY, 0, 80)), width: w, height: h };
}

function createIsland() {
  island = new BrowserWindow({
    ...islandBounds(),
    transparent: true, frame: false, resizable: false, movable: false, hasShadow: false,
    skipTaskbar: true, alwaysOnTop: true, show: false, fullscreenable: false, focusable: true,
    backgroundColor: '#00000000', webPreferences: baseWebPrefs,
  });
  island.setAlwaysOnTop(true, 'screen-saver');
  island.setVisibleOnAllWorkspaces(true);
  island.setIgnoreMouseEvents(true, { forward: true });
  island.loadFile(asset('island.html'));
  island.webContents.on('did-finish-load', () => island.webContents.setZoomFactor(clamp(configData.islandScale || 1, 0.7, 1.5)));
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
  if (island) { island.setBounds(islandBounds()); island.webContents.setZoomFactor(clamp(configData.islandScale || 1, 0.7, 1.5)); }
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
  // R3cket auto-clicker on/off.
  try { if (configData.clickHotkey) globalShortcut.register(configData.clickHotkey, () => clicker(clickerState.running ? 'stop' : 'start')); } catch { /* invalid */ }
  // The Copilot key on newer keyboards sends Win+Shift+F23.
  try { globalShortcut.register('Super+Shift+F23', () => toggleSession('copilot')); } catch { /* not supported */ }
  refreshTrayMenu();
}

function applyIslandMode() {
  if (!island) return;
  const quiet = r3.gaming && configData.r3cketQuiet && !clickerState.running;
  if (session.active || (configData.islandMode === 'always' && !quiet) || clickerState.running) island.showInactive();
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
    gaming: r3.gaming, game: r3.exe, clicker: clickerState, clickHotkey: configData.clickHotkey,
  });
  send(glow, 'vibrance:state', { active: session.active && !session.gaming, phase: session.phase, intensity: configData.intensity });
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
  Object.assign(session, { active: true, transcript: '', reply: '', sources: [], error: '', gaming: r3.gaming });
  island.showInactive();
  if (!session.gaming) glow.showInactive(); // R3cket: no full-screen effects over a game
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
  if (r.action && r.action.type === 'clicker') {
    if (r.action.interval) configData.clickInterval = clamp(r.action.interval, 20, 5000);
    const msg = await clicker(r.action.cmd);
    if (msg) r.text = msg;
  }
  session.reply = r.text;
  session.sources = r.sources || [];
  if (r.close) {
    setPhase('done');
    session.endTimer = setTimeout(endSession, 1400);
    return;
  }
  if (configData.speak) {
    setPhase('speaking');
    speakOut(r.text, token);
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

// ------------------------------------------------------------------ natural voice

let voiceProblem = '';
/** Speaks with Groq Orpheus (streamed sentence by sentence to the island); falls back to the Windows voice. */
async function speakOut(text, token) {
  if (configData.voiceEngine === 'groq' && getKey()) {
    let sent = 0;
    try {
      await brain.speak(text, (buf, last) => {
        if (token !== session.token) return false;
        sent++;
        send(island, 'island:audio', { token, b64: buf.toString('base64'), last });
        return true;
      });
      voiceProblem = '';
      if (sent) return;
    } catch (e) {
      voiceProblem = e.spoken || e.message || 'Voice error';
      console.error('voice', voiceProblem, e.detail || '');
      if (sent) { send(island, 'island:audio', { token, b64: null, last: true }); return; }
    }
  }
  if (token === session.token) send(island, 'island:speak', { text, voice: configData.voice, token });
}

// ------------------------------------------------------------------ R3cket game mode

const helper = new WinHelper(path.join(app.getPath('userData'), 'helper'));
const r3 = { gaming: false, exe: '' };
const clickerState = { running: false, clicks: 0, interval: 100 };
let clickPoll = null;

async function pollForeground() {
  if (process.platform !== 'win32' || !configData.r3cket) { if (r3.gaming) setGaming(false, ''); return; }
  const fg = await helper.foreground();
  if (!fg.exe || fg.exe === 'nebula' || fg.exe === 'electron' || fg.exe === 'powershell') return; // our own windows
  const games = (configData.games || []).map((g) => String(g).toLowerCase().replace(/\.exe$/, ''));
  const gaming = games.includes(fg.exe) || (fg.fullscreen && !NOT_GAMES.has(fg.exe));
  if (gaming !== r3.gaming || (gaming && fg.exe !== r3.exe)) setGaming(gaming, fg.exe);
}

function setGaming(on, exe) {
  r3.gaming = on; r3.exe = on ? exe : '';
  if (!on && clickerState.running) clicker('stop');
  applyIslandMode();
  pushIslandState();
}

/** start | stop | faster | slower → a sentence for Nebula to say. */
async function clicker(cmd) {
  if (process.platform !== 'win32') return 'Auto-click works on Windows only.';
  if (cmd === 'faster') configData.clickInterval = clamp(Math.round(configData.clickInterval / 1.5), 20, 5000);
  if (cmd === 'slower') configData.clickInterval = clamp(Math.round(configData.clickInterval * 1.5), 20, 5000);
  saveConfig();
  clickerState.interval = configData.clickInterval;
  if (cmd === 'stop') {
    await helper.stopClicking();
    clickerState.running = false; clearInterval(clickPoll);
    applyIslandMode(); pushIslandState();
    return `Stopped after ${clickerState.clicks} clicks.`;
  }
  if (cmd === 'start' || clickerState.running) {
    const ok = await helper.startClicking(configData.clickInterval);
    if (ok !== 'ok') return "I couldn't start the auto-clicker on this PC.";
    if (cmd === 'start') clickerState.clicks = 0;
    clickerState.running = true;
    clearInterval(clickPoll);
    clickPoll = setInterval(async () => {
      const c = await helper.clicks();
      clickerState.clicks = c.clicks; clickerState.running = c.running;
      if (!c.running) clearInterval(clickPoll);
      pushIslandState();
    }, 500);
    // A running clicker needs a fresh start for a new speed.
    if (cmd !== 'start') { await helper.stopClicking(); await helper.startClicking(configData.clickInterval); }
    applyIslandMode(); pushIslandState();
    const cps = 1000 / configData.clickInterval;
    return cmd === 'start'
      ? `Auto-clicking where your pointer is, ${cps >= 1 ? `${cps.toFixed(cps < 10 ? 1 : 0)} times a second` : `every ${(configData.clickInterval / 1000).toFixed(1)} seconds`}. Press ${configData.clickHotkey.replace(/\+/g, ' ')} to stop.`
      : `Now every ${configData.clickInterval} milliseconds.`;
  }
  return `Auto-click speed set to every ${configData.clickInterval} milliseconds.`;
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
    voiceProblem, gaming: r3.gaming, game: r3.exe, helperProblem: helper.failed,
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
  ipcMain.on('island:clicker', (_e, cmd) => clicker(cmd));
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
    if (before.hotkey !== configData.hotkey || before.clickHotkey !== configData.clickHotkey) registerHotkeys();
    if (['islandScale', 'islandX', 'islandY'].some((k) => before[k] !== configData[k])) {
      repositionWindows();
      // Show the island while its size is being adjusted.
      if (island) island.showInactive();
      clearTimeout(ipcMain.__hideIsland);
      ipcMain.__hideIsland = setTimeout(applyIslandMode, 2500);
    }
    if (before.r3cket !== configData.r3cket || before.r3cketQuiet !== configData.r3cketQuiet) { pollForeground(); applyIslandMode(); }
    if (before.clickInterval !== configData.clickInterval && clickerState.running) clicker('speed');
    if (before.startWithWindows !== configData.startWithWindows) applyLoginItem();
    if (before.islandMode !== configData.islandMode) applyIslandMode();
    refreshTrayMenu();
    broadcastSettings();
    return settingsPayload();
  });
  ipcMain.handle('settings:test', (_e, key) => brain.test(key));
  // Voice preview in Settings: natural voice clips, or tell the page to use the Windows voice.
  ipcMain.handle('settings:voice-test', async () => {
    const line = "Hey, I'm Nebula. This is how I'll sound when we talk.";
    if (configData.voiceEngine !== 'groq') return { clips: [], text: line };
    if (!getKey()) return { clips: [], text: line, problem: 'Add your Groq key first.' };
    const clips = [];
    try { await brain.speak(line, (buf) => { clips.push(buf.toString('base64')); }); return { clips, text: line }; }
    catch (e) { voiceProblem = e.spoken || e.message; return { clips: [], text: line, problem: voiceProblem }; }
  });
  ipcMain.handle('settings:foreground', async () => (process.platform === 'win32' ? helper.foreground() : { exe: '', fullscreen: false }));
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

  // R3cket: watch for games (and warm up the helper so the first auto-click is instant).
  if (process.platform === 'win32') {
    helper.start().then((ok) => { if (!ok) console.error('helper', helper.failed); });
    setInterval(() => { pollForeground().catch(() => {}); }, 2000);
  }
});

app.on('window-all-closed', (e) => e.preventDefault()); // keep running in the tray
app.on('will-quit', () => { globalShortcut.unregisterAll(); helper.stopClicking().finally(() => helper.stop()); });
