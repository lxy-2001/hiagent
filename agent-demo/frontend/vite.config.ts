import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';
const backend = process.env.WORKBENCH_BACKEND_URL ?? 'http://127.0.0.1:8080';
const url = new URL(backend);
if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('WORKBENCH_BACKEND_URL must be a loopback HTTP origin');
export default defineConfig({
  plugins: [vue()], envDir: false, base: '/',
  build: { outDir: resolve(import.meta.dirname, '../target/workbench'), emptyOutDir: true,
    sourcemap: false, manifest: 'asset-manifest.json', assetsDir: 'assets' },
  preview: {host:'127.0.0.1', strictPort:true, proxy:{'/api':{target:url.origin,changeOrigin:false}}},
  server: {host:'127.0.0.1', strictPort:true, proxy:{'/api':{target:url.origin,changeOrigin:false}}},
});
