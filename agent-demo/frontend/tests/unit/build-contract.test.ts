import { describe,it,expect } from 'vitest';
import { resolve } from 'node:path';
import config from '../../vite.config';
describe('workbench build boundary',()=>{
 it('never loads dotenv or produces source maps',()=>{expect(config.envDir).toBe(false);expect(config.build?.sourcemap).toBe(false)});
 it('uses a dedicated target folder with a manifest',()=>{expect(config.build?.outDir).toBe(resolve(import.meta.dirname,'../../../target/workbench'));expect(config.build?.manifest).toBe('asset-manifest.json')});
 it('uses same-origin assets and loopback-only development',()=>{expect(config.base).toBe('/');expect(config.server?.host).toBe('127.0.0.1')});
});
