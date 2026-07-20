#!/usr/bin/env node
'use strict';

/**
 * stock-engine 启动脚本（跨平台 Node.js，替代旧 .bat/.sh）
 *
 * Usage: node run.js <command> [options]
 *
 * Commands:
 *   start             启动服务（默认后台 + 日志文件）
 *   start-dev         开发模式启动（uvicorn --reload 热重载）
 *   start-foreground   前台启动（阻塞终端，Ctrl+C 停止）
 *   install           pip install -r requirements.txt
 *   test              运行 pytest
 *   shell             激活 Conda 环境的子 shell
 *   status            查看端口 / PID / 健康探活
 *   stop              按端口找 PID 并 kill
 *   check-env         环境检查（Conda / stock 环境 / 依赖 / 端口）
 *   logs              tail -f 日志文件
 *   help              显示帮助
 *
 * Options:
 *   --port=N          临时覆盖端口（不改 .env）
 *   --foreground      前台运行（默认后台）
 */

const { spawn, spawnSync, execSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const net = require('net');
const http = require('http');

const ROOT_DIR = __dirname;
const ENV_FILE = path.resolve(ROOT_DIR, '.env');
const ENV_EXAMPLE = path.resolve(ROOT_DIR, '.env.example');
const REQ_FILE = path.resolve(ROOT_DIR, 'requirements.txt');
const LOG_DIR = path.resolve(ROOT_DIR, '..', '.logs');
const LOG_FILE = path.join(LOG_DIR, 'engine.log');
const PID_FILE = path.join(LOG_DIR, 'engine.pid');

const CONDA_ENV = 'stock';
const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 8085;
const HEALTH_PATH = '/health';
const START_TIMEOUT_MS = 60000;

// ============================================================
// 参数解析
// ============================================================
function parseArgs(argv) {
  const opts = { background: true, port: null, command: null, rest: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--background' || a === '-b') opts.background = true;
    else if (a === '--foreground' || a === '-f') opts.background = false;
    else if (a.startsWith('--port=')) opts.port = a.slice(7);
    else if (a === '--port' && i + 1 < argv.length) opts.port = argv[++i];
    else opts.rest.push(a);
  }
  opts.command = opts.rest[0];
  return opts;
}

// ============================================================
// 配置读取（解析 .env 的 HOST / PORT）
// ============================================================
function readEnv() {
  const cfg = { host: DEFAULT_HOST, port: DEFAULT_PORT };
  try {
    if (!fs.existsSync(ENV_FILE)) return cfg;
    const text = fs.readFileSync(ENV_FILE, 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*(HOST|PORT)\s*=\s*(\S+)/);
      if (m) {
        if (m[1] === 'HOST') cfg.host = m[2];
        if (m[1] === 'PORT') cfg.port = parseInt(m[2], 10);
      }
    }
  } catch (e) { /* keep default */ }
  return cfg;
}

function resolvePort(override) {
  if (override) {
    const n = parseInt(override, 10);
    if (!Number.isNaN(n)) return n;
  }
  return readEnv().port;
}

function resolveHost() {
  return readEnv().host;
}

// ============================================================
// 端口检测
// ============================================================
function checkPortInUse(port, host = DEFAULT_HOST) {
  return new Promise((resolve) => {
    const tester = net.createConnection({ port, host });
    let done = false;
    const finish = (v) => { if (!done) { done = true; tester.destroy(); resolve(v); } };
    tester.once('connect', () => finish(true));
    tester.once('error', () => finish(false));
    setTimeout(() => finish(false), 800);
  });
}

function findPidOnPort(port) {
  try {
    if (process.platform === 'win32') {
      const r = execSync(`netstat -ano -p tcp | findstr ":${port}"`, { encoding: 'utf8' });
      for (const line of r.split(/\r?\n/)) {
        if (line.includes('LISTENING')) {
          const parts = line.trim().split(/\s+/);
          const pid = parts[parts.length - 1];
          if (pid && /^\d+$/.test(pid)) return pid;
        }
      }
      return null;
    } else {
      const r = execSync(`lsof -i :${port} -t 2>/dev/null`, { encoding: 'utf8' });
      return r.trim().split('\n')[0] || null;
    }
  } catch (e) { return null; }
}

async function ensurePortFree(port) {
  const inUse = await checkPortInUse(port);
  if (!inUse) return true;
  const pid = findPidOnPort(port);
  console.log(`[ERROR] Port :${port} is already in use${pid ? ` (PID=${pid})` : ''}`);
  console.log(`  -> stop it:       node run.js stop`);
  console.log(`  -> or override:  node run.js start --port=${port + 1}`);
  return false;
}

