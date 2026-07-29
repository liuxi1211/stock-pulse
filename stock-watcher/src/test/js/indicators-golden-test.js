'use strict';

/**
 * indicators.js golden test
 *
 * 目的（024 spec Task 7.3）：用固定 OHLCV 样本验证 indicators.js 各指标函数
 * 输出的结构正确性、预热 NaN 边界、KDJ/DMI 组合口径，并对若干可直接
 * 复算的指标做数值断言（MA / EMA / BOLL middle = MA(20)）。
 *
 * 运行：node stock-watcher/src/test/js/indicators-golden-test.js
 * 成功输出 "GOLDEN TEST PASSED" 且退出码 0；失败输出具体失败项后退出码 1。
 *
 * 注意：indicators.js 顶层 IIFE 内会执行 window.Indicators = api，因此 require
 * 前必须先把 global.window shim 成普通对象，否则 window 未定义抛 ReferenceError。
 */

const assert = require('assert');
const path = require('path');

// ---- 1. 加载被测模块（先 shim window）----
global.window = {};
const Indicators = require(path.join(
    __dirname,
    '..',
    '..',
    'main',
    'resources',
    'static',
    'js',
    'charts',
    'indicators.js'
));

// ---- 2. 固定 OHLCV 样本（60 根，确定性可复现）----
// 构造方式：close 用递增 + sin 波动 + 固定伪随机扰动；high/low 在 close 基础上
// 上下浮动；volume 与价格变化幅度相关。完全无 Math.random，保证跨机器一致。
const N = 60;
const closes = new Array(N);
const highs = new Array(N);
const lows = new Array(N);
const volumes = new Array(N);

let base = 10.0;
for (let i = 0; i < N; i++) {
    const wave = Math.sin(i / 3.0) * 0.6;          // 主波动
    const drift = 0.05 * i;                         // 缓慢上行
    const noise = ((i * 7 + 3) % 11) * 0.03 - 0.15; // 固定扰动序列，范围约 [-0.15, 0.15]
    const close = +(base + drift + wave + noise).toFixed(4);
    const spread = 0.25 + ((i * 5) % 7) * 0.05;     // 高低差，0.25 ~ 0.55
    const high = +(close + spread).toFixed(4);
    const low = +(close - spread).toFixed(4);
    const vol = 100000 + ((i * 13) % 50) * 1000 + Math.round(Math.abs(wave) * 50000);
    closes[i] = close;
    highs[i] = high;
    lows[i] = low;
    volumes[i] = vol;
}

// ---- 3. 数值辅助 ----
const isNaNv = (x) => Number.isNaN(x);

function expectNaNRange(arr, from, toExcl, label) {
    for (let i = from; i < toExcl; i++) {
        assert.ok(
            isNaNv(arr[i]),
            `${label}: 预期索引 [${from},${toExcl}) 为 NaN，但 arr[${i}]=${arr[i]}`
        );
    }
}

function expectFiniteRange(arr, from, toExcl, label) {
    for (let i = from; i < toExcl; i++) {
        assert.ok(
            !isNaNv(arr[i]) && Number.isFinite(arr[i]),
            `${label}: 预期索引 [${from},${toExcl}) 为有限数，但 arr[${i}]=${arr[i]}`
        );
    }
}

function sameLength(arrs, label) {
    const len = arrs[0].length;
    for (const a of arrs) {
        assert.strictEqual(a.length, len, `${label}: 长度不一致，期望 ${len}，实际 ${a.length}`);
    }
    return len;
}

// ---- 4. 用例集 ----
const tests = [];

tests.push(['fmtDate("20240103") === "2024-01-03"', () => {
    assert.strictEqual(Indicators.fmtDate('20240103'), '2024-01-03');
    assert.strictEqual(Indicators.fmtDate('19991231'), '1999-12-31');
}]);

tests.push(['MA(5): 前4个 NaN，第5个(索引4) = 前5个 close 的均值', () => {
    const ma5 = Indicators.MA(closes, 5);
    assert.strictEqual(ma5.length, N, 'MA(5) 长度应等于输入');
    expectNaNRange(ma5, 0, 4, 'MA(5)');
    expectFiniteRange(ma5, 4, N, 'MA(5)');
    const expected = (closes[0] + closes[1] + closes[2] + closes[3] + closes[4]) / 5;
    assert.ok(
        Math.abs(ma5[4] - expected) < 1e-9,
        `MA(5)[4] 期望 ${expected}，实际 ${ma5[4]}`
    );
    // 抽样校验中段一处
    const sample = (closes[30] + closes[31] + closes[32] + closes[33] + closes[34]) / 5;
    assert.ok(Math.abs(ma5[34] - sample) < 1e-9, `MA(5)[34] 校验失败`);
}]);

