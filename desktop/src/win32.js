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
    string exe = "";
    try { exe = Process.GetProcessById((int)pid).ProcessName; } catch {}
    RECT r; GetWindowRect(h, out r);
    MONITORINFO mi = new MONITORINFO(); mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
    GetMonitorInfo(MonitorFromWindow(h, 2), ref mi);
    bool full = r.L <= mi.rcMonitor.L && r.T <= mi.rcMonitor.T && r.R >= mi.rcMonitor.R && r.B >= mi.rcMonitor.B;
    return exe + "|" + (full ? "1" : "0");
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
      const t = setTimeout(() => resolve(''), 4000);
      this.queue.push((r) => { clearTimeout(t); resolve(r); });
      this.proc.stdin.write(line + '\n');
    });
  }

  /** {exe, fullscreen} of the app in front. */
  async foreground() {
    const r = await this.cmd('fg');
    const [exe, full] = r.split('|');
    return { exe: (exe || '').toLowerCase(), fullscreen: full === '1' };
  }
  startClicking(ms) { return this.cmd(`start ${Math.round(ms)}`); }
  stopClicking() { return this.cmd('stop'); }
  async clicks() { const [n, on] = (await this.cmd('count')).split('|'); return { clicks: +n || 0, running: on === '1' }; }

  stop() { try { this.proc && this.proc.kill(); } catch { /* */ } }
}

// Apps that fill the screen but aren't games.
const NOT_GAMES = new Set(['explorer', 'electron', 'nebula', 'chrome', 'msedge', 'firefox', 'brave', 'opera', 'vlc', 'mpc-hc64',
  'powerpnt', 'winword', 'excel', 'teams', 'ms-teams', 'zoom', 'applicationframehost', 'searchhost', 'startmenuexperiencehost',
  'lockapp', 'shellexperiencehost', 'textinputhost', 'code', 'devenv', 'obs64', 'discord', 'spotify', 'netflix', 'acrobat']);

module.exports = { WinHelper, NOT_GAMES, SCRIPT };
