'use strict';
// CI check on Windows: the R3cket helper compiles, reports the foreground app, and the clicker starts and stops.
const os = require('os');
const path = require('path');
const { WinHelper } = require('../src/win32');

(async () => {
  const h = new WinHelper(path.join(os.tmpdir(), 'nebula-helper-test'));
  const ok = await h.start();
  if (!ok) { console.error('helper failed to start:', h.failed); process.exit(1); }
  const fg = await h.foreground();
  console.log('foreground:', JSON.stringify(fg));
  if (typeof fg.exe !== 'string' || typeof fg.fullscreen !== 'boolean') process.exit(1);
  if ((await h.startClicking(200)) !== 'ok') { console.error('start failed'); process.exit(1); }
  await new Promise((r) => setTimeout(r, 900));
  const c = await h.clicks();
  console.log('clicker:', JSON.stringify(c));
  if (!c.running) { console.error('clicker not running'); process.exit(1); }
  if ((await h.stopClicking()) !== 'ok') process.exit(1);
  await new Promise((r) => setTimeout(r, 400));
  if ((await h.clicks()).running) { console.error('clicker did not stop'); process.exit(1); }
  h.stop();
  console.log('R3cket helper OK');
  process.exit(0);
})();
