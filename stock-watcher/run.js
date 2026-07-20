#!/usr/bin/env node
'use strict';

/**
 * stock-watcher 启动脚本（跨平台 Node.js，替代旧 .bat/.sh）
 *
 * Usage: node run.js <command> [options]
 *
 * Commands (compile-centric):
 *   compile-dev       ⚡ 增量编译（跳过 clean，快）- 开发时高频用
 *   compile           全量 clean+compile - 打包前 / 重置状态用
 *   start             编译 + 启动（默认后台 + 日志文件）
 *   start-dev         编译 + 开发模式启动（dev profile + 热重载）
 *   start-foreground  编译 + 前台启动（阻塞终端，Ctrl+C 停止）
 *   test              运行单元测试
 *   package           打包（跳过测试）
 *   package-all       打包（含测试）
 *   status            查看端口 / PID / 健康探活
 *   stop              按端口找 PID 并 kill
 *   check-env         环境检查（JDK21 / Maven Wrapper / secret / 端口）
 *   logs              tail -f 日志文件
 *   help              显示帮助
 *
 * Options:
 *   --port=N          临时覆盖端口（不改 application.yml）
 *   --foreground      前台运行（默认后台）
 *   --clean / --full  start 时强制 clean compile（默认增量）
 */

const { spawn, spawnSync, execSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const net = require('net');
const http = require('http');

const ROOT_DIR = __dirname;
const MVNW = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
const MVNW_PATH = path.resolve(ROOT_DIR, MVNW);
const APP_YML = path.resolve(ROOT_DIR, 'src/main/resources/application.yml');
const SECRET_FILE = path.resolve(ROOT_DIR, 'src/main/resources/application-secret.properties');
const SECRET_TEMPLATE = path.resolve(ROOT_DIR, 'src/main/resources/application-secret.properties.template');
const LOG_DIR = path.resolve(ROOT_DIR, '..', '.logs');
const LOG_FILE = path.join(LOG_DIR, 'watcher.log');
const PID_FILE = path.join(LOG_DIR, 'watcher.pid');

const DEFAULT_PORT = 8080;
const DEFAULT_HOST = '127.0.0.1';
const HEALTH_PATH = '/actuator/health';
const REQUIRED_JAVA_VERSION = '21';
const START_TIMEOUT_MS = 120000;

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
// 配置读取（解析 application.yml 的 server.port）
// ============================================================
function readAppConfig() {
  let port = DEFAULT_PORT;
  try {
    if (!fs.existsSync(APP_YML)) return { port };
    const text = fs.readFileSync(APP_YML, 'utf8');
    const lines = text.split(/\r?\n/);
    let inServer = false;
    for (const line of lines) {
      if (/^[A-Za-z]/.test(line)) {
        inServer = /^server:\s*$/.test(line.trim());
        continue;
      }
      if (inServer) {
        const m = line.match(/^\s+port:\s*(\d+)/);
        if (m) port = parseInt(m[1], 10);
      }
    }
  } catch (e) { /* keep default */ }
  return { port };
}

function resolvePort(override) {
  if (override) {
    const n = parseInt(override, 10);
    if (!Number.isNaN(n)) return n;
  }
  return readAppConfig().port;
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
  const urls = [
    `http://${DEFAULT_HOST}:${port}${HEALTH_PATH}`,
    `http://${DEFAULT_HOST}:${port}/health`,
    `http://${DEFAULT_HOST}:${port}/`,
  ];
  for (const url of urls) {
    const r = await httpGet(url);
    if (r.status) return { url, status: r.status, body: r.body };
  }
  return null;
}

// ============================================================
// JDK 自动发现
// ============================================================
function findJdkHome() {
  let javacPath = null;

  try {
    const cmd = process.platform === 'win32' ? 'where javac' : 'which javac';
    const r = execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
    javacPath = r.trim().split(/\r?\n/)[0];
  } catch (e) { /* fall through */ }

  if (!javacPath) {
    try {
      const r = execSync('java -XshowSettings:properties -version 2>&1', { encoding: 'utf8' });
      const m = r.match(/java\.home\s*=\s*(.+)/);
      if (m) {
        const javaHome = m[1].trim();
        const testJavac = path.join(javaHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
        if (fs.existsSync(testJavac)) javacPath = testJavac;
      }
    } catch (e) { /* ignore */ }
  }

  if (!javacPath) {
    console.log('[ERROR] Cannot find javac. Install JDK 21 and ensure it is in PATH.');
    return null;
  }

  let jdkHome = path.dirname(path.dirname(javacPath));

  // Windows: Oracle javapath redirector workaround
  if (process.platform === 'win32' && javacPath.toLowerCase().includes('javapath')) {
    try {
      const r = execSync('reg query "HKLM\\SOFTWARE\\JavaSoft\\JDK" /v "CurrentVersion" 2>&1', { encoding: 'utf8' });
      const m1 = r.match(/CurrentVersion\s+REG_SZ\s+(.+)/);
      if (m1) {
        const ver = m1[1].trim();
        const r2 = execSync(`reg query "HKLM\\SOFTWARE\\JavaSoft\\JDK\\${ver}" /v "JavaHome" 2>&1`, { encoding: 'utf8' });
        const m2 = r2.match(/JavaHome\s+REG_SZ\s+(.+)/);
        if (m2) jdkHome = m2[1].trim();
      }
    } catch (e) { /* ignore */ }
  }

  const testJavac = path.join(jdkHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
  if (!fs.existsSync(testJavac)) {
    console.log('[ERROR] JDK home has no javac:', jdkHome);
    return null;
  }

  console.log('[INFO] JDK home:', jdkHome);
  return jdkHome;
}

function checkJavaVersion(jdkHome) {
  try {
    const javaBin = path.join(jdkHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const r = execSync(`"${javaBin}" -version 2>&1`, { encoding: 'utf8' });
    if (r.includes(REQUIRED_JAVA_VERSION)) {
      console.log('[OK] Java ' + REQUIRED_JAVA_VERSION + ' detected');
      return true;
    }
    console.log('[ERROR] Java ' + REQUIRED_JAVA_VERSION + ' required. Found:');
    console.log(r.trim());
    return false;
  } catch (e) {
    console.log('[ERROR] Cannot check Java version');
    return false;
  }
}

// ============================================================
// Maven 包装
// ============================================================
function runMavenSync(jdkHome, args) {
  if (!fs.existsSync(MVNW_PATH)) {
    console.log('[ERROR] Maven wrapper not found:', MVNW_PATH);
    return 1;
  }
  const env = { ...process.env, JAVA_HOME: jdkHome };
  console.log(`[INFO] ${MVNW} ${args.join(' ')}`);
  const r = spawnSync(MVNW, args, {
    stdio: 'inherit',
    env,
    cwd: ROOT_DIR,
    shell: process.platform === 'win32',
  });
  return r.status || 0;
}

function spawnMaven(jdkHome, args, { background }) {
  if (!fs.existsSync(MVNW_PATH)) {
    console.log('[ERROR] Maven wrapper not found:', MVNW_PATH);
    return null;
  }
  const env = { ...process.env, JAVA_HOME: jdkHome };

  if (background) {
    if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR, { recursive: true });
    const logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });
    logStream.write(`\n==== ${new Date().toISOString()} start (args=${args.join(' ')}) ====\n`);
    const child = spawn(MVNW, args, {
      cwd: ROOT_DIR,
      env,
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

  const r = spawnSync(MVNW, args, {
    stdio: 'inherit',
    env,
    cwd: ROOT_DIR,
    shell: process.platform === 'win32',
  });
  return r.status || 0;
}

// ============================================================
// 命令实现
// ============================================================
function compile(jdkHome, { clean = true, quiet = false } = {}) {
  const mode = clean ? 'clean' : 'incremental';
  console.log(`[INFO] Compiling stock-watcher (${mode})...`);
  const args = [];
  if (clean) args.push('clean');
  args.push('compile');
  if (quiet) args.push('-q');
  const status = runMavenSync(jdkHome, args);
  if (status === 0) {
    console.log(`[OK] Compilation successful (${mode})`);
  } else {
    console.log(`[ERROR] Compilation failed (${mode})`);
  }
  return status;
}

function buildStartArgs(profile, port) {
  const args = ['spring-boot:run'];
  if (profile === 'dev') args.push('-Dspring.profiles.active=dev');
  if (port && port !== DEFAULT_PORT) {
    args.push(`-Dspring-boot.run.arguments=--server.port=${port}`);
  }
  return args;
}

function start(jdkHome, { profile, port, background }) {
  const args = buildStartArgs(profile, port);
  console.log(`[INFO] Starting stock-watcher (${profile === 'dev' ? 'dev' : 'prod'}) on :${port}`);
  console.log(`[INFO] URL:     http://${DEFAULT_HOST}:${port}`);
  console.log(`[INFO] Swagger: http://${DEFAULT_HOST}:${port}/swagger-ui.html`);
  if (profile === 'dev') console.log('[INFO] Hot reload enabled (dev profile)');
  if (background) console.log(`[INFO] Background mode, log: ${LOG_FILE}`);
  console.log('');
  return spawnMaven(jdkHome, args, { background });
}

async function waitForPort(port, timeoutMs = START_TIMEOUT_MS) {
  const startMs = Date.now();
  while (Date.now() - startMs < timeoutMs) {
    if (await checkPortInUse(port)) return Date.now() - startMs;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return 0;
}

async function statusCommand() {
  const port = resolvePort();
  const inUse = await checkPortInUse(port);
  if (!inUse) {
    console.log(`[status] stock-watcher NOT running on :${port}`);
    return 0;
  }
  const pid = findPidOnPort(port);
  console.log(`[status] stock-watcher running on :${port}  PID=${pid || '?'}`);
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

function checkEnvCommand(jdkHome) {
  console.log('=== stock-watcher environment check ===');
  let ok = true;

  if (!jdkHome) {
    console.log('  [FAIL] JDK not found');
    ok = false;
  } else if (!checkJavaVersion(jdkHome)) {
    ok = false;
  } else {
    console.log(`  [OK]   JDK 21: ${jdkHome}`);
  }

  if (fs.existsSync(MVNW_PATH)) {
    console.log(`  [OK]   Maven wrapper: ${MVNW}`);
  } else {
    console.log(`  [FAIL] Maven wrapper missing: ${MVNW_PATH}`);
    ok = false;
  }

  if (fs.existsSync(SECRET_FILE)) {
    console.log('  [OK]   application-secret.properties exists');
  } else if (fs.existsSync(SECRET_TEMPLATE)) {
    console.log('  [FAIL] application-secret.properties missing (template exists)');
    console.log('         -> cd src/main/resources && cp application-secret.properties.template application-secret.properties');
    ok = false;
  } else {
    console.log('  [FAIL] application-secret.properties missing (no template found either)');
    ok = false;
  }

  const { port } = readAppConfig();
  console.log(`  [INFO] Configured port: ${port}`);

  return ok ? 0 : 1;
}

function printHelp() {
  console.log('');
  console.log('stock-watcher - Cross-platform launcher (Node.js)');
  console.log('=================================================');
  console.log('');
  console.log('Usage: node run.js <command> [options]');
  console.log('');
  console.log('Commands (compile-centric):');
  console.log('  compile-dev       ⚡ Incremental compile (fast, no clean) - use during dev');
  console.log('  compile           Full clean+compile - use before packaging or to reset state');
  console.log('  start             compile + start (background by default)');
  console.log('  start-dev         compile + start in dev mode (hot reload)');
  console.log('  start-foreground  compile + start in foreground (block terminal)');
  console.log('');
  console.log('Other commands:');
  console.log('  test              Run unit tests');
  console.log('  package           Package (skip tests)');
  console.log('  package-all       Package (with tests)');
  console.log('  status            Show port / PID / health');
  console.log('  stop              Stop the service');
  console.log('  check-env         Environment check');
  console.log('  logs              Tail log file');
  console.log('  help              Show this help');
  console.log('');
  console.log('Options:');
  console.log('  --port=N          Override port (without editing application.yml)');
  console.log('  --foreground      Run in foreground (default: background)');
  console.log('  --no-clean        Alias of compile-dev (for muscle memory)');
  console.log('');
  console.log('Port config source: src/main/resources/application.yml -> server.port');
  console.log('');
  console.log('Examples:');
  console.log('  node run.js compile-dev                  # ⚡ fastest compile (incremental)');
  console.log('  node run.js compile                     # full clean+compile');
  console.log('  node run.js start-dev --port=8090        # compile + dev start on 8090');
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

  // 不需要 JDK 的命令
  if (command === 'status') process.exit(await statusCommand());
  if (command === 'stop') process.exit(stopCommand());
  if (command === 'logs') process.exit(logsCommand());

  // 需要 JDK
  const jdkHome = findJdkHome();
  if (!jdkHome) process.exit(1);
  if (!checkJavaVersion(jdkHome)) process.exit(1);

  const port = resolvePort(opts.port);

  switch (command) {
    case 'check-env':
      process.exit(checkEnvCommand(jdkHome));
    case 'compile':
      process.exit(compile(jdkHome, { clean: true }));
    case 'compile-dev':
    case 'incremental':
      process.exit(compile(jdkHome, { clean: false }));
    case 'test':
      process.exit(runMavenSync(jdkHome, ['test']));
    case 'package':
      process.exit(runMavenSync(jdkHome, ['clean', 'package', '-DskipTests']));
    case 'package-all':
      process.exit(runMavenSync(jdkHome, ['clean', 'package']));
    case 'start':
    case 'start-dev':
    case 'start-foreground': {
      const profile = command === 'start-dev' ? 'dev' : 'prod';
      const bg = command !== 'start-foreground' && opts.background;
      if (!(await ensurePortFree(port))) process.exit(1);
      // start 默认走增量编译（快），加 --clean / --full 才做 clean compile
      const cleanCompile = opts.rest.includes('--clean') || opts.rest.includes('--full');
      console.log(`[INFO] Pre-compile (${cleanCompile ? 'clean' : 'incremental'}, quiet)...`);
      if (compile(jdkHome, { clean: cleanCompile, quiet: true }) !== 0) process.exit(1);
      const r = start(jdkHome, { profile, port, background: bg });
      if (bg) {
        console.log(`[INFO] Waiting for port :${port} (timeout ${START_TIMEOUT_MS / 1000}s)...`);
        const elapsed = await waitForPort(port, START_TIMEOUT_MS);
        if (elapsed > 0) {
          console.log(`[OK] stock-watcher started on :${port} (took ${(elapsed / 1000).toFixed(1)}s)`);
          console.log(`[INFO] Logs:  node run.js logs`);
          console.log(`[INFO] Stop:  node run.js stop`);
          process.exit(0);
        } else {
          console.log('[ERROR] stock-watcher did not start within timeout');
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
  findJdkHome, checkJavaVersion, readAppConfig, checkPortInUse,
  findPidOnPort, resolvePort, ensurePortFree, waitForPort,
  ROOT_DIR, LOG_FILE, PID_FILE, DEFAULT_PORT,
};
