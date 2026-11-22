'use strict';
// Nebula for Windows — main process.
// Owns the windows (island, glow, settings, hidden mic), the hotkey, the tray,
// the "Hey Nebula" wake word, and the listen → think → speak flow.

const {
  app, BrowserWindow, Tray, Menu, ipcMain, globalShortcut, screen, nativeImage, safeStorage, Notification, shell, nativeTheme,
  utilityProcess, MessageChannelMain, powerMonitor, desktopCapturer,
} = require('electron');
const fs = require('fs');
const path = require('path');
const { Brain, cleanKey } = require('./brain');
const { wakeThreshold } = require('./voice');
const { WinHelper, NOT_GAMES, isGamePath } = require('./win32');
const os = require('os');
const net = require('net');
const { execFile } = require('child_process');
const { Store } = require('./store');
const { Agent } = require('./agent');

// Never interrupt the user with crash dialogs: log quietly and keep running.
const LOG = () => path.join(app.getPath('userData'), 'nebula.log');
function log(...a) {
  try {
    const line = `[${new Date().toISOString()}] ${a.map((x) => (x instanceof Error ? x.stack : typeof x === 'string' ? x : JSON.stringify(x))).join(' ')}\n`;
    const f = LOG(); if (fs.existsSync(f) && fs.statSync(f).size > 512 * 1024) fs.renameSync(f, f + '.old');
    fs.appendFileSync(f, line);
  } catch { /* */ }
}
process.on('uncaughtException', (e) => log('uncaught', e));
process.on('unhandledRejection', (e) => log('unhandled', e));

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
  followUps: true,       // keep listening briefly after Nebula answers
  performance: 'auto',   // 'auto' (lite on battery) | 'full' | 'lite'
  voice: '',
  startWithWindows: true,
  islandMode: 'always', // 'always' | 'active'
  model: '',
  noSearchModel: '',
  groqKeyEnc: '',
  voiceEngine: 'groq',   // 'groq' (natural Orpheus voice) | 'system' (Windows voice)
  groqVoice: 'troy',
  voiceModel: 'canopylabs/orpheus-v1-english',
  islandScale: 1,        // 0.7 – 1.5
  islandX: 0,            // px from centre
  islandY: 0,            // px down from the top
  r3cket: true,          // game mode
  r3cketQuiet: true,     // hide the resting island while gaming
  clickInterval: 100,    // ms between auto-clicks
  clickHotkey: 'Ctrl+Alt+K',
  games: [],             // extra .exe names that count as games
  r3cketHud: true,       // game time, ping, CPU and RAM in the island while you play
  r3cketPower: true,     // High performance power plan + game priority while playing
  crosshairHotkey: 'Ctrl+Alt+X',
  userName: '', nameSayAs: '', nameHeardAs: [],
  screenShare: false,
  groqExtraEnc: [],      // backup Groq keys (encrypted), used when the main one runs out for the day
  activeKey: 0,
  theme: 'system',       // 'system' | 'light' | 'dark'
  currency: '',
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
function decrypt(enc) {
  if (!enc) return '';
  try {
    return safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(Buffer.from(enc, 'base64'))
      : Buffer.from(enc, 'base64').toString('utf8');
  } catch { return ''; }
}
function encrypt(key) {
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(key) : Buffer.from(key, 'utf8');
  return key ? buf.toString('base64') : '';
}
/** Main key + backups, in order. */
function allKeys() {
  return [decrypt(configData.groqKeyEnc), ...(configData.groqExtraEnc || []).map(decrypt)].filter((k) => k.length >= 20)
    .filter((k, i, a) => a.indexOf(k) === i);
}
function getKey() { const all = allKeys(); return all.length ? all[(configData.activeKey || 0) % all.length] : ''; }
/** Move to the next backup key (when one hits its daily limit). */
function rotateKey() {
  const n = allKeys().length;
  if (n < 2) return false;
  configData.activeKey = ((configData.activeKey || 0) + 1) % n; saveConfig();
  log('switched to Groq key', configData.activeKey + 1);
  return true;
}
function setExtraKeys(list) {
  configData.groqExtraEnc = list.map(cleanKey).filter((k) => k.length >= 20).map(encrypt);
  configData.activeKey = 0; saveConfig();
}
function setKey(raw) {
  const key = cleanKey(raw);
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(key) : Buffer.from(key, 'utf8');
  configData.groqKeyEnc = key ? buf.toString('base64') : '';
  configData.noSearchModel = '';
  configData.activeKey = 0;
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

/**
 * Lite mode (automatic on battery, or chosen in Settings): no full-screen glow window at all —
 * the island's own rim does the job. Much lighter on laptops.
 */
function liteMode() {
  const p = configData.performance || 'auto';
  return p === 'lite' || (p === 'auto' && powerMonitor.isOnBatteryPower());
}
let glowKill = null;
/** The glow window only exists while it's needed (frees the graphics chip the rest of the time). */
function showGlow(msg) {
  if (liteMode()) return;
  clearTimeout(glowKill);
  if (!glow || glow.isDestroyed()) {
    createGlow();
    glow.webContents.once('did-finish-load', () => { pushIslandState(); if (msg) send(glow, msg); if (glow && !glow.isDestroyed()) glow.showInactive(); });
    return;
  }
  if (msg) send(glow, msg);
  glow.showInactive();
}
function dropGlow(afterMs) {
  clearTimeout(glowKill);
  glowKill = setTimeout(() => {
    if (session.active) return;
    if (glow && !glow.isDestroyed()) glow.destroy();
    glow = null;
  }, afterMs);
}

function createEar() {
  ear = new BrowserWindow({ show: false, width: 200, height: 200, webPreferences: baseWebPrefs });
  ear.loadFile(asset('ear.html'));
}

/** The Nebula app window (Chat · Tasks · Files · Settings), in the phone app's design. */
function openApp(view) {
  if (settings && !settings.isDestroyed()) {
    if (settings.isMinimized()) settings.restore();
    settings.show(); settings.focus();
    if (view) send(settings, 'app:open', view);
    return;
  }
  const dark = configData.theme === 'dark' || (configData.theme !== 'light' && nativeTheme.shouldUseDarkColors);
  settings = new BrowserWindow({
    width: 1180, height: 780, minWidth: 860, minHeight: 580, title: 'Nebula',
    backgroundColor: dark ? '#121110' : '#E7E5E2', icon: iconPath(), show: false,
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#00000000', symbolColor: dark ? '#F3EFEA' : '#262422', height: 46 },
    webPreferences: { ...baseWebPrefs, backgroundThrottling: true }, // the app window may sleep when hidden
  });
  settings.loadFile(asset('app.html'), view ? { query: { view } } : undefined);
  settings.once('ready-to-show', () => settings.show());
  settings.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
}
const openSettings = () => openApp('settings');

function repositionWindows() {
  const b = primaryBounds();
  if (island) { island.setBounds(islandBounds()); island.webContents.setZoomFactor(clamp(configData.islandScale || 1, 0.7, 1.5)); }
  if (glow && !glow.isDestroyed()) glow.setBounds({ x: b.x, y: b.y, width: b.width, height: b.height });
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
    { label: 'Open Nebula', click: () => openApp() },
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
  try { if (configData.crosshairHotkey) globalShortcut.register(configData.crosshairHotkey, () => showCrosshair(!(cross && !cross.isDestroyed()))); } catch { /* invalid */ }
  // R3cket auto-clicker on/off.
  try { if (configData.clickHotkey) globalShortcut.register(configData.clickHotkey, () => clicker(clickerState.running ? 'stop' : 'start')); } catch { /* invalid */ }
  // The Copilot key on newer keyboards sends Win+Shift+F23.
  try { globalShortcut.register('Super+Shift+F23', () => toggleSession('copilot')); } catch { /* not supported */ }
  refreshTrayMenu();
}

function applyIslandMode() {
  if (!island) return;
  const quiet = r3.gaming && configData.r3cketQuiet && !clickerState.running && !configData.r3cketHud;
  if (session.active || (configData.islandMode === 'always' && !quiet) || clickerState.running) island.showInactive();
  else island.hide();
}

function applyLoginItem() {
  try { app.setLoginItemSettings({ openAtLogin: !!configData.startWithWindows, args: ['--hidden'] }); } catch { /* */ }
}

// ------------------------------------------------------------------ wake word + mic

// Listening runs in a separate background process (ears.js) so the wake word never stalls the PC.
let ears = null;
let wake = false;       // wake word loaded and listening
let wakeError = '';
function modelDir() {
  return app.isPackaged ? path.join(process.resourcesPath, 'kws') : path.join(__dirname, '..', 'models', 'kws');
}
function startEars() {
  if (ears) return;
  ears = utilityProcess.fork(path.join(__dirname, 'ears.js'), [], { serviceName: 'Nebula listening' });
  ears.on('message', onEars);
  ears.on('exit', (code) => {
    ears = null; wake = false;
    log('ears stopped', code);
    if (!quitting) setTimeout(() => { startEars(); initWake(); connectEarPort(); }, 2000); // restart quietly
  });
  connectEarPort();
}
/** A direct pipe from the mic window to the listening process (audio never passes through here). */
function connectEarPort() {
  if (!ears || !ear || ear.isDestroyed()) return;
  const { port1, port2 } = new MessageChannelMain();
  ears.postMessage({ type: 'port' }, [port2]);
  ear.webContents.postMessage('ear:port', null, [port1]);
}
function onEars(m) {
  if (m.type === 'ready') { wake = !!m.wake; wakeError = ''; broadcastSettings(); pushIslandState(); }
  else if (m.type === 'error') { wakeError = m.message; log(m.message); if (/Wake word/.test(m.message)) { wake = false; broadcastSettings(); } }
  else if (m.type === 'wake') { if (!session.active) startSession('wake'); }
  else if (m.type === 'level') { if (session.active) { send(island, 'island:level', m.level); if (glow && !glow.isDestroyed() && glow.isVisible()) send(glow, 'vibrance:level', m.level); } }
  else if (m.type === 'utterance') {
    if (m.token !== session.token || session.listening !== m.token) return;
    session.listening = 0;
    onUtterance(m.wav ? Buffer.from(m.wav) : null, m.token);
  }
}
function initWake() {
  if (!ears) startEars();
  ears.postMessage({ type: 'config', modelDir: modelDir(), threshold: wakeThreshold(configData.sensitivity), wake: !!configData.wakeEnabled });
}

let earRunning = false;
let quitting = false;
function updateEar() {
  const shouldRun = configData.wakeEnabled || session.active;
  if (shouldRun !== earRunning) {
    earRunning = shouldRun;
    send(ear, shouldRun ? 'ear:start' : 'ear:stop');
  }
}

/** Fallback path if the direct mic pipe isn't available. */
function onAudio(samples) {
  if (ears) ears.postMessage({ type: 'audio', samples });
}

// ------------------------------------------------------------------ the conversation

const brain = new Brain(config, getKey, rotateKey);
let store = null; let agent = null;
let voiceConv = null; // the chat that voice requests go into (kept for 10 minutes)

/** Screen share: a ~1280px JPEG of the main screen (base64), straight from Windows — no picker needed. */
async function captureScreen() {
  const d = screen.getPrimaryDisplay();
  const k = Math.min(1, 1280 / d.size.width);
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: Math.round(d.size.width * k), height: Math.round(d.size.height * k) } });
  const src = sources.find((s) => String(s.display_id) === String(d.id)) || sources[0];
  if (!src || src.thumbnail.isEmpty()) return null;
  return src.thumbnail.toJPEG(72).toString('base64');
}

