import { spawn, spawnSync } from 'node:child_process';
const built = spawnSync(process.execPath, ['scripts/build.mjs'], { stdio: 'inherit', windowsHide: true });
if (built.status !== 0) process.exit(built.status ?? 1);
const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--host', '127.0.0.1', '--port', '15179'], { stdio: 'inherit', windowsHide: true });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.kill());
server.on('exit', code => process.exit(code ?? 1));
