'use strict';
// Tiny Windows helper (built-in PowerShell + C#, no native modules):
//  - which app is in front and whether it fills the screen (R3cket game detection)
//  - an auto-clicker that clicks where the mouse pointer is, on its own thread for steady timing.

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const CSHARP = String.raw`
using System; using System.Diagnostics; using System.Runtime.InteropServices; using System.Threading;
public static class NebulaWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)] public struct MONITORINFO { public int cbSize; public RECT rcMonitor; public RECT rcWork; public uint dwFlags; }
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public MOUSEINPUT mi; }
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern IntPtr GetShellWindow();
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
  [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr m, ref MONITORINFO mi);
  [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);

  public static string Foreground() {
    IntPtr h = GetForegroundWindow();
    if (h == IntPtr.Zero || h == GetShellWindow()) return "|0";
    uint pid; GetWindowThreadProcessId(h, out pid);
    string exe = ""; string path = "";
    try { var pr = Process.GetProcessById((int)pid); exe = pr.ProcessName; try { path = pr.MainModule.FileName; } catch {} } catch {}
    RECT r; GetWindowRect(h, out r);
    MONITORINFO mi = new MONITORINFO(); mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
    GetMonitorInfo(MonitorFromWindow(h, 2), ref mi);
    bool full = r.L <= mi.rcMonitor.L && r.T <= mi.rcMonitor.T && r.R >= mi.rcMonitor.R && r.B >= mi.rcMonitor.B;
    return exe + "|" + (full ? "1" : "0") + "|" + path.Replace("|", "");
  }

  /** R3cket: give the game more CPU time (High priority). Returns how many processes were boosted. */
  public static int Boost(string exe) {
    int n = 0;
    foreach (var p in Process.GetProcessesByName(exe)) { try { p.PriorityClass = ProcessPriorityClass.High; n++; } catch {} }
    return n;
  }

  static volatile bool running; static volatile int interval = 100; public static long Clicks;
  static Thread worker;
  public static void Start(int ms) {
    interval = Math.Max(20, Math.Min(5000, ms));
    if (running) return;
    running = true; Clicks = 0;
    worker = new Thread(() => {
      INPUT[] down = new INPUT[2];
      down[0].type = 0; down[0].mi.dwFlags = 0x0002; // left down
      down[1].type = 0; down[1].mi.dwFlags = 0x0004; // left up
      int size = Marshal.SizeOf(typeof(INPUT));
      while (running) {
        if (SendInput(2, down, size) == 2) Interlocked.Increment(ref Clicks);
        Thread.Sleep(interval);
      }
    });
    worker.IsBackground = true; worker.Start();
  }
  public static void Stop() { running = false; }
  public static bool Running() { return running; }
}
`;

const SCRIPT = `
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
${CSHARP}
'@
# What's playing, from Windows' own media controls (the same source as the volume flyout).
$mgr = $null; $lastKey = ''
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $asTaskG = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation\`1' })[0]
  function Await($op, $type) { $t = $asTaskG.MakeGenericMethod($type).Invoke($null, @($op)); $null = $t.Wait(3000); $t.Result }
  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
  $null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
} catch { $mgr = $null }
function MediaNow() {
  if ($mgr -eq $null) { return '{"none":true}' }
  $s = $mgr.GetCurrentSession()
  if ($s -eq $null) { return '{"none":true}' }
  $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
  $pb = $s.GetPlaybackInfo(); $tl = $s.GetTimelineProperties()
  $o = [ordered]@{ title = [string]$p.Title; artist = [string]$p.Artist; app = [string]$s.SourceAppUserModelId
    playing = ($pb.PlaybackStatus -eq 'Playing'); pos = [int64]$tl.Position.TotalMilliseconds; dur = [int64]$tl.EndTime.TotalMilliseconds
    at = [int64]([DateTimeOffset]$tl.LastUpdatedTime).ToUnixTimeMilliseconds() }
  $key = "$($o.title)|$($o.artist)"
  if ($key -ne $script:lastKey -and $p.Thumbnail -ne $null) {
    try {
      $st = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
      $ns = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st); $ms = New-Object System.IO.MemoryStream; $ns.CopyTo($ms)
      $o.art = 'data:image/png;base64,' + [Convert]::ToBase64String($ms.ToArray()); $st.Dispose()
    } catch { }
    $script:lastKey = $key
  }
  return ($o | ConvertTo-Json -Compress)
}
function MediaCtl($what) {
  if ($mgr -eq $null) { return 'none' }
  $s = $mgr.GetCurrentSession(); if ($s -eq $null) { return 'none' }
  switch ($what) {
    'toggle' { $r = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
    'next'   { $r = Await ($s.TrySkipNextAsync()) ([bool]) }
    'prev'   { $r = Await ($s.TrySkipPreviousAsync()) ([bool]) }
  }
  return "$r"
}
[Console]::Out.WriteLine('ready'); [Console]::Out.Flush()
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  $p = $line.Split(' ')
  try {
    switch ($p[0]) {
      'fg'    { $out = [NebulaWin]::Foreground() }
      'start' { [NebulaWin]::Start([int]$p[1]); $out = 'ok' }
      'stop'  { [NebulaWin]::Stop(); $out = 'ok' }
      'count' { $out = "$([NebulaWin]::Clicks)|$([int][NebulaWin]::Running())" }
      'boost' { $out = "$([NebulaWin]::Boost($p[1]))" }
      'media' { $out = MediaNow }
      'mctl'  { $out = MediaCtl $p[1] }
      'mreset' { $script:lastKey = ''; $out = 'ok' }
      default { $out = 'unknown' }
    }
  } catch { $out = 'error ' + $_.Exception.Message.Replace([char]10, ' ') }
  [Console]::Out.WriteLine($out); [Console]::Out.Flush()
}
`;

