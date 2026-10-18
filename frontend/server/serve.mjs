import http from 'node:http';
import https from 'node:https';
import { createReadStream } from 'node:fs';
import { stat, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, extname, sep } from 'node:path';
import { pipeline } from 'node:stream';
const root = fileURLToPath(new URL('../dist/', import.meta.url));
const target = new URL(process.env.LAB_API_TARGET ?? 'http://127.0.0.1:8080');
if (
  !['http:', 'https:'].includes(target.protocol) ||
  target.username ||
  target.password ||
  target.pathname !== '/' ||
  target.search ||
  target.hash
)
  throw new Error('LAB_API_TARGET must be an HTTP(S) origin');
const port = Number(process.env.PORT ?? 4173);
await access(resolve(root, 'index.html'));
const server = http.createServer(async (req, res) => {
  const raw = req.url ?? '/';
  if (raw.startsWith('/api/')) {
    const upstream = (target.protocol === 'https:' ? https : http).request(
      new URL(target.origin + raw.slice(4)),
      {
        method: req.method,
        headers: { ...req.headers, host: target.host },
      },
      (incoming) => {
        const headers = { ...incoming.headers };
        if (headers.location) {
          const location = new URL(headers.location, target);
          if (location.origin === target.origin)
            headers.location = '/api' + location.pathname + location.search + location.hash;
        }
        res.writeHead(incoming.statusCode ?? 502, headers);
        pipeline(incoming, res, () => {});
      },
    );
    upstream.setTimeout(120000, () => upstream.destroy(new Error('Upstream timeout')));
    upstream.on('error', () => {
      if (!res.headersSent)
        res
          .writeHead(502, { 'Content-Type': 'application/json' })
          .end('{"error":"API unavailable"}');
      else res.destroy();
    });
    res.on('close', () => upstream.destroy());
    pipeline(req, upstream, () => {});
    return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405).end();
    return;
  }
  const path = raw.split('?')[0];
  if (path === '/ui') {
    res.writeHead(302, { Location: target.origin + '/ui?legacy=true' }).end();
    return;
  }
  if (path === '/') {
    res.writeHead(302, { Location: '/app/catalog' }).end();
    return;
  }
  if (!(path === '/app' || path.startsWith('/app/'))) {
    res.writeHead(404).end();
    return;
  }
  try {
    let file;
    if (path.startsWith('/app/assets/')) {
      const relative = decodeURIComponent(path.slice('/app/'.length));
      file = resolve(root, relative);
      if (!file.startsWith(root + (root.endsWith(sep) ? '' : sep))) {
        res.writeHead(404).end();
        return;
      }
    } else file = resolve(root, 'index.html');
    const info = await stat(file);
    if (!info.isFile()) {
      res.writeHead(404).end();
      return;
    }
    const type =
      {
        '.html': 'text/html; charset=utf-8',
        '.js': 'text/javascript; charset=utf-8',
        '.css': 'text/css; charset=utf-8',
        '.svg': 'image/svg+xml',
        '.png': 'image/png',
      }[extname(file)] ?? 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': type,
      'Content-Length': info.size,
      'Cache-Control':
        extname(file) === '.html' ? 'no-store' : 'public, max-age=31536000, immutable',
      'X-Content-Type-Options': 'nosniff',
    });
    if (req.method === 'HEAD') res.end();
    else pipeline(createReadStream(file), res, () => {});
  } catch {
    res.writeHead(404).end();
  }
});
server.listen(port, '127.0.0.1', () =>
  console.log(`Dashboard: http://127.0.0.1:${server.address().port}/app/catalog`),
);
for (const signal of ['SIGTERM', 'SIGINT'])
  process.on(signal, () => {
    server.close();
    server.closeIdleConnections();
  });
