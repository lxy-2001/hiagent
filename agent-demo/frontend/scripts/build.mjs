import { build } from 'vite';
import { resolve, relative } from 'node:path';
import { readFile, readdir, mkdir, rm, rename, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
const frontend=resolve(import.meta.dirname,'..');
const parent=resolve(frontend,'../target');
const output=resolve(parent,'workbench'), stage=resolve(parent,'workbench-staging');
function guarded(p){if(![output,stage].includes(p)||relative(parent,p).startsWith('..')||p===parent)throw new Error('Unsafe output path');return p;}
await mkdir(parent,{recursive:true});
await rm(guarded(stage),{recursive:true,force:true});
try{
 await build({root:frontend,configFile:resolve(frontend,'vite.config.ts'),build:{outDir:stage}});
 const sourceFiles=['package.json','package-lock.json','index.html','vite.config.ts','tsconfig.json','scripts/build.mjs'];
 async function scan(dir,list,prefix=''){for(const e of await readdir(dir,{withFileTypes:true})){const n=prefix+e.name;if(e.isDirectory())await scan(resolve(dir,e.name),list,n+'/');else list.push(n);}}
 const source=[];await scan(resolve(frontend,'src'),source,'src/');sourceFiles.push(...source);
 const hash=async (base,n)=>createHash('sha256').update(await readFile(resolve(base,n))).digest('hex');
 const sources=Object.fromEntries(await Promise.all(sourceFiles.sort().map(async n=>[n,await hash(frontend,n)])));
 const assets=[];await scan(stage,assets);
 const files=Object.fromEntries(await Promise.all(assets.sort().map(async n=>[n,await hash(stage,n)])));
 const repository=resolve(frontend,'../..');
 let commit,dirty;
 const provenance=resolve(repository,'source-provenance.json');
 if(existsSync(provenance)){
  const manifest=JSON.parse(await readFile(provenance,'utf8'));
  if(manifest.schemaVersion!==1||!/^[a-f0-9]{40}$/.test(manifest.codeSha)||typeof manifest.workingTreeDirty!=='boolean'||!Array.isArray(manifest.files))throw new Error('Invalid source provenance');
  const declared=new Map(manifest.files.map(file=>[file.path,file.sha256]));
  if(declared.size!==manifest.files.length)throw new Error('Duplicate provenance paths');
  for(const [path,digest] of Object.entries(sources))if(declared.get('agent-demo/frontend/'+path)!==digest)throw new Error('Source provenance mismatch: '+path);
  commit=manifest.codeSha;dirty=manifest.workingTreeDirty;
 }else{
  const top=execFileSync('git',['rev-parse','--show-toplevel'],{cwd:repository,encoding:'utf8'}).trim();
  if(resolve(top)!==repository)throw new Error('Build must belong to its own repository or provide verified source provenance');
  commit=execFileSync('git',['rev-parse','HEAD'],{cwd:repository,encoding:'utf8'}).trim();
  dirty=execFileSync('git',['status','--porcelain'],{cwd:repository,encoding:'utf8'}).trim().length>0;
 }
 await writeFile(resolve(stage,'build-info.json'),JSON.stringify({schemaVersion:1,commit,dirty,sources,files},null,2)+'\n');
 await rm(guarded(output),{recursive:true,force:true});await rename(guarded(stage),guarded(output));
 console.log('Workbench built with source and asset hashes.');
}catch(error){await rm(guarded(stage),{recursive:true,force:true});throw error;}
