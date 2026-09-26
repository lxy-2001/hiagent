import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
const root = resolve(import.meta.dirname, '../../..');
const classpath = [resolve(root, 'agent-demo/target/test-classes'), resolve(root, 'agent-demo/target/classes'), readFileSync(resolve(root, 'agent-demo/target/demo-test-classpath.txt'), 'utf8').trim()].join(process.platform === 'win32' ? ';' : ':');
const packaged = process.env.WORKBENCH_PACKAGED === '1';
const dependencies = readFileSync(resolve(root, 'agent-demo/target/demo-test-classpath.txt'), 'utf8').trim().split(process.platform === 'win32' ? ';' : ':');
const loaderPath = [resolve(root, 'agent-demo/target/test-classes'), ...dependencies.filter(path => !/[\\/]com[\\/]agentflow[\\/]/.test(path))].join(',');
const args = packaged
  ? ['-Dloader.path=' + loaderPath, '-Dloader.main=com.agentflow.demo.workbench.WorkbenchFixtureLauncher', '-cp', resolve(root, 'agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar'), 'org.springframework.boot.loader.launch.PropertiesLauncher', '--offline', '15180']
  : ['-cp', classpath, 'com.agentflow.demo.workbench.WorkbenchFixtureLauncher', '--offline', '15180'];
const child = spawn('java', args, { cwd: root, stdio: 'inherit', windowsHide: true });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
child.on('exit', code => process.exit(code ?? 1));
