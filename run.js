#!/usr/bin/env node
'use strict';

/**
 * stock-pulse 全栈编排脚本（跨平台 Node.js）
 *
 * 一键拉起 stock-engine (:8085) + stock-watcher (:8080)。
 * 启动顺序：先 engine -> 端口探活通过 -> 再 watcher。
 *
 * Usage: node run.js <command> [options]
 *
 * Commands:
 *   start            全栈启动（engine -> watcher，后台运行 + 日志）
 *   start-dev        全栈启动（dev 模式，两个服务都开热重载）
 *   start-engine     仅启动 stock-engine
 *   start-watcher    仅启动 stock-watcher
 *   status           查看两个服务的端口 / PID / 健康状态
 *   stop              停止两个服务
 *   check-env         两个服务的环境检查
 *   logs <which>     tail 某个服务的日志（watcher / engine）
 *   help              显示帮助
 *
 * Options:
 *   --engine-port=N   临时覆盖 engine 端口
 *   --watcher-port=N  临时覆盖 watcher 端口
 */

const { spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const ROOT_DIR = __dirname;
const WATCHER_RUN = path.resolve(ROOT_DIR, 'stock-watcher/run.js');
const ENGINE_RUN = path.resolve(ROOT_DIR, 'stock-engine/run.js');
const LOG_DIR = path.resolve(ROOT_DIR, '.logs');

const ENGINE_DEFAULT_PORT = 8085;
const WATCHER_DEFAULT_PORT = 8080;
const ENGINE_HOST = '127.0.0.1';
const WATCHER_HOST = '127.0.0.1';

// ============================================================
// 参数解析
// ============================================================
function parseArgs(argv) {
  const opts = { command: null, rest: [], enginePort: null, watcherPort: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--engine-port=')) opts.enginePort = a.slice(15);
    else if (a === '--engine-port' && i + 1 < argv.length) opts.enginePort = argv[++i];
    else if (a.startsWith('--watcher-port=')) opts.watcherPort = a.slice(16);
    else if (a === '--watcher-port' && i + 1 < argv.length) opts.watcherPort = argv[++i];
    else opts.rest.push(a);
  }
  opts.command = opts.rest[0];
  opts.subcommand = opts.rest[1];
  return opts;
}

// ============================================================
// 子脚本调用
// ============================================================
function runSub(scriptPath, args) {
  if (!fs.existsSync(scriptPath)) {
    console.log(`[ERROR] Sub-script not found: ${scriptPath}`);
    return 1;
  }
  const r = spawnSync('node', [scriptPath, ...args], {
    stdio: 'inherit',
    cwd: ROOT_DIR,
    shell: false,
  });
  return r.status || 0;
}

function readSubPort(scriptPath, defaultPort) {
  // 让子脚本自己 resolvePort（保留单一数据源），这里仅作展示用
  return defaultPort;
}

// ============================================================
// 命令实现
// ============================================================
function startSingle(which, profile, portOverride) {
  const script = which === 'engine' ? ENGINE_RUN : WATCHER_RUN;
  const cmd = profile === 'dev' ? 'start-dev' : 'start';
  const args = [cmd];
  if (portOverride) args.push(`--port=${portOverride}`);
  return runSub(script, args);
}

function startAll(profile) {
  const banner = (n, total) => console.log(`\n========== [${n}/${total}] stock-engine ==========`);

  // 1. engine
  banner(1, 2);
  const eStatus = startSingle('engine', profile, parseArgs(process.argv.slice(2)).enginePort);
  if (eStatus !== 0) {
    console.log('\n[ERROR] stock-engine failed to start. Aborting.');
    console.log('[INFO]   Check: node stock-engine/run.js logs');
    return 1;
  }

  // 2. watcher
  console.log('\n========== [2/2] stock-watcher ==========');
  const wStatus = startSingle('watcher', profile, parseArgs(process.argv.slice(2)).watcherPort);
  if (wStatus !== 0) {
    console.log('\n[ERROR] stock-watcher failed to start.');
    console.log('[INFO]   stock-engine is still running. Stop it: node run.js stop');
    console.log('[INFO]   Check watcher log: node stock-watcher/run.js logs');
    return 1;
  }

  // 完成
  console.log('\n========================================');
  console.log(' All services started successfully');
  console.log('========================================');
  console.log(`  stock-engine :  http://${ENGINE_HOST}:${ENGINE_DEFAULT_PORT}  (docs: /docs)`);
  console.log(`  stock-watcher:  http://${WATCHER_HOST}:${WATCHER_DEFAULT_PORT}  (swagger: /swagger-ui.html)`);
  console.log('');
  console.log('  Default login: admin / admin123');
  console.log('');
  console.log('  Logs:   node run.js logs watcher | node run.js logs engine');
  console.log('  Status: node run.js status');
  console.log('  Stop:   node run.js stop');
  console.log('');
  return 0;
}

function statusAll() {
  console.log('############ stock-engine ############');
  const e = runSub(ENGINE_RUN, ['status']);
  console.log('\n############ stock-watcher ############');
  const w = runSub(WATCHER_RUN, ['status']);
  return e === 0 && w === 0 ? 0 : 1;
}

function stopAll() {
  console.log('=== Stopping stock-watcher ===');
  const w = runSub(WATCHER_RUN, ['stop']);
  console.log('\n=== Stopping stock-engine ===');
  const e = runSub(ENGINE_RUN, ['stop']);
  return w === 0 && e === 0 ? 0 : 1;
}

function checkEnvAll() {
  console.log('############ stock-engine ############');
  const e = runSub(ENGINE_RUN, ['check-env']);
  console.log('\n############ stock-watcher ############');
  const w = runSub(WATCHER_RUN, ['check-env']);
  return e === 0 && w === 0 ? 0 : 1;
}

function logsOne(which) {
  if (which === 'engine') return runSub(ENGINE_RUN, ['logs']);
  if (which === 'watcher') return runSub(WATCHER_RUN, ['logs']);
  console.log(`[ERROR] Unknown target: ${which}. Use 'watcher' or 'engine'.`);
  console.log("  -> node run.js logs watcher");
  console.log("  -> node run.js logs engine");
  return 1;
}

function printHelp() {
  console.log('');
  console.log('stock-pulse - Full-stack orchestrator (Node.js)');
  console.log('===============================================');
  console.log('');
  console.log('Usage: node run.js <command> [options]');
  console.log('');
  console.log('Commands:');
  console.log('  start              Start both services (engine -> watcher, background)');
  console.log('  start-dev          Start both in dev mode (hot reload)');
  console.log('  start-engine       Start stock-engine only');
  console.log('  start-watcher      Start stock-watcher only');
  console.log('  status             Show port / PID / health for both');
  console.log('  stop               Stop both services');
  console.log('  check-env          Run environment check for both');
  console.log('  logs <which>       Tail log file (watcher | engine)');
  console.log('  help               Show this help');
  console.log('');
  console.log('Options:');
  console.log('  --engine-port=N    Override engine port');
  console.log('  --watcher-port=N   Override watcher port');
  console.log('');
  console.log('Services:');
  console.log(`  stock-engine   :  http://${ENGINE_HOST}:${ENGINE_DEFAULT_PORT}   (FastAPI, Conda env 'stock')`);
  console.log(`  stock-watcher  :  http://${WATCHER_HOST}:${WATCHER_DEFAULT_PORT}   (Spring Boot, JDK 21)`);
  console.log('');
  console.log('Default startup order: engine first (watcher depends on it via python.compute.url)');
  console.log('');
  console.log('Examples:');
  console.log('  node run.js start');
  console.log('  node run.js start-dev');
  console.log('  node run.js start --engine-port=8095 --watcher-port=8090');
  console.log('  node run.js status');
  console.log('  node run.js stop');
  console.log('  node run.js logs watcher');
  console.log('');
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const command = opts.command;

  if (!command || command === 'help' || command === '-h' || command === '--help') {
    printHelp();
    process.exit(command === 'help' ? 0 : 1);
  }

  switch (command) {
    case 'start':
      process.exit(startAll('prod'));
    case 'start-dev':
      process.exit(startAll('dev'));
    case 'start-engine':
      process.exit(startSingle('engine', opts.subcommand === 'dev' ? 'dev' : 'prod', opts.enginePort));
    case 'start-watcher':
      process.exit(startSingle('watcher', opts.subcommand === 'dev' ? 'dev' : 'prod', opts.watcherPort));
    case 'status':
      process.exit(statusAll());
    case 'stop':
      process.exit(stopAll());
    case 'check-env':
      process.exit(checkEnvAll());
    case 'logs':
      if (!opts.subcommand) {
        console.log('[ERROR] Usage: node run.js logs <watcher|engine>');
        process.exit(1);
      }
      process.exit(logsOne(opts.subcommand));
    default:
      console.log(`[ERROR] Unknown command: ${command}`);
      printHelp();
      process.exit(1);
  }
}

if (require.main === module) {
  main();
}