class WinHelper {
  constructor(dir) {
    this.dir = dir;
    this.proc = null;
    this.queue = [];
    this.buf = '';
    this.ready = null;
    this.failed = '';
  }

  start() {
    if (process.platform !== 'win32') { this.failed = 'Windows only'; return Promise.resolve(false); }
    if (this.ready) return this.ready;
    this.ready = new Promise((resolve) => {
      try {
        fs.mkdirSync(this.dir, { recursive: true });
        const file = path.join(this.dir, 'nebula-helper.ps1');
        fs.writeFileSync(file, SCRIPT, 'utf8');
        this.proc = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file],
          { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      } catch (e) { this.failed = e.message; resolve(false); return; }
      let started = false;
      const timer = setTimeout(() => { if (!started) { this.failed = 'helper did not start'; resolve(false); } }, 20000);
      this.proc.stdout.setEncoding('utf8');
      this.proc.stdout.on('data', (d) => {
        this.buf += d;
        let i;
        while ((i = this.buf.indexOf('\n')) >= 0) {
          const line = this.buf.slice(0, i).trim(); this.buf = this.buf.slice(i + 1);
          if (!started) { if (line === 'ready') { started = true; clearTimeout(timer); resolve(true); } continue; }
          const q = this.queue.shift(); if (q) q(line);
        }
      });
      this.proc.stderr.on('data', (d) => { this.failed = String(d).slice(0, 300); });
      this.proc.on('exit', () => {
        this.proc = null; this.ready = null;
        this.queue.splice(0).forEach((q) => q(''));
        if (!started) resolve(false);
      });
    });
    return this.ready;
  }

  async cmd(line) {
    if (!(await this.start()) || !this.proc) return '';
    return new Promise((resolve) => {
      // A slow answer must still use up its own slot, or every later reply would land on the wrong question.
      let done = false;
      const t = setTimeout(() => { done = true; resolve(''); }, /^media/.test(line) ? 9000 : 4000);
      this.queue.push((r) => { clearTimeout(t); if (!done) resolve(r); });
      this.proc.stdin.write(line + '\n');
    });
  }

  /** {exe, fullscreen} of the app in front. */
  async foreground() {
    const r = await this.cmd('fg');
    const [exe, full, file] = r.split('|');
    return { exe: (exe || '').toLowerCase(), fullscreen: full === '1', path: (file || '').toLowerCase() };
  }
  async boost(exe) { return +(await this.cmd(`boost ${String(exe).replace(/\s/g, '')}`)) || 0; }
  /** What's playing on Windows: {title, artist, playing, pos, dur, at, art?} — art (a data URL) only when the track changes. */
  async media() { const r = await this.cmd('media'); try { return JSON.parse(r); } catch { return null; } }
  mediaControl(what) { return this.cmd(`mctl ${what}`); }
  mediaReset() { return this.cmd('mreset'); }
  startClicking(ms) { return this.cmd(`start ${Math.round(ms)}`); }
  stopClicking() { return this.cmd('stop'); }
  async clicks() { const [n, on] = (await this.cmd('count')).split('|'); return { clicks: +n || 0, running: on === '1' }; }

  stop() { try { this.proc && this.proc.kill(); } catch { /* */ } }
}

// Apps that fill the screen but aren't games.
const NOT_GAMES = new Set(['explorer', 'electron', 'nebula', 'chrome', 'msedge', 'firefox', 'brave', 'opera', 'vlc', 'mpc-hc64',
  'powerpnt', 'winword', 'excel', 'teams', 'ms-teams', 'zoom', 'applicationframehost', 'searchhost', 'startmenuexperiencehost',
  'lockapp', 'shellexperiencehost', 'textinputhost', 'code', 'devenv', 'obs64', 'discord', 'spotify', 'netflix', 'acrobat']);

// Where games live: anything launched from these folders is a game, even in a window.
const GAME_DIRS = ['\\steamapps\\', '\\epic games\\', '\\riot games\\', '\\xboxgames\\', '\\windowsapps\\microsoft.minecraft',
  '\\roblox\\', '\\ubisoft game launcher\\', '\\ea games\\', '\\origin games\\', '\\gog galaxy\\games\\', '\\battle.net\\',
  '\\blizzard\\', '\\rockstar games\\', '\\games\\', '\\minecraft', '\\genshin impact', '\\hoyoplay\\', '\\wargaming'];
const isGamePath = (p) => !!p && GAME_DIRS.some((d) => p.includes(d));

module.exports = { WinHelper, NOT_GAMES, SCRIPT, isGamePath };
