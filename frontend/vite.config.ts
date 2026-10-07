import { defineConfig } from 'vitest/config';
import type { Connect } from 'vite';
const guard: Connect.NextHandleFunction = (req, res, next) => {
  const path = (req.url ?? '/').split('?')[0];
  if (path === '/ui') {
    res.writeHead(302, {
      Location: new URL('/ui?legacy=true', process.env.LAB_API_TARGET ?? 'http://127.0.0.1:8080')
        .href,
    });
    res.end();
  } else if (path === '/') {
    res.writeHead(302, { Location: '/app/runs' });
    res.end();
  } else if (
    path === '/api' ||
    path.startsWith('/api/') ||
    path === '/app' ||
    path.startsWith('/app/')
  )
    next();
  else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
  }
};
const proxy = {
  '^/api(?:/|$)': {
    target: process.env.LAB_API_TARGET ?? 'http://127.0.0.1:8080',
    changeOrigin: false,
    rewrite: (path: string) => path.replace(/^\/api(?=\/|$)/, '') || '/',
    configure(server: { on: Function }) {
      server.on(
        'proxyRes',
        (response: { headers: Record<string, string | string[] | undefined> }) => {
          const location = response.headers.location;
          if (typeof location === 'string' && /^\/(?!\/)/.test(location))
            response.headers.location = '/api' + location;
        },
      );
    },
  },
};
export default defineConfig({
  base: '/app/',
  plugins: [
    {
      name: 'route-boundaries',
      configureServer(server) {
        server.middlewares.use(guard);
      },
      configurePreviewServer(server) {
        server.middlewares.use(guard);
      },
    },
  ],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, strictPort: true, proxy },
  test: { include: ['src/**/*.test.ts'] },
});