// ============================================================
// HTTP 健康探活
// ============================================================
function httpGet(url, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', (e) => resolve({ error: e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ error: 'timeout' }); });
  });
}

async function probeHealth(port) {
  const host = resolveHost();
  const urls = [
    `http://${host}:${port}${HEALTH_PATH}`,
    `http://${host}:${port}/`,
  ];
  for (const url of urls) {
    const r = await httpGet(url);
    if (r.status) return { url, status: r.status, body: r.body };
  }
  return null;
}

// ============================================================
// Conda 自动发现
// ============================================================
function findConda() {
  let condaPath = null;

  if (process.env.CONDA_EXE && fs.existsSync(process.env.CONDA_EXE)) {
    return process.env.CONDA_EXE;
  }

  try {
    const cmd = process.platform === 'win32' ? 'where conda' : 'which conda';
    const r = execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
    condaPath = r.trim().split(/\r?\n/)[0];
    if (condaPath) return condaPath;
  } catch (e) { /* fall through */ }

  const commonPaths = process.platform === 'win32'
    ? [
        'D:\\javaApp\\miniforge\\Scripts\\conda.exe',
        'D:\\javaApp\\miniconda\\Scripts\\conda.exe',
        'C:\\Users\\' + process.env.USERNAME + '\\miniforge3\\Scripts\\conda.exe',
        'C:\\Users\\' + process.env.USERNAME + '\\miniconda3\\Scripts\\conda.exe',
        'C:\\ProgramData\\miniforge3\\Scripts\\conda.exe',
        'C:\\ProgramData\\miniconda3\\Scripts\\conda.exe',
      ]
    : [
        '/usr/local/miniforge3/bin/conda',
        '/usr/local/miniconda3/bin/conda',
        '/usr/local/anaconda3/bin/conda',
        (process.env.HOME || '') + '/miniforge3/bin/conda',
        (process.env.HOME || '') + '/miniconda3/bin/conda',
        (process.env.HOME || '') + '/anaconda3/bin/conda',
      ];

  for (const p of commonPaths) {
    if (fs.existsSync(p)) return p;
  }

  console.log('[ERROR] Cannot find Conda. Install Miniforge/Miniconda/Anaconda.');
  return null;
}

function checkCondaEnv(condaPath) {
  try {
    const r = execSync(`"${condaPath}" env list`, { encoding: 'utf8' });
    return r.split(/\r?\n/).some((line) => new RegExp(`\\s${CONDA_ENV}\\s`).test(line) || new RegExp(`^${CONDA_ENV}\\s`).test(line));
  } catch (e) {
    return false;
  }
}

// ============================================================
// Python 包装
// ============================================================
function runCondaSync(condaPath, args) {
  const cmdArgs = ['run', '--no-capture-output', '-n', CONDA_ENV, 'python', ...args];
  console.log(`[INFO] conda ${cmdArgs.join(' ')}`);
  const r = spawnSync(condaPath, cmdArgs, {
    stdio: 'inherit',
    cwd: ROOT_DIR,
    shell: process.platform === 'win32',
  });
  return r.status || 0;
}

function spawnConda(condaPath, args, { background }) {
  const cmdArgs = ['run', '--no-capture-output', '-n', CONDA_ENV, 'python', ...args];

  if (background) {
    if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR, { recursive: true });
    const logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });
    logStream.write(`\n==== ${new Date().toISOString()} start (args=${args.join(' ')}) ====\n`);
    const child = spawn(condaPath, cmdArgs, {
      cwd: ROOT_DIR,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
      shell: process.platform === 'win32',
      windowsHide: true,
    });
    child.stdout.pipe(logStream);
    child.stderr.pipe(logStream);
    child.unref();
    if (child.pid) fs.writeFileSync(PID_FILE, String(child.pid));
    return child;
  }

  console.log(`[INFO] conda ${cmdArgs.join(' ')}`);
  const r = spawnSync(condaPath, cmdArgs, {
    stdio: 'inherit',
    cwd: ROOT_DIR,
    shell: process.platform === 'win32',
  });
  return r.status || 0;
}

// ============================================================
// 命令实现
// ============================================================
function buildStartArgs(profile, port, host) {
  const args = ['-m', 'uvicorn', 'main:app', '--host', host, '--port', String(port)];
  if (profile === 'dev') args.push('--reload');
  return args;
}

