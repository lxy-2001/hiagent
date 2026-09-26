// @vitest-environment node
import { it, expect } from 'vitest';
import { createServer as createHttpServer } from 'node:http';
import { createServer } from 'vite';
import type { AddressInfo } from 'node:net';
import config from '../../vite.config';
it('closes the upstream SSE connection when the browser cancels its proxied reader', async () => {
  let closed = false;
  const backend = createHttpServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' }); response.write('data: ready\n\n');
    response.on('close', () => { closed = true; });
  });
  await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve));
  const options = config.server!.proxy!['/api'];
  if (typeof options === 'string') throw new Error('Expected configured proxy');
  const server = await createServer({ configFile: false, logLevel: 'silent', server: { host: '127.0.0.1', port: 0, proxy: { '/api': { ...options, target: `http://127.0.0.1:${(backend.address() as AddressInfo).port}` } } } });
  try {
    await server.listen();
    const response = await fetch(`http://127.0.0.1:${(server.httpServer!.address() as AddressInfo).port}/api/events`);
    const reader = response.body!.getReader(); await reader.read(); await reader.cancel();
    await expect.poll(() => closed, { timeout: 1000 }).toBe(true);
  } finally {
    backend.closeAllConnections(); backend.close(); if (server.httpServer && 'closeAllConnections' in server.httpServer) server.httpServer.closeAllConnections(); await server.close();
  }
});
