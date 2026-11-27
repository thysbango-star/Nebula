'use strict';
// A tiny stdio MCP server for tests: a notes app with list / add / delete.
const notes = [process.env.FIRST_NOTE || 'Buy milk'];
const rl = require('readline').createInterface({ input: process.stdin });
const send = (m) => process.stdout.write(`${JSON.stringify(m)}\n`);
console.error('fake-mcp started'); // servers log to stderr
rl.on('line', (line) => {
  const m = JSON.parse(line);
  if (m.method === 'initialize') return send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'notes', version: '1.0' }, instructions: 'Notes for the user.' } });
  if (m.method === 'tools/list') return send({ jsonrpc: '2.0', id: m.id, result: { tools: [
    { name: 'list_notes', description: 'List all notes', inputSchema: { type: 'object', properties: {} } },
    { name: 'add_note', description: 'Add a note', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } },
    { name: 'delete_note', description: 'Delete a note', inputSchema: { type: 'object', properties: { index: { type: 'number' } } }, annotations: { destructiveHint: true } },
  ] } });
  if (m.method === 'tools/call') {
    const { name, arguments: a } = m.params;
    if (name === 'list_notes') return send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: notes.map((n, i) => `${i}: ${n}`).join('\n') }] } });
    if (name === 'add_note') { notes.push(a.text); return send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: `Added "${a.text}"` }] } }); }
    if (name === 'delete_note') { const r = notes.splice(a.index, 1); return send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: r.length ? `Deleted "${r[0]}"` : 'No such note' }], isError: !r.length } }); }
  }
  if (m.id != null) send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'nope' } });
});