tests.push(['EMA(12): 前11个 NaN，索引11 = 前12个 close 的 SMA', () => {
    const ema12 = Indicators.EMA(closes, 12);
    assert.strictEqual(ema12.length, N, 'EMA(12) 长度应等于输入');
    expectNaNRange(ema12, 0, 11, 'EMA(12)');
    expectFiniteRange(ema12, 11, N, 'EMA(12)');
    let sum = 0;
    for (let i = 0; i < 12; i++) sum += closes[i];
    const seed = sum / 12;
    assert.ok(
        Math.abs(ema12[11] - seed) < 1e-9,
        `EMA(12)[11] 期望 SMA 种子 ${seed}，实际 ${ema12[11]}`
    );
}]);

tests.push(['MACD: dif 索引25 开始有效；dif/dea/hist 同长', () => {
    const { dif, dea, hist } = Indicators.MACD(closes);
    sameLength([dif, dea, hist], 'MACD');
    // 默认 slowperiod=26，dif 在 slowperiod-1=25 开始有效
    expectNaNRange(dif, 0, 25, 'MACD.dif');
    expectFiniteRange(dif, 25, N, 'MACD.dif');
    // dea 是 dif 的 EMA(9)，从 dif 首个有效位 25 再延后 8 位 = 33
    expectNaNRange(dea, 0, 33, 'MACD.dea');
    expectFiniteRange(dea, 33, N, 'MACD.dea');
    // hist 在 dif、dea 同时有效后才有值
    expectNaNRange(hist, 0, 33, 'MACD.hist');
    expectFiniteRange(hist, 33, N, 'MACD.hist');
    // 抽样验证 hist = dif - dea（TA-Lib 口径，非 2*(dif-dea)）
    for (const i of [33, 40, 50, N - 1]) {
        const expected = dif[i] - dea[i];
        assert.ok(
            Math.abs(hist[i] - expected) < 1e-9,
            `MACD.hist[${i}] 期望 ${expected}，实际 ${hist[i]}`
        );
    }
}]);

tests.push(['KDJ: k/d/j 同长；有效段 j = 3*k - 2*d', () => {
    const { k, d, j } = Indicators.KDJ(highs, lows, closes);
    sameLength([k, d, j], 'KDJ');
    // fastk=9, slowk=3, slowd=3：k 在 9-1 + 3-1 = 10 开始有效；d 在 10 + 3-1 = 12 开始有效
    expectNaNRange(k, 0, 10, 'KDJ.k');
    expectFiniteRange(k, 10, N, 'KDJ.k');
    expectNaNRange(d, 0, 12, 'KDJ.d');
    expectFiniteRange(d, 12, N, 'KDJ.d');
    // j 仅在 k、d 同时有效处有值，故与 d 同段
    expectNaNRange(j, 0, 12, 'KDJ.j');
    expectFiniteRange(j, 12, N, 'KDJ.j');
    // 验证组合口径 j = 3k - 2d
    for (const i of [12, 20, 30, 45, N - 1]) {
        const expected = 3 * k[i] - 2 * d[i];
        assert.ok(
            Math.abs(j[i] - expected) < 1e-9,
            `KDJ.j[${i}] 期望 ${expected}，实际 ${j[i]}`
        );
    }
}]);

tests.push(['RSI(14): 索引14 有效，值 ∈ [0,100]', () => {
    const { rsi14 } = Indicators.RSI(closes, [14]);
    assert.strictEqual(rsi14.length, N, 'RSI(14) 长度应等于输入');
    // 代码逻辑：rsi[period] 即索引 14 首次有效
    expectNaNRange(rsi14, 0, 14, 'RSI(14)');
    expectFiniteRange(rsi14, 14, N, 'RSI(14)');
    for (let i = 14; i < N; i++) {
        assert.ok(
            rsi14[i] >= 0 && rsi14[i] <= 100,
            `RSI(14)[${i}]=${rsi14[i]} 越界 [0,100]`
        );
    }
}]);

tests.push(['BOLL: upper/middle/lower 同长；middle = MA(20)；upper>=middle>=lower', () => {
    const { upper, middle, lower } = Indicators.BOLL(closes);
    sameLength([upper, middle, lower], 'BOLL');
    const ma20 = Indicators.MA(closes, 20);
    for (let i = 19; i < N; i++) {
        assert.ok(
            Math.abs(middle[i] - ma20[i]) < 1e-9,
            `BOLL.middle[${i}] 应等于 MA(20)，期望 ${ma20[i]}，实际 ${middle[i]}`
        );
        assert.ok(
            upper[i] >= middle[i] - 1e-9 && middle[i] >= lower[i] - 1e-9,
            `BOLL[${i}] 序关系失败：upper=${upper[i]} middle=${middle[i]} lower=${lower[i]}`
        );
    }
}]);

tests.push(['WR: 值 ∈ [-100, 0]', () => {
    const wr = Indicators.WR(highs, lows, closes);
    assert.strictEqual(wr.length, N, 'WR 长度应等于输入');
    expectNaNRange(wr, 0, 13, 'WR'); // timeperiod=14
    expectFiniteRange(wr, 13, N, 'WR');
    for (let i = 13; i < N; i++) {
        assert.ok(
            wr[i] >= -100 - 1e-9 && wr[i] <= 0 + 1e-9,
            `WR[${i}]=${wr[i]} 越界 [-100,0]`
        );
    }
}]);

