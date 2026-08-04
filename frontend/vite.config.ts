import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The frontend is a static dev page that ONLY talks to the Spring Boot backend.
// During dev we proxy /api to the backend on :8080; in all cases the browser
// only ever calls the three backend APIs (never SimNow, never credentials).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
        // SSE: disable proxy buffering so agent-demo event streams flush
        // immediately instead of being buffered into a single response.
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const ct = proxyRes.headers['content-type'] ?? '';
            if (ct.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache';
              proxyRes.headers['x-accel-buffering'] = 'no';
            }
          });
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
    // Module 2 restart tests drive the real 5s health poll, so allow generous
    // per-test time on slow CI machines.
    testTimeout: 20000,
  },
});
