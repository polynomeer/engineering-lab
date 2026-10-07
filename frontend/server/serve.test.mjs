import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

test('serves the build and streams API bodies, statuses, headers and failures', async (t) => {
  let received;
  const backend = http
    .createServer((req, res) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        received = {
          url: req.url,
          body: Buffer.concat(chunks).toString(),
          type: req.headers['content-type'],
        };
        res.writeHead(202, {
          'Content-Type': 'application/x-ndjson',
          'Content-Disposition': 'attachment; filename="evidence.ndjson"',
          Location: '/runs/example',
        });
        res.write('{"type":"start"}\n');
        setTimeout(() => res.end('{"type":"end"}\n'), 20);
      });
    })
    .listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const child = spawn(process.execPath, ['server/serve.mjs'], {
    env: {
      ...process.env,
      PORT: '0',
      LAB_API_TARGET: `http://127.0.0.1:${backend.address().port}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    child.kill();
    backend.close();
    backend.closeAllConnections();
  });
  const address = await new Promise((resolve, reject) => {
    child.stdout.on('data', (data) => {
      const match = String(data).match(/http:\/\/127.0.0.1:\d+/);
      if (match) resolve(match[0]);
    });
    child.on('exit', (code) => reject(new Error('server exited ' + code)));
  });
  assert.equal((await fetch(address + '/app/runs/deep-link')).status, 200);
  assert.equal((await fetch(address + '/outside')).status, 404);
  assert.equal((await fetch(address + '/app/assets/missing.js')).status, 404);
  const response = await fetch(address + '/api/runs?x=1', {
    method: 'POST',
    headers: { 'Content-Type': 'multipart/form-data; boundary=sample' },
    body: '--sample\r\ndata\r\n--sample--',
  });
  assert.equal(response.status, 202);
  assert.equal(response.headers.get('location'), '/api/runs/example');
  assert.match(response.headers.get('content-disposition'), /evidence.ndjson/);
  assert.equal(await response.text(), '{"type":"start"}\n{"type":"end"}\n');
  assert.equal(received.url, '/runs?x=1');
  assert.match(received.body, /data/);
  assert.match(received.type, /boundary=sample/);
  await fetch(address + '/api//untrusted.invalid/path');
  assert.equal(received.url, '//untrusted.invalid/path');
  await new Promise((resolve) => backend.close(resolve));
  assert.equal((await fetch(address + '/api/runs')).status, 502);
});