tests.push(['CCI: 数组同长，预热段 NaN 后有效', () => {
    const cci = Indicators.CCI(highs, lows, closes);
    assert.strictEqual(cci.length, N, 'CCI 长度应等于输入');
    expectNaNRange(cci, 0, 13, 'CCI'); // timeperiod=14
    expectFiniteRange(cci, 13, N, 'CCI');
}]);

tests.push(['PLUS_DI / MINUS_DI: 预热段 NaN，之后有效，值 ∈ [0,100]', () => {
    const pdi = Indicators.PLUS_DI(highs, lows, closes);
    const mdi = Indicators.MINUS_DI(highs, lows, closes);
    assert.strictEqual(pdi.length, N, 'PLUS_DI 长度');
    assert.strictEqual(mdi.length, N, 'MINUS_DI 长度');
    expectNaNRange(pdi, 0, 14, 'PLUS_DI'); // timeperiod=14
    expectFiniteRange(pdi, 14, N, 'PLUS_DI');
    expectNaNRange(mdi, 0, 14, 'MINUS_DI');
    expectFiniteRange(mdi, 14, N, 'MINUS_DI');
    for (let i = 14; i < N; i++) {
        assert.ok(pdi[i] >= 0 && pdi[i] <= 100, `PLUS_DI[${i}]=${pdi[i]} 越界 [0,100]`);
        assert.ok(mdi[i] >= 0 && mdi[i] <= 100, `MINUS_DI[${i}]=${mdi[i]} 越界 [0,100]`);
    }
}]);

tests.push(['ADX: 预热段 NaN（2*14=28），之后有效', () => {
    const adx = Indicators.ADX(highs, lows, closes);
    assert.strictEqual(adx.length, N, 'ADX 长度');
    expectNaNRange(adx, 0, 28, 'ADX');
    expectFiniteRange(adx, 28, N, 'ADX');
}]);

tests.push(['ATR: 预热段 NaN，之后为正', () => {
    const atr = Indicators.ATR(highs, lows, closes);
    assert.strictEqual(atr.length, N, 'ATR 长度');
    expectNaNRange(atr, 0, 14, 'ATR'); // timeperiod=14
    expectFiniteRange(atr, 14, N, 'ATR');
    for (let i = 14; i < N; i++) {
        assert.ok(atr[i] > 0, `ATR[${i}]=${atr[i]} 应为正`);
    }
}]);

tests.push(['OBV: 首值 0，长度同输入', () => {
    const obv = Indicators.OBV(closes, volumes);
    assert.strictEqual(obv.length, N, 'OBV 长度应等于输入');
    assert.strictEqual(obv[0], 0, 'OBV[0] 应为 0');
    // 全段都应为有限数（OBV 无预热期）
    expectFiniteRange(obv, 0, N, 'OBV');
}]);

tests.push(['SAR: 长度同输入，全段有限', () => {
    const sar = Indicators.SAR(highs, lows);
    assert.strictEqual(sar.length, N, 'SAR 长度应等于输入');
    expectFiniteRange(sar, 0, N, 'SAR');
}]);

tests.push(['额外预热 NaN 约束汇总：MA(5) 前4 / EMA(12) 前11 / MACD dif 前25', () => {
    // 此用例是对"预热 NaN"硬约束的集中复测，便于失败时一眼定位。
    const ma5 = Indicators.MA(closes, 5);
    for (let i = 0; i < 4; i++) {
        assert.ok(isNaNv(ma5[i]), `MA(5) 预热约束：arr[${i}] 应为 NaN，实际 ${ma5[i]}`);
    }
    const ema12 = Indicators.EMA(closes, 12);
    for (let i = 0; i < 11; i++) {
        assert.ok(isNaNv(ema12[i]), `EMA(12) 预热约束：arr[${i}] 应为 NaN，实际 ${ema12[i]}`);
    }
    const { dif } = Indicators.MACD(closes);
    for (let i = 0; i < 25; i++) {
        assert.ok(isNaNv(dif[i]), `MACD.dif 预热约束：arr[${i}] 应为 NaN，实际 ${dif[i]}`);
    }
}]);

// ---- 5. 运行 ----
const failures = [];
let passed = 0;
for (const [name, fn] of tests) {
    try {
        fn();
        passed++;
        console.log(`  [OK] ${name}`);
    } catch (e) {
        failures.push({ name, err: e });
        console.log(`  [FAIL] ${name}`);
        console.log(`         ${e.message}`);
    }
}

console.log('');
console.log(`Total: ${tests.length}, Passed: ${passed}, Failed: ${failures.length}`);

if (failures.length > 0) {
    console.log('');
    console.log('GOLDEN TEST FAILED');
    process.exit(1);
} else {
    console.log('GOLDEN TEST PASSED');
    process.exit(0);
}
