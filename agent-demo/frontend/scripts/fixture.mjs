import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
const root = resolve(import.meta.dirname, '../../..');
const classpath = [resolve(root, 'agent-demo/target/test-classes'), resolve(root, 'agent-demo/target/classes'), readFileSync(resolve(root, 'agent-demo/target/demo-test-classpath.txt'), 'utf8').trim()].join(process.platform === 'win32' ? ';' : ':');
const child = spawn('java', ['-cp', classpath, 'com.agentflow.demo.workbench.WorkbenchFixtureLauncher', '--offline', '15180'], { cwd: root, stdio: 'inherit', windowsHide: true });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
child.on('exit', code => process.exit(code ?? 1));
