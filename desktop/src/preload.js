'use strict';
// A small, safe bridge between Nebula's pages and the main process.
const { contextBridge, ipcRenderer } = require('electron');

const ALLOWED = /^(ear|island|vibrance|settings|open|app|sports|support|widget|mood|ws):/;

// The mic window gets a direct pipe to the listening process; hand it to the page.
ipcRenderer.on('ear:port', (e) => { window.postMessage({ type: 'nebula-ear-port' }, '*', e.ports); });

contextBridge.exposeInMainWorld('nebula', {
  send: (channel, data) => { if (ALLOWED.test(channel)) ipcRenderer.send(channel, data); },
  invoke: (channel, data) => (ALLOWED.test(channel) ? ipcRenderer.invoke(channel, data) : Promise.reject(new Error('blocked'))),
  on: (channel, fn) => {
    if (!ALLOWED.test(channel)) return () => {};
    const handler = (_e, data) => fn(data);
    ipcRenderer.on(channel, handler);
    return () => ipcRenderer.removeListener(channel, handler);
  },
});