function start(condaPath, { profile, port, host, background }) {
  const args = buildStartArgs(profile, port, host);
  console.log(`[INFO] Starting stock-engine (${profile === 'dev' ? 'dev' : 'prod'}) on ${host}:${port}`);
  console.log(`[INFO] API docs: http://${host}:${port}/docs`);
  console.log(`[INFO] Health:   http://${host}:${port}${HEALTH_PATH}`);
  if (profile === 'dev') console.log('[INFO] Hot reload enabled (--reload)');
  if (background) console.log(`[INFO] Background mode, log: ${LOG_FILE}`);
  console.log('');
  return spawnConda(condaPath, args, { background });
}

async function waitForPort(port, host, timeoutMs = START_TIMEOUT_MS) {
  const startMs = Date.now();
  while (Date.now() - startMs < timeoutMs) {
    if (await checkPortInUse(port, host)) return Date.now() - startMs;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return 0;
}

async function statusCommand() {
  const port = resolvePort();
  const host = resolveHost();
  const inUse = await checkPortInUse(port, host);
  if (!inUse) {
    console.log(`[status] stock-engine NOT running on ${host}:${port}`);
    return 0;
  }
  const pid = findPidOnPort(port);
  console.log(`[status] stock-engine running on ${host}:${port}  PID=${pid || '?'}`);
  const health = await probeHealth(port);
  if (health) {
    console.log(`  health: HTTP ${health.status}  ${health.body.slice(0, 120).replace(/\s+/g, ' ')}`);
    console.log(`  probe url: ${health.url}`);
  } else {
    console.log('  health: NOT reachable (port open but HTTP probe failed)');
  }
  return 0;
}

function stopCommand() {
  const port = resolvePort();
  const pid = findPidOnPort(port);
  if (!pid) {
    console.log(`[stop] No process listening on :${port}`);
    if (fs.existsSync(PID_FILE)) fs.unlinkSync(PID_FILE);
    return 0;
  }
  try {
    if (process.platform === 'win32') {
      execSync(`taskkill /PID ${pid} /T /F`, { stdio: 'inherit' });
    } else {
      execSync(`kill -TERM ${pid}`, { stdio: 'inherit' });
    }
    console.log(`[stop] Killed PID=${pid} on :${port}`);
    if (fs.existsSync(PID_FILE)) fs.unlinkSync(PID_FILE);
  } catch (e) {
    console.log(`[ERROR] Failed to kill PID=${pid}: ${e.message}`);
    return 1;
  }
  return 0;
}

function logsCommand() {
  if (!fs.existsSync(LOG_FILE)) {
    console.log(`[logs] Log file not found: ${LOG_FILE}`);
    console.log('       Start the service first: node run.js start');
    return 1;
  }
  console.log(`[logs] Tailing ${LOG_FILE} (Ctrl+C to stop)`);
  console.log('---');
  if (process.platform === 'win32') {
    spawnSync('powershell', ['-NoProfile', '-Command', `Get-Content -Path '${LOG_FILE}' -Wait -Tail 50`], { stdio: 'inherit' });
  } else {
    spawnSync('tail', ['-f', '-n', '50', LOG_FILE], { stdio: 'inherit' });
  }
  return 0;
}

function checkEnvCommand(condaPath) {
  console.log('=== stock-engine environment check ===');
  let ok = true;

  if (!condaPath) {
    console.log('  [FAIL] Conda not found');
    return 1;
  }
  console.log(`  [OK]   Conda: ${condaPath}`);

  if (checkCondaEnv(condaPath)) {
    console.log(`  [OK]   Conda env '${CONDA_ENV}' exists`);
  } else {
    console.log(`  [FAIL] Conda env '${CONDA_ENV}' missing`);
    console.log(`         -> conda create -n ${CONDA_ENV} python=3.12 -y`);
    ok = false;
  }

  if (fs.existsSync(REQ_FILE)) {
    console.log(`  [OK]   requirements.txt exists`);
  } else {
    console.log(`  [FAIL] requirements.txt missing: ${REQ_FILE}`);
    ok = false;
  }

  if (fs.existsSync(ENV_FILE)) {
    console.log(`  [OK]   .env exists`);
  } else if (fs.existsSync(ENV_EXAMPLE)) {
    console.log(`  [WARN] .env missing (will use defaults from config.py)`);
    console.log(`         -> cp .env.example .env  (then edit if needed)`);
  } else {
    console.log(`  [WARN] .env and .env.example both missing (will use defaults)`);
  }

  const { host, port } = readEnv();
  console.log(`  [INFO] Configured: ${host}:${port}`);

  return ok ? 0 : 1;
}

function installCommand(condaPath) {
  console.log('[INFO] Installing dependencies...');
  return runCondaSync(condaPath, ['-m', 'pip', 'install', '-r', 'requirements.txt']);
}

function shellCommand(condaPath) {
  console.log(`[INFO] Activating Conda env '${CONDA_ENV}' at ${ROOT_DIR}`);
  console.log('[INFO] Type "exit" to return');
  console.log('');
  const shellCmd = process.platform === 'win32' ? 'cmd.exe' : 'bash';
  const r = spawnSync(condaPath, ['run', '-n', CONDA_ENV, shellCmd], {
    stdio: 'inherit',
    cwd: ROOT_DIR,
    shell: process.platform === 'win32',
  });
  return r.status || 0;
}

function printHelp() {
  console.log('');
  console.log('stock-engine - Cross-platform launcher (Node.js)');
  console.log('================================================');
  console.log('');
  console.log('Usage: node run.js <command> [options]');
  console.log('');
  console.log('Commands:');
  console.log('  start             Start service (background by default)');
  console.log('  start-dev         Start in dev mode (uvicorn --reload)');
  console.log('  start-foreground  Start in foreground (block terminal, Ctrl+C to stop)');
  console.log('  install           pip install -r requirements.txt');
  console.log('  test              Run pytest');
  console.log('  shell             Open shell in conda env');
  console.log('  status            Show port / PID / health');
  console.log('  stop              Stop the service');
  console.log('  check-env         Environment check');
  console.log('  logs              Tail log file');
  console.log('  help              Show this help');
  console.log('');
  console.log('Options:');
  console.log('  --port=N          Override port (without editing .env)');
  console.log('  --foreground      Run in foreground (default: background)');
  console.log('');
  console.log('Port config source: .env (HOST / PORT)  ->  fallback config.py defaults (127.0.0.1:8085)');
  console.log('');
  console.log('Examples:');
  console.log('  node run.js start');
  console.log('  node run.js start-dev --port=8095');
  console.log('  node run.js status');
  console.log('  node run.js stop');
  console.log('  node run.js logs');
  console.log('');
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const command = opts.command;

  if (!command || command === 'help' || command === '-h' || command === '--help') {
    printHelp();
    process.exit(command === 'help' ? 0 : 1);
  }

  // 不需要 Conda 的命令
  if (command === 'status') process.exit(await statusCommand());
  if (command === 'stop') process.exit(stopCommand());
  if (command === 'logs') process.exit(logsCommand());

  // 需要 Conda
  const condaPath = findConda();
  if (!condaPath) process.exit(1);

  if (command === 'check-env') process.exit(checkEnvCommand(condaPath));

  // 其他命令需要 env 存在
  if (!checkCondaEnv(condaPath)) {
    console.log(`[ERROR] Conda env '${CONDA_ENV}' not found.`);
    console.log(`        -> conda create -n ${CONDA_ENV} python=3.12 -y`);
    console.log(`        -> node run.js install`);
    process.exit(1);
  }

  const port = resolvePort(opts.port);
  const host = resolveHost();

  switch (command) {
    case 'install':
      process.exit(installCommand(condaPath));
    case 'test':
      process.exit(runCondaSync(condaPath, ['-m', 'pytest', '-v']));
    case 'shell':
      process.exit(shellCommand(condaPath));
    case 'start':
    case 'start-dev':
    case 'start-foreground': {
      const profile = command === 'start-dev' ? 'dev' : 'prod';
      const bg = command !== 'start-foreground' && opts.background;
      if (!(await ensurePortFree(port, host))) process.exit(1);
      const r = start(condaPath, { profile, port, host, background: bg });
      if (bg) {
        console.log(`[INFO] Waiting for port :${port} (timeout ${START_TIMEOUT_MS / 1000}s)...`);
        const elapsed = await waitForPort(port, host, START_TIMEOUT_MS);
        if (elapsed > 0) {
          console.log(`[OK] stock-engine started on ${host}:${port} (took ${(elapsed / 1000).toFixed(1)}s)`);
          console.log(`[INFO] Logs:  node run.js logs`);
          console.log(`[INFO] Stop:  node run.js stop`);
          process.exit(0);
        } else {
          console.log('[ERROR] stock-engine did not start within timeout');
          console.log(`[INFO] Check log: ${LOG_FILE}`);
          process.exit(1);
        }
      } else {
        process.exit(typeof r === 'number' ? r : 0);
      }
      break;
    }
    default:
      console.log(`[ERROR] Unknown command: ${command}`);
      printHelp();
      process.exit(1);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  findConda, checkCondaEnv, readEnv, checkPortInUse,
  findPidOnPort, resolvePort, resolveHost, ensurePortFree, waitForPort,
  ROOT_DIR, LOG_FILE, PID_FILE, DEFAULT_PORT, CONDA_ENV,
};