/** PDF from HTML using Chromium's own print engine. */
async function printPdf(html) {
  const w = new BrowserWindow({ show: false, webPreferences: { offscreen: true, javascript: false } });
  try {
    await w.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
    return await w.webContents.printToPDF({ pageSize: 'A4', printBackground: true, margins: { marginType: 'none' }, preferCSSPageSize: true });
  } finally { w.destroy(); }
}

function initAgent() {
  store = new Store(app.getPath('userData'));
  store.on('change', (data) => send(settings, 'app:data', data));
  agent = new Agent({
    brain, store, config, printPdf,
    launch: async (name) => { const r = brain.open(name.toLowerCase()); return { ok: !/couldn't/i.test(r.text) }; },
    foreground: process.platform === 'win32' ? () => helper.foreground() : null,
    timer: (s) => setTimer(s),
    clicker: (cmd) => clicker(cmd),
    onActivity: (a) => send(settings, 'app:activity', a),
    screenshot: captureScreen,
    sound: (kind) => send(settings, 'app:sound', kind),
  });
}
const session = {
  active: false, phase: 'idle', transcript: '', reply: '', sources: [], error: '',
  listening: 0, lastEnd: 0, endTimer: null, token: 0,
};
const timer = { endsAt: 0, label: '', handle: null };

function pushIslandState() {
  send(island, 'island:state', {
    active: session.active, phase: session.phase, transcript: session.transcript, reply: session.reply,
    sources: session.sources, timerEndsAt: timer.endsAt, intensity: configData.intensity,
    hasKey: !!getKey(), hotkey: registeredHotkey || configData.hotkey, wakeEnabled: configData.wakeEnabled && !!wake,
    gaming: r3.gaming, game: r3.exe, clicker: clickerState, clickHotkey: configData.clickHotkey,
    hud: r3.gaming && configData.r3cketHud ? { ...r3.hud, since: r3.since, boosts: r3.boosts, crosshair: !!r3.crosshair } : null,
    followup: !!session.followup, screenShare: !!configData.screenShare,
  });
  send(glow, 'vibrance:state', { active: session.active && !session.gaming, phase: session.phase, intensity: configData.intensity });
}

function setPhase(phase, extra = {}) {
  Object.assign(session, { phase }, extra);
  pushIslandState();
}

function toggleSession(source) {
  if (session.active) {
    if (session.phase === 'listening' && session.listening) { if (ears) ears.postMessage({ type: 'finish' }); }
    else endSession();
  } else {
    startSession(source);
  }
}

function startSession(source, typed) {
  clearTimeout(session.endTimer);
  if (Date.now() - session.lastEnd > 90_000) brain.resetConversation(); // follow-ups within 90 s keep context
  session.token += 1;
  const follow = String(source).startsWith('followup');
  // A follow-up keeps Nebula's last answer on screen while it listens.
  Object.assign(session, { active: true, transcript: '', error: '', gaming: r3.gaming }, follow ? {} : { reply: '', sources: [] });
  if (!follow && source !== 'typed' && source !== 'preview') send(island, 'island:sound', 'wake');
  island.showInactive();
  if (!session.gaming) showGlow(); // R3cket: no full-screen effects over a game
  send(island, 'island:speak-stop');
  if (typed) {
    session.voice = false;
    session.listening = 0;
    session.transcript = typed;
    think(typed, session.token);
    return;
  }
  setPhase('listening');
  session.listening = session.token;
  if (!ears) startEars();
  // A follow-up only waits a few seconds for you to start talking; silence closes Nebula.
  if (!String(source).startsWith('followup')) session.voice = source !== 'typed';
  const wait = source === 'followup-asked' ? { waitSeconds: 8 } : source === 'followup' ? { waitSeconds: 5 } : source === 'typed' ? {} : { ignoreMs: 1200 };
  ears.postMessage({ type: 'listen', token: session.token, opts: wait });
  ears.postMessage({ type: 'levels', on: true });
  updateEar();
}

async function onUtterance(wav, token) {
  if (!wav) {
    // Silence after a follow-up just means you're done.
    if (session.followup) { session.followup = false; endSession(); return; }
    fail("I didn't catch that."); return;
  }
  session.followup = false;
  setPhase('thinking');
  try {
    const text = await brain.transcribe(wav);
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
  send(island, 'island:sound', 'thinking');
  text = fixHeard(text);
  // Voice requests run through the same task engine as the app, so they show up in Chat and Tasks.
  const fresh = voiceConv && store.conversation(voiceConv) && Date.now() - store.conversation(voiceConv).updatedAt < 10 * 60_000;
  if (!fresh) voiceConv = store.newConversation().id;
  const r = await agent.submit(voiceConv, text);
  if (token !== session.token) return;
  const conv = store.conversation(voiceConv);
  const lastMsg = conv?.messages.filter((m) => m.role === 'assistant').pop();
  session.reply = r.text;
  session.sources = lastMsg?.sources || [];
  session.expectsAnswer = !!r.needsInput;
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
  // Conversation mode: after Nebula answers, keep listening a few seconds for a follow-up — no button needed.
  // If Nebula asked you something, it waits a little longer. Silence closes the island.
  if (session.voice && configData.followUps !== false) {
    const asked = session.expectsAnswer; session.expectsAnswer = false;
    session.followup = true;
    startSession(asked ? 'followup-asked' : 'followup');
    return;
  }
  if (session.expectsAnswer) { session.expectsAnswer = false; session.followup = true; startSession('followup-asked'); return; }
  setPhase('done');
  const hold = Math.min(25_000, 7_000 + session.reply.length * 40);
  session.endTimer = setTimeout(endSession, hold);
}

function fail(message) {
  send(island, 'island:sound', 'failure');
  setPhase('error', { reply: message });
  session.endTimer = setTimeout(endSession, 4_500);
}

function endSession() {
  clearTimeout(session.endTimer);
  if (session.listening && ears) ears.postMessage({ type: 'cancel' });
  session.listening = 0; session.followup = false;
  if (ears) ears.postMessage({ type: 'levels', on: false });
  session.token += 1;
  session.active = false;
  session.lastEnd = Date.now();
  setPhase('idle');
  send(island, 'island:speak-stop');
  setTimeout(() => {
    if (!session.active) {
      dropGlow(700); // after the light has drawn back into the island
      applyIslandMode();
    }
  }, 900); // let the glow fade and the island settle first
  updateEar();
}

function setTimer(seconds) {
  clearTimeout(timer.handle);
  if (!seconds) { timer.endsAt = 0; pushIslandState(); return; }
  timer.endsAt = Date.now() + seconds * 1000;
  timer.handle = setTimeout(() => {
    timer.endsAt = 0;
    pushIslandState();
    send(island, 'island:timer-done');
    showGlow('vibrance:pulse');
    dropGlow(4000);
    if (Notification.isSupported()) new Notification({ title: 'Nebula', body: "Time's up!", icon: iconPath() }).show();
  }, seconds * 1000);
  pushIslandState();
}

// ------------------------------------------------------------------ name coach

const COMMON_WORDS = new Set('the a an and or but if then this that these those there their they them is are was were be been am i you he she it we me my your his her our its to of in on at for with from by as so not no yes do does did have has had can could will would should may might just like what when where who why how all any some one two three hi hey hello okay ok please thanks thank good great nice well now here see say said tell ask make made get got go going come want need know think look time day today night play open call text send'.split(' '));
/** Mis-hearings of your name (learned by the name coach) become your name again. */
function fixHeard(text) {
  const name = configData.userName;
  if (!name) return text;
  let out = text;
  for (const h of configData.nameHeardAs || []) {
    if (!h || h.length < 3 || h.toLowerCase() === name.toLowerCase() || COMMON_WORDS.has(h.toLowerCase())) continue;
    // Only a capitalised word mid-sentence (how Whisper writes a name), never the start of a sentence.
    const cap = h.charAt(0).toUpperCase() + h.slice(1);
    out = out.replace(new RegExp(`(?<![.!?]\\s|^)\\b${cap.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'g'), name);
  }
  return out;
}
/** Your name spelled the way it should be *said*, before the voice reads it. */
function forSpeech(text) {
  const name = configData.userName; const say = configData.nameSayAs;
  if (!name || !say || say.toLowerCase() === name.toLowerCase()) return text;
  return text.replace(new RegExp(`\\b${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'gi'), say);
}

// ------------------------------------------------------------------ natural voice

let voiceProblem = '';
/** Speaks with Groq Orpheus (streamed sentence by sentence to the island); falls back to the Windows voice. */
async function speakOut(text, token) {
  text = forSpeech(text);
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
      log('voice', voiceProblem, e.detail || '');
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
  const gaming = games.includes(fg.exe) || isGamePath(fg.path) || (fg.fullscreen && !NOT_GAMES.has(fg.exe));
  if (gaming !== r3.gaming || (gaming && fg.exe !== r3.exe)) setGaming(gaming, fg.exe);
}

function setGaming(on, exe) {
  const was = r3.gaming;
  r3.gaming = on; r3.exe = on ? exe : '';
  if (!on && clickerState.running) clicker('stop');
  if (on) enterGame(exe); else if (was) exitGame();
  applyIslandMode();
  pushIslandState();
}

// ---- R3cket boosts (Windows)
const run = (cmd, args) => new Promise((res) => execFile(cmd, args, { windowsHide: true, timeout: 8000 }, (e, out) => res(e ? '' : String(out))));
async function enterGame(exe) {
  r3.since = Date.now(); r3.hud = { cpu: null, ram: null, ping: null };
  r3.boosts = [];
  if (configData.r3cketPower) {
    const n = await helper.boost(exe).catch(() => 0);
    if (n) r3.boosts.push('priority');
    const cur = /([0-9a-f]{8}-[0-9a-f-]{27})/i.exec(await run('powercfg', ['/getactivescheme']));
    if (cur && !/8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c/i.test(cur[1])) {
      r3.prevScheme = cur[1];
      await run('powercfg', ['/setactive', 'SCHEME_MIN']); // Windows' "High performance" plan
      if (/8c5e7fda/i.test(await run('powercfg', ['/getactivescheme']))) r3.boosts.push('power');
    }
  }
  if (configData.r3cketHud) startHud();
  log('R3cket on', exe, r3.boosts.join(','));
  pushIslandState();
}
async function exitGame() {
  stopHud(); showCrosshair(false);
  if (r3.prevScheme) { await run('powercfg', ['/setactive', r3.prevScheme]); r3.prevScheme = null; }
  r3.boosts = [];
}
let hudTimer = null; let lastCpu = null;
function cpuSample() { return os.cpus().reduce((a, c) => { const t = Object.values(c.times).reduce((x, y) => x + y, 0); return { idle: a.idle + c.times.idle, total: a.total + t }; }, { idle: 0, total: 0 }); }
function pingOnce() {
  return new Promise((res) => {
    const t = Date.now(); const s = net.connect({ host: '1.1.1.1', port: 443 });
    const done = (v) => { s.destroy(); res(v); };
    s.setTimeout(1500, () => done(null)); s.once('connect', () => done(Date.now() - t)); s.once('error', () => done(null));
  });
}
function startHud() {
  stopHud(); lastCpu = cpuSample();
  const tick = async () => {
    const c = cpuSample(); const di = c.idle - lastCpu.idle; const dt = c.total - lastCpu.total; lastCpu = c;
    r3.hud = { cpu: dt > 0 ? Math.round(100 * (1 - di / dt)) : null, ram: Math.round(100 * (1 - os.freemem() / os.totalmem())), ping: await pingOnce() };
    pushIslandState();
  };
  tick(); hudTimer = setInterval(tick, 3000);
}
function stopHud() { clearInterval(hudTimer); hudTimer = null; }

// Crosshair: a tiny click-through window in the middle of the screen.
let cross = null;
function showCrosshair(on) {
  if (!on) { if (cross && !cross.isDestroyed()) cross.destroy(); cross = null; r3.crosshair = false; pushIslandState(); return; }
  if (cross && !cross.isDestroyed()) return;
  const b = primaryBounds(); const S = 44;
  cross = new BrowserWindow({ x: Math.round(b.x + b.width / 2 - S / 2), y: Math.round(b.y + b.height / 2 - S / 2), width: S, height: S,
    transparent: true, frame: false, resizable: false, movable: false, focusable: false, skipTaskbar: true, hasShadow: false,
    alwaysOnTop: true, show: false, backgroundColor: '#00000000', webPreferences: { javascript: false } });
  cross.setAlwaysOnTop(true, 'screen-saver'); cross.setIgnoreMouseEvents(true);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44"><g stroke-linecap="round"><g stroke="#000" stroke-opacity=".6" stroke-width="4"><path d="M22 6v10M22 28v10M6 22h10M28 22h10"/></g><g stroke="#B8FF3D" stroke-width="2"><path d="M22 6v10M22 28v10M6 22h10M28 22h10"/></g></g><circle cx="22" cy="22" r="1.8" fill="#B8FF3D"/></svg>`;
  cross.loadURL('data:text/html,' + encodeURIComponent(`<body style="margin:0;background:transparent">${svg}</body>`));
  cross.once('ready-to-show', () => cross && cross.showInactive());
  r3.crosshair = true; pushIslandState();
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
    groqExtraEnc: undefined, keyCount: allKeys().length, activeKeyNumber: allKeys().length ? ((configData.activeKey || 0) % allKeys().length) + 1 : 0,
    dark: configData.theme === 'dark' || (configData.theme !== 'light' && nativeTheme.shouldUseDarkColors), platform: process.platform,
    version: app.getVersion(),
  };
}

function wireIpc() {
  ipcMain.on('ear:audio', (_e, data) => onAudio(data instanceof Float32Array ? data : new Float32Array(data)));
  ipcMain.on('ear:need-port', () => connectEarPort());
  ipcMain.on('ear:error', (_e, msg) => { log('mic', msg); if (session.active) fail('I can’t reach the microphone. Check Windows microphone privacy settings.'); });

  ipcMain.on('island:interactive', (_e, on) => island && island.setIgnoreMouseEvents(!on, { forward: true }));
  ipcMain.on('island:talk', () => toggleSession('island'));
  ipcMain.on('island:ask', (_e, text) => { if (String(text || '').trim()) startSession('typed', String(text).trim()); });
  ipcMain.on('island:spoken', (_e, token) => finishTurn(token));
  ipcMain.on('island:close', () => endSession());
  ipcMain.on('island:clicker', (_e, cmd) => clicker(cmd));
  ipcMain.on('island:crosshair', () => showCrosshair(!(cross && !cross.isDestroyed())));
  ipcMain.on('island:cancel-timer', () => setTimer(0));
  ipcMain.on('island:focus', () => { if (island) island.focus(); });
  ipcMain.on('open:url', (_e, url) => { if (/^https?:\/\//.test(url)) shell.openExternal(url); });
  ipcMain.on('open:settings', openSettings);
  ipcMain.on('open:app', (_e, view) => openApp(view));

  // ---- the app window (Chat · Tasks · Files)
  ipcMain.handle('app:init', () => ({ data: store.snapshot(), activity: agent.activity, settings: settingsPayload() }));
  ipcMain.handle('app:send', async (_e, { conversationId, text }) => {
    const cid = conversationId && store.conversation(conversationId) ? conversationId : store.newConversation().id;
    agent.submit(cid, String(text || '').trim()).catch((e) => log('submit', e));
    return cid;
  });
  ipcMain.handle('app:new-chat', () => store.newConversation().id);
  ipcMain.on('app:answer', (_e, { taskId, choices }) => { agent.answer(taskId, choices).catch((e) => log('answer', e)); });
  ipcMain.on('app:cancel', (_e, taskId) => agent.cancel(taskId));
  ipcMain.on('app:stop', (_e, cid) => agent.stop(cid));
  ipcMain.on('app:delete-task', (_e, id) => store.deleteTask(id));
  ipcMain.on('app:delete-chat', (_e, id) => store.deleteConversation(id));
  ipcMain.on('app:delete-file', (_e, id) => store.deleteFile(id));
  ipcMain.on('app:open-file', (_e, id) => { const f = store.file(id); if (f) shell.openPath(f.path).then((err) => err && log('open', err)); });
  ipcMain.on('app:show-file', (_e, id) => { const f = store.file(id); if (f) shell.showItemInFolder(f.path); });
  ipcMain.on('app:files-folder', () => shell.openPath(store.filesDir));
  ipcMain.on('app:mic', (_e, cid) => { if (cid && store.conversation(cid)) voiceConv = cid; startSession('app'); });

  ipcMain.handle('settings:get', () => settingsPayload());
  ipcMain.handle('settings:set', (_e, patch) => {
    const before = { ...configData };
    for (const [k, v] of Object.entries(patch || {})) {
      if (k === 'groqKey') setKey(v);
      else if (k === 'groqExtraKeys') setExtraKeys(Array.isArray(v) ? v : String(v || '').split(/\s+/));
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
    if (before.r3cket !== configData.r3cket || before.r3cketQuiet !== configData.r3cketQuiet) { if (configData.r3cket) helper.start(); pollForeground(); applyIslandMode(); }
    if (before.theme !== configData.theme && settings && !settings.isDestroyed()) {
      const dark = configData.theme === 'dark' || (configData.theme !== 'light' && nativeTheme.shouldUseDarkColors);
      try { settings.setTitleBarOverlay({ color: '#00000000', symbolColor: dark ? '#F3EFEA' : '#262422', height: 46 }); } catch { /* */ }
    }
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
  // ---- name coach
  ipcMain.handle('settings:name-take', async (_e, wav) => {
    try { return { text: (await brain.transcribe(Buffer.from(wav), { prompt: '', mime: 'audio/webm', file: 'name.webm' })).replace(/^my name is\s+/i, '').replace(/[.!?,]+$/g, '').trim() }; }
    catch (e) { return { error: e.spoken || e.message }; }
  });
  ipcMain.handle('settings:name-suggest', async (_e, { name, heard }) => {
    try { const list = await brain.nameSpellings(name, heard); return list.length ? list : [name]; } catch { return [name]; }
  });
  ipcMain.handle('settings:say', async (_e, text) => {
    if (configData.voiceEngine !== 'groq' || !getKey()) return { clips: [], text };
    const clips = [];
    try { await brain.speak(text, (buf) => { clips.push(buf.toString('base64')); }); return { clips, text }; } catch (e) { return { clips: [], text, problem: e.spoken || e.message }; }
  });
  ipcMain.handle('settings:foreground', async () => (process.platform === 'win32' ? helper.foreground() : { exe: '', fullscreen: false }));
  ipcMain.on('settings:preview', () => {
    startSession('preview');
  });
}

// ------------------------------------------------------------------ app lifecycle

app.on('second-instance', () => openApp());

app.whenReady().then(() => {
  loadConfig();
  // Allow the mic for our own windows only.
  const { session: electronSession } = require('electron');
  electronSession.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(permission === 'media'));
  electronSession.defaultSession.setPermissionCheckHandler((wc, permission) => permission === 'media');

  initAgent();
  wireIpc();
  nativeTheme.on('updated', () => broadcastSettings());
  createIsland(); // the glow window is created only when Nebula wakes
  createEar();
  createTray();
  registerHotkeys();
  applyLoginItem();
  initWake();
  ear.webContents.on('did-finish-load', () => { earRunning = false; connectEarPort(); updateEar(); });

  screen.on('display-metrics-changed', repositionWindows);
  screen.on('display-added', repositionWindows);
  screen.on('display-removed', repositionWindows);

  if (!process.argv.includes('--hidden')) openApp(getKey() ? undefined : 'settings');

  // R3cket: watch for games (and warm up the helper so the first auto-click is instant).
  if (process.platform === 'win32') {
    // Light on the laptop: the helper only starts when R3cket is on, and checks the front app every 3 s.
    // Start the helper a little after launch so Nebula opens fast.
    setTimeout(() => { if (configData.r3cket) helper.start().then((ok) => { if (!ok) log('helper', helper.failed); }); }, 15000);
    setInterval(() => { if (configData.r3cket) pollForeground().catch(() => {}); }, 3000);
  }
});

app.on('window-all-closed', (e) => e.preventDefault()); // keep running in the tray
app.on('before-quit', () => { quitting = true; });
app.on('will-quit', () => { quitting = true; globalShortcut.unregisterAll(); try { if (ears) ears.kill(); } catch { /* */ } helper.stopClicking().finally(() => helper.stop()); });
