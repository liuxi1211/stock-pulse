/**
 * 多因子选股中心前端原型脚本
 * 纯演示数据，所有「运行选股」结果均为本地模拟。
 *
 * 与统一 Schema / 标准因子库对齐：
 * - 条件叶子节点支持 factorKey + params + outputIndex
 * - 因子参数按 factors.json 元数据动态渲染
 */

const FACTOR_REGISTRY = [
  {
    key: 'MA', displayName: 'MA 简单移动平均线', category: 'OVERLAP', source: 'AKQUANT',
    params: [{ name: 'timeperiod', displayName: '周期', type: 'INT', defaultValue: 5, min: 1, max: 500, step: 1 }],
    multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'EMA', displayName: 'EMA 指数移动平均线', category: 'OVERLAP', source: 'AKQUANT',
    params: [{ name: 'timeperiod', displayName: '周期', type: 'INT', defaultValue: 12, min: 1, max: 500, step: 1 }],
    multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'RSI', displayName: 'RSI 相对强弱指标', category: 'MOMENTUM', source: 'AKQUANT',
    params: [{ name: 'timeperiod', displayName: '周期', type: 'INT', defaultValue: 14, min: 1, max: 500, step: 1 }],
    multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'MACD', displayName: 'MACD 指数平滑异同', category: 'MOMENTUM', source: 'AKQUANT',
    params: [
      { name: 'fastperiod', displayName: '快线', type: 'INT', defaultValue: 12, min: 1, max: 500, step: 1 },
      { name: 'slowperiod', displayName: '慢线', type: 'INT', defaultValue: 26, min: 1, max: 500, step: 1 },
      { name: 'signalperiod', displayName: '信号', type: 'INT', defaultValue: 9, min: 1, max: 500, step: 1 },
    ],
    multiOutput: true, outputLabels: ['macd', 'signal', 'hist'], defaultOutputIndex: 0,
  },
  {
    key: 'BOLL', displayName: 'BOLL 布林带', category: 'VOLATILITY', source: 'AKQUANT',
    params: [
      { name: 'timeperiod', displayName: '周期', type: 'INT', defaultValue: 20, min: 1, max: 500, step: 1 },
      { name: 'nbdevup', displayName: '上轨倍数', type: 'FLOAT', defaultValue: 2, min: 0.1, max: 10, step: 0.1 },
      { name: 'nbdevdn', displayName: '下轨倍数', type: 'FLOAT', defaultValue: 2, min: 0.1, max: 10, step: 0.1 },
    ],
    multiOutput: true, outputLabels: ['upper', 'middle', 'lower'], defaultOutputIndex: 1,
  },
  {
    key: 'ATR', displayName: 'ATR 真实波动幅度', category: 'VOLATILITY', source: 'AKQUANT',
    params: [{ name: 'timeperiod', displayName: '周期', type: 'INT', defaultValue: 14, min: 1, max: 500, step: 1 }],
    multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'OBV', displayName: 'OBV 能量潮', category: 'VOLUME', source: 'AKQUANT',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'CLOSE', displayName: '收盘价', category: 'PRICE', source: 'RAW',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'VOLUME', displayName: '成交量', category: 'PRICE', source: 'RAW',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'PE_TTM', displayName: 'PE_TTM 市盈率', category: 'VALUATION', source: 'TUSHARE',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'PB_TTM', displayName: 'PB_TTM 市净率', category: 'VALUATION', source: 'TUSHARE',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'ROE_TTM', displayName: 'ROE_TTM 净资产收益率', category: 'QUALITY', source: 'TUSHARE',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
  {
    key: 'TOTAL_MV', displayName: 'TOTAL_MV 总市值', category: 'VALUATION', source: 'TUSHARE',
    params: [], multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
  },
];

const COMPARATORS = ['>', '<', '>=', '<=', '==', '!='];

const CATEGORY_ORDER = ['VALUATION', 'QUALITY', 'GROWTH', 'FINANCE', 'PRICE', 'OVERLAP', 'MOMENTUM', 'VOLATILITY', 'VOLUME', 'STATISTIC'];
const CATEGORY_NAMES = {
  OVERLAP: '趋势指标', MOMENTUM: '动量指标', VOLATILITY: '波动率指标', VOLUME: '成交量指标',
  STATISTIC: '统计指标', PRICE: '价格/量直通', VALUATION: '估值因子', QUALITY: '质量因子',
  GROWTH: '成长因子', FINANCE: '财务结构',
};

function getFactorDef(key) {
  return FACTOR_REGISTRY.find(f => f.key === key);
}

function getDefaultParams(def) {
  const params = {};
  (def?.params || []).forEach(p => { params[p.name] = p.defaultValue; });
  return params;
}

function getFactorSignature(key, params, outputIndex) {
  const def = getFactorDef(key);
  if (!def) return key;
  if (def.params.length === 0) return key;
  const paramStr = def.params.map(p => `${p.name}=${params[p.name] ?? p.defaultValue}`).join(',');
  let sig = `${key}(${paramStr})`;
  if (def.multiOutput) sig += `[${outputIndex ?? def.defaultOutputIndex}]`;
  return sig;
}

function resolveFactorValue(stock, key) {
  const def = getFactorDef(key);
  if (!def) return NaN;
  const sig = getFactorSignature(key, getDefaultParams(def), def.defaultOutputIndex);
  return stock[sig] ?? NaN;
}

const MOCK_STOCKS = [
  { symbol: '600519', name: '贵州茅台', industry: '白酒', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 2840, change: 0.37,
    'PE_TTM': 28.5, 'PB_TTM': 8.1, 'ROE_TTM': 24.8, 'TOTAL_MV': 20500, 'CLOSE': 1692, 'VOLUME': 32000,
    'MA(timeperiod=5)': 1680, 'MA(timeperiod=10)': 1665, 'MA(timeperiod=20)': 1660,
    'EMA(timeperiod=12)': 1685, 'RSI(timeperiod=14)': 58,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 1.2, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.8, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.4,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 1710, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 1660, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 1610,
    'ATR(timeperiod=14)': 18, 'OBV': 1200000 },
  { symbol: '000858', name: '五粮液', industry: '白酒', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 3120, change: 0.82,
    'PE_TTM': 18.2, 'PB_TTM': 5.2, 'ROE_TTM': 19.5, 'TOTAL_MV': 5600, 'CLOSE': 146, 'VOLUME': 45000,
    'MA(timeperiod=5)': 145, 'MA(timeperiod=10)': 143, 'MA(timeperiod=20)': 142,
    'EMA(timeperiod=12)': 146, 'RSI(timeperiod=14)': 62,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.6, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.3, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.3,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 149, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 142, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 135,
    'ATR(timeperiod=14)': 2.1, 'OBV': 980000 },
  { symbol: '601398', name: '工商银行', industry: '银行', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 5420, change: 0.17,
    'PE_TTM': 5.1, 'PB_TTM': 0.52, 'ROE_TTM': 11.2, 'TOTAL_MV': 18200, 'CLOSE': 5.85, 'VOLUME': 120000,
    'MA(timeperiod=5)': 5.82, 'MA(timeperiod=10)': 5.81, 'MA(timeperiod=20)': 5.80,
    'EMA(timeperiod=12)': 5.83, 'RSI(timeperiod=14)': 48,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.01, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': -0.01, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.02,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 5.92, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 5.80, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 5.68,
    'ATR(timeperiod=14)': 0.06, 'OBV': 2100000 },
  { symbol: '000001', name: '平安银行', industry: '银行', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 9560, change: 0.29,
    'PE_TTM': 4.8, 'PB_TTM': 0.48, 'ROE_TTM': 10.8, 'TOTAL_MV': 2100, 'CLOSE': 10.25, 'VOLUME': 88000,
    'MA(timeperiod=5)': 10.2, 'MA(timeperiod=10)': 10.15, 'MA(timeperiod=20)': 10.1,
    'EMA(timeperiod=12)': 10.22, 'RSI(timeperiod=14)': 55,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.03, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.02, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.01,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 10.35, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 10.1, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 9.85,
    'ATR(timeperiod=14)': 0.12, 'OBV': 1500000 },
  { symbol: '300750', name: '宁德时代', industry: '新能源', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 1780, change: 1.25,
    'PE_TTM': 22.4, 'PB_TTM': 4.8, 'ROE_TTM': 18.6, 'TOTAL_MV': 9200, 'CLOSE': 212, 'VOLUME': 62000,
    'MA(timeperiod=5)': 210, 'MA(timeperiod=10)': 208, 'MA(timeperiod=20)': 205,
    'EMA(timeperiod=12)': 211, 'RSI(timeperiod=14)': 66,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 1.8, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 1.2, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.6,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 218, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 205, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 192,
    'ATR(timeperiod=14)': 4.2, 'OBV': 1800000 },
  { symbol: '002594', name: '比亚迪', industry: '汽车', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 4380, change: 0.56,
    'PE_TTM': 26.8, 'PB_TTM': 4.2, 'ROE_TTM': 21.3, 'TOTAL_MV': 7800, 'CLOSE': 270, 'VOLUME': 54000,
    'MA(timeperiod=5)': 268, 'MA(timeperiod=10)': 266, 'MA(timeperiod=20)': 265,
    'EMA(timeperiod=12)': 269, 'RSI(timeperiod=14)': 59,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.9, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.6, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.3,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 275, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 265, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 255,
    'ATR(timeperiod=14)': 3.5, 'OBV': 1600000 },
  { symbol: '000002', name: '万科A', industry: '房地产', is_st: false, is_suspended: false, is_limit_up: false, is_limit_down: true, list_days: 9120, change: -1.83,
    'PE_TTM': 7.5, 'PB_TTM': 0.35, 'ROE_TTM': 8.4, 'TOTAL_MV': 980, 'CLOSE': 8.05, 'VOLUME': 72000,
    'MA(timeperiod=5)': 8.1, 'MA(timeperiod=10)': 8.15, 'MA(timeperiod=20)': 8.2,
    'EMA(timeperiod=12)': 8.08, 'RSI(timeperiod=14)': 32,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': -0.1, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.0, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': -0.1,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 8.35, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 8.2, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 8.05,
    'ATR(timeperiod=14)': 0.18, 'OBV': 900000 },
  { symbol: '600519_X', name: '*ST 示例', industry: '综合', is_st: true, is_suspended: false, is_limit_up: false, is_limit_down: false, list_days: 1200, change: -2.36,
    'PE_TTM': NaN, 'PB_TTM': 0.8, 'ROE_TTM': -12.0, 'TOTAL_MV': 45, 'CLOSE': 2.48, 'VOLUME': 12000,
    'MA(timeperiod=5)': 2.5, 'MA(timeperiod=10)': 2.55, 'MA(timeperiod=20)': 2.6,
    'EMA(timeperiod=12)': 2.52, 'RSI(timeperiod=14)': 40,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': -0.2, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': -0.1, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': -0.1,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 2.7, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 2.6, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 2.5,
    'ATR(timeperiod=14)': 0.08, 'OBV': 300000 },
  { symbol: '300001', name: '特锐德', industry: '新能源', is_st: false, is_suspended: true, is_limit_up: false, is_limit_down: false, list_days: 3560, change: 0.00,
    'PE_TTM': 35.0, 'PB_TTM': 2.1, 'ROE_TTM': 9.2, 'TOTAL_MV': 180, 'CLOSE': 18.6, 'VOLUME': 21000,
    'MA(timeperiod=5)': 18.5, 'MA(timeperiod=10)': 18.3, 'MA(timeperiod=20)': 18.2,
    'EMA(timeperiod=12)': 18.55, 'RSI(timeperiod=14)': 50,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.1, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.05, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.05,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 19.0, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 18.2, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 17.4,
    'ATR(timeperiod=14)': 0.35, 'OBV': 700000 },
  { symbol: '688981', name: '中芯国际', industry: '半导体', is_st: false, is_suspended: false, is_limit_up: true, is_limit_down: false, list_days: 1450, change: 9.99,
    'PE_TTM': 120.5, 'PB_TTM': 3.5, 'ROE_TTM': 6.1, 'TOTAL_MV': 4300, 'CLOSE': 55, 'VOLUME': 150000,
    'MA(timeperiod=5)': 54, 'MA(timeperiod=10)': 53, 'MA(timeperiod=20)': 52,
    'EMA(timeperiod=12)': 54.2, 'RSI(timeperiod=14)': 78,
    'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[0]': 0.5, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[1]': 0.3, 'MACD(fastperiod=12,slowperiod=26,signalperiod=9)[2]': 0.2,
    'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[0]': 56, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[1]': 52, 'BOLL(timeperiod=20,nbdevup=2,nbdevdn=2)[2]': 48,
    'ATR(timeperiod=14)': 1.5, 'OBV': 2200000 },
];

const state = {
  mode: 'snapshot',
  activePlanId: 1,
  locked: false,
  plans: [
    {
      id: 1,
      name: '低估值优质',
      note: 'PE < 30 且 ROE > 10，按 ROE/PE/市值综合打分',
      mode: 'snapshot',
      filters: { excludeSt: true, excludeSuspended: true, excludeLimitUp: true, excludeLimitDown: false, industryAllow: '', industryBlock: '房地产,钢铁', minListDays: 180 },
      ranking: {
        method: 'composite',
        weights: [
          { factor: 'ROE_TTM', weight: 0.5 },
          { factor: 'PE_TTM', weight: -0.3 },
          { factor: 'TOTAL_MV', weight: 0.2 },
        ],
        singleFactor: 'TOTAL_MV',
        singleOrder: 'asc',
        topN: 30,
      },
      tree: {
        type: 'group',
        operator: 'AND',
        children: [
          { type: 'leaf', leftType: 'factor', leftKey: 'PE_TTM', leftParams: {}, leftOutputIndex: 0, comparator: '<', rightType: 'value', rightValue: 30, rightKey: null, rightParams: {}, rightOutputIndex: 0 },
          { type: 'leaf', leftType: 'factor', leftKey: 'ROE_TTM', leftParams: {}, leftOutputIndex: 0, comparator: '>', rightType: 'value', rightValue: 10, rightKey: null, rightParams: {}, rightOutputIndex: 0 },
        ],
      },
      lastRun: '2026-07-02 14:32',
    },
    {
      id: 2,
      name: '技术面反转',
      note: 'MA5 > MA20 且 RSI14 < 70',
      mode: 'range',
      filters: { excludeSt: true, excludeSuspended: true, excludeLimitUp: false, excludeLimitDown: true, industryAllow: '', industryBlock: '', minListDays: 120 },
      ranking: {
        method: 'single',
        weights: [{ factor: 'RSI', weight: 1 }],
        singleFactor: 'RSI',
        singleOrder: 'asc',
        topN: 20,
      },
      tree: {
        type: 'group',
        operator: 'AND',
        children: [
          {
            type: 'leaf', leftType: 'factor', leftKey: 'MA', leftParams: { timeperiod: 5 }, leftOutputIndex: 0,
            comparator: '>',
            rightType: 'factor', rightKey: 'MA', rightParams: { timeperiod: 20 }, rightOutputIndex: 0,
          },
          {
            type: 'leaf', leftType: 'factor', leftKey: 'RSI', leftParams: { timeperiod: 14 }, leftOutputIndex: 0,
            comparator: '<',
            rightType: 'value', rightValue: 70, rightKey: null, rightParams: {}, rightOutputIndex: 0,
          },
        ],
      },
      lastRun: '2026-07-01 09:15',
    },
  ],
  result: null,
};

/* ---------- 初始化 ---------- */
function init() {
  bindEvents();
  renderPlans();
  selectPlan(state.activePlanId);
}

function getActivePlan() {
  return state.plans.find(p => p.id === state.activePlanId);
}

function bindEvents() {
  document.getElementById('runBtn').addEventListener('click', () => runScreen(true));
  document.getElementById('runEditorBtn').addEventListener('click', () => runScreen(true));

  document.querySelectorAll('.mode-btn').forEach(btn => {
    btn.addEventListener('click', () => setMode(btn.dataset.mode));
  });

  document.getElementById('planList').addEventListener('click', e => {
    const card = e.target.closest('.plan-card');
    if (card) selectPlan(Number(card.dataset.id));
  });
  document.getElementById('newPlanBtn').addEventListener('click', createPlan);

  document.querySelectorAll('.editor-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.editor-tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById(`tab-${tab.dataset.tab}`).classList.add('active');
    });
  });

  const treeEl = document.getElementById('conditionTree');
  treeEl.addEventListener('click', e => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const path = btn.dataset.path.split(',').map(Number);
    const action = btn.dataset.action;
    if (action === 'toggleOperator') toggleOperator(path);
    else if (action === 'addLeaf') addLeaf(path);
    else if (action === 'addGroup') addGroup(path);
    else if (action === 'removeNode') removeNode(path);
  });
  treeEl.addEventListener('change', e => {
    const field = e.target.closest('[data-field]');
    if (!field) return;
    const path = field.dataset.path.split(',').map(Number);
    const side = field.dataset.side;
    updateLeaf(path, side, field.dataset.field, field.value, field.type === 'checkbox' ? field.checked : undefined);
  });

  document.getElementById('addLeafBtn').addEventListener('click', () => addLeaf([]));
  document.getElementById('addGroupBtn').addEventListener('click', () => addGroup([]));

  ['excludeSt', 'excludeSuspended', 'excludeLimitUp', 'excludeLimitDown'].forEach(id => {
    document.getElementById(id).addEventListener('change', e => {
      const plan = getActivePlan();
      if (plan) plan.filters[id] = e.target.checked;
    });
  });
  ['industryAllow', 'industryBlock'].forEach(id => {
    document.getElementById(id).addEventListener('change', e => {
      const plan = getActivePlan();
      if (plan) plan.filters[id] = e.target.value;
    });
  });
  document.getElementById('minListDays').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) plan.filters.minListDays = Number(e.target.value);
  });

  document.querySelectorAll('input[name="rankMethod"]').forEach(r => {
    r.addEventListener('change', () => {
      const plan = getActivePlan();
      if (plan) {
        plan.ranking.method = r.value;
        updateRankingUI();
      }
    });
  });
  document.getElementById('singleFactor').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) plan.ranking.singleFactor = e.target.value;
  });
  document.getElementById('singleOrder').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) plan.ranking.singleOrder = e.target.value;
  });
  document.getElementById('topN').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) plan.ranking.topN = Number(e.target.value);
  });
  document.getElementById('addWeightBtn').addEventListener('click', addWeight);
  document.getElementById('weightList').addEventListener('click', e => {
    const btn = e.target.closest('[data-action="removeWeight"]');
    if (btn) removeWeight(Number(btn.dataset.index));
  });
  document.getElementById('weightList').addEventListener('change', e => {
    const field = e.target.closest('[data-field]');
    if (!field) return;
    const plan = getActivePlan();
    if (!plan) return;
    const idx = Number(field.dataset.index);
    if (field.dataset.field === 'factor') plan.ranking.weights[idx].factor = field.value;
    if (field.dataset.field === 'weight') plan.ranking.weights[idx].weight = Number(field.value);
  });

  document.getElementById('planName').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) {
      plan.name = e.target.value || '未命名方案';
      renderPlans();
    }
  });
  document.getElementById('planNote').addEventListener('change', e => {
    const plan = getActivePlan();
    if (plan) plan.note = e.target.value;
  });

  document.getElementById('savePlanBtn').addEventListener('click', () => {
    alert('方案已保存（原型演示，未提交服务器）');
  });

  document.querySelectorAll('.result-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.result-tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.result-view').forEach(v => v.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById(`result-${tab.dataset.resultTab}`).classList.add('active');
    });
  });

  document.getElementById('result-tracking').addEventListener('click', e => {
    const btn = e.target.closest('#lockResultBtn');
    if (btn) lockResult();
  });
}

/* ---------- 模式与方案 ---------- */
function setMode(mode) {
  state.mode = mode;
  document.querySelectorAll('.mode-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.mode === mode);
  });
  const plan = getActivePlan();
  if (plan) plan.mode = mode;
  renderPlans();
}

function renderPlans() {
  const container = document.getElementById('planList');
  container.innerHTML = state.plans.map(plan => `
    <div class="plan-card ${plan.id === state.activePlanId ? 'active' : ''}" data-id="${plan.id}" role="option" aria-selected="${plan.id === state.activePlanId}">
      <p class="plan-card-name">${escapeHtml(plan.name)}</p>
      <div class="plan-card-meta">
        <span class="plan-card-tag ${plan.mode}">${plan.mode === 'snapshot' ? '快照' : '区间'}</span>
        <span>${plan.lastRun || '未运行'}</span>
      </div>
    </div>
  `).join('');
}

function selectPlan(id) {
  state.activePlanId = id;
  state.result = null;
  state.locked = false;
  renderPlans();
  const plan = getActivePlan();
  if (!plan) return;
  setMode(plan.mode);
  document.getElementById('planName').value = plan.name;
  document.getElementById('planNote').value = plan.note || '';
  renderConditionTree();
  renderFilters();
  renderRanking();
  resetResults();
}

function createPlan() {
  const id = Math.max(...state.plans.map(p => p.id), 0) + 1;
  const plan = {
    id,
    name: `新建方案 ${id}`,
    note: '',
    mode: state.mode,
    filters: { excludeSt: true, excludeSuspended: true, excludeLimitUp: true, excludeLimitDown: false, industryAllow: '', industryBlock: '', minListDays: 180 },
    ranking: { method: 'single', weights: [{ factor: 'PE_TTM', weight: 1 }], singleFactor: 'PE_TTM', singleOrder: 'asc', topN: 30 },
    tree: { type: 'group', operator: 'AND', children: [] },
    lastRun: '',
  };
  state.plans.push(plan);
  selectPlan(id);
}

/* ---------- 条件树 ---------- */
function renderConditionTree() {
  const plan = getActivePlan();
  const container = document.getElementById('conditionTree');
  if (!plan || !plan.tree.children.length) {
    container.innerHTML = `
      <div class="empty-tree">
        <p>当前没有任何条件</p>
        <p class="tree-hint">点击上方「添加条件」开始构建规则树</p>
      </div>`;
    return;
  }
  container.innerHTML = renderNode(plan.tree, []);
}

function renderNode(node, path) {
  if (node.type === 'group') {
    const childrenHtml = node.children.map((child, i) => renderNode(child, [...path, i])).join('');
    const removeHtml = path.length
      ? `<button class="sp-btn small danger" data-action="removeNode" data-path="${path.join(',')}" type="button"><i class="bi bi-trash3"></i> 删除分组</button>`
      : '';
    return `
      <div class="group-node" data-path="${path.join(',')}">
        <div class="group-header">
          <span class="operator-badge ${node.operator}" data-action="toggleOperator" data-path="${path.join(',')}" role="button" tabindex="0">${node.operator}</span>
          <div class="tree-actions">
            <button class="sp-btn small" data-action="addLeaf" data-path="${path.join(',')}" type="button"><i class="bi bi-plus-lg"></i> 条件</button>
            <button class="sp-btn small" data-action="addGroup" data-path="${path.join(',')}" type="button"><i class="bi bi-diagram-3"></i> 分组</button>
            ${removeHtml}
          </div>
        </div>
        <div class="children">${childrenHtml}</div>
      </div>`;
  }
  return renderLeaf(node, path);
}

function renderLeaf(leaf, path) {
  const pathStr = path.join(',');
  return `
    <div class="leaf-node" data-path="${pathStr}">
      ${renderExpression(leaf, 'left', pathStr)}
      <span class="comparator-badge"><select data-field="comparator" data-path="${pathStr}" data-side="left">${comparatorOptions(leaf.comparator)}</select></span>
      ${renderExpression(leaf, 'right', pathStr)}
      <button class="node-remove" data-action="removeNode" data-path="${pathStr}" type="button" aria-label="删除条件"><i class="bi bi-x-lg"></i></button>
    </div>`;
}

function renderExpression(leaf, side, pathStr) {
  const type = leaf[`${side}Type`];
  const key = leaf[`${side}Key`];
  const params = leaf[`${side}Params`] || {};
  const outputIndex = leaf[`${side}OutputIndex`] ?? 0;
  const def = getFactorDef(key);

  const factorSelect = type === 'factor'
    ? `<select class="factor-select" data-field="key" data-path="${pathStr}" data-side="${side}">${factorOptions(key)}</select>`
    : '';
  const valueInput = type === 'value'
    ? `<input type="number" class="value-input" data-field="value" data-path="${pathStr}" data-side="${side}" value="${leaf[`${side}Value`] ?? 0}" step="any">`
    : '';

  const paramsHtml = (type === 'factor' && def?.params?.length)
    ? `<div class="expr-params">${def.params.map(p => renderParamInput(p, params[p.name], pathStr, side)).join('')}</div>`
    : '';

  const outputHtml = (type === 'factor' && def?.multiOutput)
    ? `<div class="output-field"><label>输出</label><select data-field="output" data-path="${pathStr}" data-side="${side}">${outputOptions(def, outputIndex)}</select></div>`
    : '';

  return `
    <div class="expr-block">
      <div class="expr-head">
        <select class="type-select" data-field="type" data-path="${pathStr}" data-side="${side}">${exprTypeOptions(type)}</select>
        ${factorSelect}
        ${valueInput}
      </div>
      ${paramsHtml}
      ${outputHtml}
    </div>`;
}

function exprTypeOptions(selected) {
  const opts = [{ value: 'factor', label: '因子' }, { value: 'value', label: '数值' }];
  return opts.map(o => `<option value="${o.value}" ${o.value === selected ? 'selected' : ''}>${o.label}</option>`).join('');
}

function factorOptions(selected) {
  const grouped = {};
  FACTOR_REGISTRY.forEach(f => {
    const cat = f.category || 'OTHER';
    if (!grouped[cat]) grouped[cat] = [];
    grouped[cat].push(f);
  });
  const cats = CATEGORY_ORDER.filter(c => grouped[c]).concat(Object.keys(grouped).filter(c => !CATEGORY_ORDER.includes(c)));
  return cats.map(cat => {
    const items = grouped[cat].map(f => `<option value="${f.key}" ${f.key === selected ? 'selected' : ''}>${escapeHtml(f.key)} · ${escapeHtml(f.displayName)}</option>`).join('');
    return `<optgroup label="${escapeHtml(CATEGORY_NAMES[cat] || cat)}">${items}</optgroup>`;
  }).join('');
}

function renderParamInput(param, value, pathStr, side) {
  const val = value !== undefined ? value : param.defaultValue;
  const step = param.step ?? (param.type === 'INT' ? 1 : 0.01);
  return `
    <div class="param-field">
      <label>${escapeHtml(param.displayName || param.name)}</label>
      <input type="number" data-field="param-${param.name}" data-path="${pathStr}" data-side="${side}"
             value="${val}" min="${param.min}" max="${param.max}" step="${step}">
    </div>`;
}

function outputOptions(def, selected) {
  return (def.outputLabels || []).map((label, i) => `<option value="${i}" ${i === selected ? 'selected' : ''}>[${i}] ${escapeHtml(label)}</option>`).join('');
}

function comparatorOptions(selected) {
  return COMPARATORS.map(c => `<option value="${c}" ${c === selected ? 'selected' : ''}>${c}</option>`).join('');
}

function nodeAt(path) {
  const plan = getActivePlan();
  if (!plan) return null;
  let node = plan.tree;
  for (const idx of path) {
    node = node.children[idx];
  }
  return node;
}

function parentAt(path) {
  const plan = getActivePlan();
  if (!plan || path.length === 0) return null;
  let node = plan.tree;
  for (let i = 0; i < path.length - 1; i++) {
    node = node.children[path[i]];
  }
  return node;
}

function toggleOperator(path) {
  const node = nodeAt(path);
  if (node?.type === 'group') {
    node.operator = node.operator === 'AND' ? 'OR' : 'AND';
    renderConditionTree();
  }
}

function addLeaf(path) {
  const node = nodeAt(path);
  if (node?.type === 'group') {
    node.children.push({
      type: 'leaf', leftType: 'factor', leftKey: 'PE_TTM', leftParams: {}, leftOutputIndex: 0,
      comparator: '<', rightType: 'value', rightValue: 30, rightKey: null, rightParams: {}, rightOutputIndex: 0,
    });
    renderConditionTree();
  }
}

function addGroup(path) {
  const node = nodeAt(path);
  if (node?.type === 'group') {
    node.children.push({ type: 'group', operator: 'AND', children: [] });
    renderConditionTree();
  }
}

function removeNode(path) {
  if (path.length === 0) return;
  const parent = parentAt(path);
  const idx = path[path.length - 1];
  parent.children.splice(idx, 1);
  renderConditionTree();
}

function updateLeaf(path, side, field, value, checked) {
  const node = nodeAt(path);
  if (!node || node.type !== 'leaf') return;
  const typeKey = `${side}Type`;
  const keyKey = `${side}Key`;
  const paramsKey = `${side}Params`;
  const outputKey = `${side}OutputIndex`;
  const valueKey = `${side}Value`;

  if (field === 'type') {
    node[typeKey] = value;
    if (value === 'factor') {
      node[keyKey] = 'PE_TTM';
      node[paramsKey] = {};
      node[outputKey] = 0;
    } else {
      node[valueKey] = 0;
      node[keyKey] = null;
      node[paramsKey] = {};
      node[outputKey] = 0;
    }
  } else if (field === 'key') {
    const def = getFactorDef(value);
    node[keyKey] = value;
    node[paramsKey] = getDefaultParams(def);
    node[outputKey] = def?.defaultOutputIndex ?? 0;
  } else if (field.startsWith('param-')) {
    const paramName = field.slice(6);
    node[paramsKey][paramName] = Number(value);
  } else if (field === 'output') {
    node[outputKey] = Number(value);
  } else if (field === 'value') {
    node[valueKey] = value === '' ? 0 : Number(value);
  } else if (field === 'comparator') {
    node.comparator = value;
  }
  renderConditionTree();
}

/* ---------- 过滤与排序 ---------- */
function renderFilters() {
  const plan = getActivePlan();
  if (!plan) return;
  const f = plan.filters;
  document.getElementById('excludeSt').checked = f.excludeSt;
  document.getElementById('excludeSuspended').checked = f.excludeSuspended;
  document.getElementById('excludeLimitUp').checked = f.excludeLimitUp;
  document.getElementById('excludeLimitDown').checked = f.excludeLimitDown;
  document.getElementById('industryAllow').value = f.industryAllow;
  document.getElementById('industryBlock').value = f.industryBlock;
  document.getElementById('minListDays').value = f.minListDays;
}

function renderRanking() {
  const plan = getActivePlan();
  if (!plan) return;
  const r = plan.ranking;
  document.querySelector(`input[name="rankMethod"][value="${r.method}"]`).checked = true;
  populateSingleFactorSelect();
  document.getElementById('singleFactor').value = r.singleFactor;
  document.getElementById('singleOrder').value = r.singleOrder;
  document.getElementById('topN').value = r.topN;
  renderWeights();
  updateRankingUI();
}

function populateSingleFactorSelect() {
  document.getElementById('singleFactor').innerHTML = factorOptions('');
}

function renderWeights() {
  const plan = getActivePlan();
  const container = document.getElementById('weightList');
  if (!plan) return;
  container.innerHTML = plan.ranking.weights.map((w, i) => `
    <div class="weight-row">
      <select data-field="factor" data-index="${i}">${factorOptions(w.factor)}</select>
      <input type="number" data-field="weight" data-index="${i}" value="${w.weight}" step="0.1">
      <button class="weight-remove" data-action="removeWeight" data-index="${i}" type="button" aria-label="删除权重"><i class="bi bi-x-lg"></i></button>
    </div>
  `).join('');
}

function updateRankingUI() {
  const plan = getActivePlan();
  const isSingle = plan?.ranking.method === 'single';
  document.getElementById('singleRanking').classList.toggle('hidden', !isSingle);
  document.getElementById('compositeRanking').classList.toggle('hidden', isSingle);
}

function addWeight() {
  const plan = getActivePlan();
  if (!plan) return;
  plan.ranking.weights.push({ factor: 'PE_TTM', weight: 0 });
  renderWeights();
}

function removeWeight(index) {
  const plan = getActivePlan();
  if (!plan) return;
  plan.ranking.weights.splice(index, 1);
  if (plan.ranking.weights.length === 0) plan.ranking.weights.push({ factor: 'PE_TTM', weight: 0 });
  renderWeights();
}

/* ---------- 选股运行 ---------- */
function runScreen(animate) {
  const plan = getActivePlan();
  if (!plan) return;
  if (animate) {
    document.querySelectorAll('.seal-btn').forEach(btn => {
      btn.classList.remove('stamping');
      void btn.offsetWidth;
      btn.classList.add('stamping');
    });
  }
  plan.lastRun = new Date().toLocaleString('zh-CN', { hour12: false });
  renderPlans();

  const date = document.getElementById('screenDate').value;
  const universe = document.getElementById('universeSelect').value;

  const { stocks, excluded } = applyFilters(MOCK_STOCKS, plan.filters);
  const passed = stocks.filter(s => evaluateTree(plan.tree, s));
  const ranked = rankStocks(passed, plan.ranking);

  if (plan.mode === 'snapshot') {
    state.result = {
      mode: 'snapshot',
      date,
      universe,
      totalCount: MOCK_STOCKS.length,
      passedCount: passed.length,
      stocks: ranked.slice(0, plan.ranking.topN),
      excluded,
    };
  } else {
    state.result = buildRangeResult(ranked, plan);
    state.result.date = date;
    state.result.universe = universe;
    state.result.totalCount = MOCK_STOCKS.length;
    state.result.excluded = excluded;
  }

  state.locked = false;
  renderResults();
}

function applyFilters(stocks, filters) {
  const excluded = { st: [], suspended: [], limitUp: [], limitDown: [], industry: [], listDays: [] };
  const pass = stocks.filter(s => {
    if (filters.excludeSt && s.is_st) { excluded.st.push(s.symbol); return false; }
    if (filters.excludeSuspended && s.is_suspended) { excluded.suspended.push(s.symbol); return false; }
    if (filters.excludeLimitUp && s.is_limit_up) { excluded.limitUp.push(s.symbol); return false; }
    if (filters.excludeLimitDown && s.is_limit_down) { excluded.limitDown.push(s.symbol); return false; }
    if (filters.industryAllow) {
      const allowed = filters.industryAllow.split(/[,，]/).map(x => x.trim()).filter(Boolean);
      if (allowed.length && !allowed.includes(s.industry)) { excluded.industry.push(s.symbol); return false; }
    }
    if (filters.industryBlock) {
      const blocked = filters.industryBlock.split(/[,，]/).map(x => x.trim()).filter(Boolean);
      if (blocked.includes(s.industry)) { excluded.industry.push(s.symbol); return false; }
    }
    if (s.list_days < filters.minListDays) { excluded.listDays.push(s.symbol); return false; }
    return true;
  });
  return { stocks: pass, excluded };
}

function evaluateTree(node, stock) {
  if (node.type === 'group') {
    if (node.children.length === 0) return true;
    if (node.operator === 'AND') return node.children.every(c => evaluateTree(c, stock));
    return node.children.some(c => evaluateTree(c, stock));
  }
  return evaluateLeaf(node, stock);
}

function evaluateLeaf(leaf, stock) {
  const leftVal = leaf.leftType === 'value' ? leaf.leftValue : leaf.leftKey;
  const rightVal = leaf.rightType === 'value' ? leaf.rightValue : leaf.rightKey;
  const left = evalExpr(leaf.leftType, leftVal, leaf.leftParams, leaf.leftOutputIndex, stock);
  const right = evalExpr(leaf.rightType, rightVal, leaf.rightParams, leaf.rightOutputIndex, stock);
  if (Number.isNaN(left) || Number.isNaN(right)) return false;
  switch (leaf.comparator) {
    case '>': return left > right;
    case '<': return left < right;
    case '>=': return left >= right;
    case '<=': return left <= right;
    case '==': return left === right;
    case '!=': return left !== right;
    default: return false;
  }
}

function evalExpr(type, key, params, outputIndex, stock) {
  if (type === 'value') return Number(key);
  const sig = getFactorSignature(key, params || {}, outputIndex);
  return stock[sig] ?? NaN;
}

function rankStocks(stocks, ranking) {
  if (ranking.method === 'single') {
    const factor = ranking.singleFactor;
    const order = ranking.singleOrder === 'asc' ? 1 : -1;
    return stocks
      .map(s => ({ ...s, score: resolveFactorValue(s, factor) }))
      .sort((a, b) => {
        const na = Number.isNaN(a.score), nb = Number.isNaN(b.score);
        if (na && nb) return a.symbol.localeCompare(b.symbol);
        if (na) return 1;
        if (nb) return -1;
        const diff = (a.score - b.score) * order;
        return diff === 0 ? a.symbol.localeCompare(b.symbol) : diff;
      })
      .map((s, i) => ({ ...s, rank: i + 1 }));
  }
  const weights = ranking.weights.filter(w => w.weight !== 0);
  if (weights.length === 0) {
    return stocks.map((s, i) => ({ ...s, score: 0, rank: i + 1 }));
  }
  const means = {}, stds = {};
  weights.forEach(w => {
    const vals = stocks.map(s => resolveFactorValue(s, w.factor)).filter(v => !Number.isNaN(v));
    means[w.factor] = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0;
    const m = means[w.factor];
    stds[w.factor] = vals.length ? Math.sqrt(vals.reduce((sum, v) => sum + (v - m) ** 2, 0) / vals.length) || 1 : 1;
  });
  const scored = stocks.map(s => {
    let score = 0;
    weights.forEach(w => {
      const v = resolveFactorValue(s, w.factor);
      if (Number.isNaN(v)) return;
      const z = (v - means[w.factor]) / stds[w.factor];
      score += z * w.weight;
    });
    return { ...s, score };
  });
  scored.sort((a, b) => {
    if (Number.isNaN(a.score) && Number.isNaN(b.score)) return a.symbol.localeCompare(b.symbol);
    if (Number.isNaN(a.score)) return 1;
    if (Number.isNaN(b.score)) return -1;
    const diff = b.score - a.score;
    return diff === 0 ? a.symbol.localeCompare(b.symbol) : diff;
  });
  return scored.map((s, i) => ({ ...s, rank: i + 1 }));
}

function buildRangeResult(ranked, plan) {
  const dates = [];
  const d = new Date();
  for (let i = 19; i >= 0; i--) {
    const dd = new Date(d);
    dd.setDate(dd.getDate() - i);
    dates.push(dd.toISOString().slice(0, 10));
  }
  const symbols = ranked.slice(0, plan.ranking.topN).map(s => s.symbol);
  const stockMap = {};
  symbols.forEach(sym => {
    stockMap[sym] = { symbol: sym, firstHitDate: null, hitCount: 0, totalDays: dates.length, dailyHits: [], consecutiveMax: 0, currentStreak: 0 };
  });

  dates.forEach(date => {
    symbols.forEach(sym => {
      const stock = MOCK_STOCKS.find(s => s.symbol === sym);
      const baseHit = evaluateTree(plan.tree, stock);
      const noise = Math.random() > 0.85 ? !baseHit : baseHit;
      const hit = noise;
      const rec = stockMap[sym];
      rec.dailyHits.push({ date, hit });
      if (hit) {
        rec.hitCount++;
        if (!rec.firstHitDate) rec.firstHitDate = date;
        rec.currentStreak++;
        rec.consecutiveMax = Math.max(rec.consecutiveMax, rec.currentStreak);
      } else {
        rec.currentStreak = 0;
      }
    });
  });

  const list = Object.values(stockMap).map(r => ({
    ...r,
    hitRatio: (r.hitCount / r.totalDays).toFixed(2),
  })).sort((a, b) => b.hitCount - a.hitCount || a.symbol.localeCompare(b.symbol));

  return { mode: 'range', dates, symbols, stocks: list };
}

/* ---------- 结果渲染 ---------- */
function resetResults() {
  document.getElementById('resultEmpty').classList.remove('hidden');
  document.getElementById('resultContent').classList.add('hidden');
}

function renderResults() {
  if (!state.result) return;
  document.getElementById('resultEmpty').classList.add('hidden');
  document.getElementById('resultContent').classList.remove('hidden');
  renderSummary();
  if (state.result.mode === 'snapshot') {
    renderSnapshotStocks();
  } else {
    renderRangeStocks();
  }
  renderExcluded();
  renderTracking();
}

function renderSummary() {
  const r = state.result;
  const container = document.getElementById('resultSummary');
  if (r.mode === 'snapshot') {
    const excludedTotal = Object.values(r.excluded).flat().length;
    container.innerHTML = `
      <div class="summary-card"><div class="summary-value">${r.totalCount}</div><div class="summary-label">候选总数</div></div>
      <div class="summary-card"><div class="summary-value">${r.passedCount}</div><div class="summary-label">满足条件</div></div>
      <div class="summary-card"><div class="summary-value">${excludedTotal}</div><div class="summary-label">被过滤</div></div>
    `;
  } else {
    const anyHit = r.stocks.filter(s => s.hitCount > 0).length;
    container.innerHTML = `
      <div class="summary-card"><div class="summary-value">${r.totalCount}</div><div class="summary-label">候选总数</div></div>
      <div class="summary-card"><div class="summary-value">${anyHit}</div><div class="summary-label">区间曾命中</div></div>
      <div class="summary-card"><div class="summary-value">${r.stocks.length}</div><div class="summary-label">追踪股票数</div></div>
    `;
  }
}

function renderSnapshotStocks() {
  const stocks = state.result.stocks;
  const container = document.getElementById('result-stocks');
  if (!stocks.length) {
    container.innerHTML = '<p class="tree-hint">无命中股票</p>';
    return;
  }
  container.innerHTML = `
    <table class="data-table">
      <thead>
        <tr>
          <th>排名</th>
          <th>代码</th>
          <th>名称</th>
          <th class="num">得分</th>
          <th class="num">PE_TTM</th>
          <th class="num">ROE_TTM</th>
          <th class="num">总市值(亿)</th>
          <th class="num">涨跌</th>
        </tr>
      </thead>
      <tbody>
        ${stocks.map(s => `
          <tr>
            <td><span class="rank ${s.rank <= 3 ? 'top' : ''}">${s.rank}</span></td>
            <td class="symbol">${s.symbol}</td>
            <td class="name">${escapeHtml(s.name)}</td>
            <td class="num">${formatNum(s.score)}</td>
            <td class="num">${formatNum(s['PE_TTM'])}</td>
            <td class="num">${formatNum(s['ROE_TTM'])}</td>
            <td class="num">${formatNum(s['TOTAL_MV'])}</td>
            <td class="num ${s.change >= 0 ? 'rise' : 'fall'}">${s.change >= 0 ? '+' : ''}${s.change.toFixed(2)}%</td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

function renderRangeStocks() {
  const stocks = state.result.stocks;
  const container = document.getElementById('result-stocks');
  if (!stocks.length) {
    container.innerHTML = '<p class="tree-hint">无命中股票</p>';
    return;
  }
  container.innerHTML = stocks.map(s => {
    const dots = s.dailyHits.map(h => `<span class="hit-dot ${h.hit ? 'hit' : ''}" title="${h.date} ${h.hit ? '命中' : '未命中'}"></span>`).join('');
    return `
      <div class="range-card">
        <div class="range-header">
          <span class="range-symbol">${s.symbol}</span>
          <span class="range-stats">
            <span>首次命中 ${s.firstHitDate || '—'}</span>
            <span>命中 ${s.hitCount}/${s.totalDays}</span>
            <span>连续最大 ${s.consecutiveMax} 天</span>
          </span>
        </div>
        <div class="hit-bar">${dots}</div>
      </div>
    `;
  }).join('');
}

function renderExcluded() {
  const ex = state.result.excluded;
  const container = document.getElementById('result-excluded');
  const labels = { st: 'ST 股票', suspended: '停牌', limitUp: '涨停', limitDown: '跌停', industry: '行业过滤', listDays: '上市天数不足' };
  let html = '';
  Object.entries(labels).forEach(([key, label]) => {
    const list = ex[key] || [];
    if (list.length) {
      html += `
        <div class="range-card">
          <div class="range-header">
            <span class="range-symbol">${label}</span>
            <span class="range-stats">${list.length} 只</span>
          </div>
          <p class="tree-hint" style="margin:0;font-family:var(--font-mono)">${list.join(', ')}</p>
        </div>
      `;
    }
  });
  container.innerHTML = html || '<p class="tree-hint">没有股票被过滤</p>';
}

function renderTracking() {
  const container = document.getElementById('result-tracking');
  if (!state.locked) {
    container.innerHTML = `
      <div class="tracking-card">
        <p class="tree-hint">锁定本次选股结果后，watcher 将在 5 / 10 / 20 交易日收盘后计算组合收益。</p>
        <button id="lockResultBtn" class="sp-btn primary lock-btn" type="button"><i class="bi bi-lock-fill"></i> 锁定并追踪</button>
      </div>
    `;
    return;
  }
  const returns = [
    { days: '5 日', value: 2.35 },
    { days: '10 日', value: 4.12 },
    { days: '20 日', value: -0.87 },
  ];
  container.innerHTML = `
    <div class="tracking-card locked">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
        <span class="seal-mark"><i class="bi bi-stamp-fill"></i> 已锁定</span>
        <span class="tree-hint">${state.result.date || ''} · 等权组合</span>
      </div>
      <div class="tracking-returns">
        ${returns.map(r => `
          <div class="return-pill">
            <div class="days">${r.days}</div>
            <div class="value ${r.value >= 0 ? 'rise' : 'fall'}">${r.value >= 0 ? '+' : ''}${r.value.toFixed(2)}%</div>
          </div>
        `).join('')}
      </div>
      <p class="tree-hint" style="margin-top:10px">相对沪深 300 超额：+1.24%</p>
    </div>
  `;
}

function lockResult() {
  state.locked = true;
  renderTracking();
  document.querySelectorAll('.result-tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.result-view').forEach(v => v.classList.remove('active'));
  document.querySelector('[data-result-tab="tracking"]').classList.add('active');
  document.getElementById('result-tracking').classList.add('active');
}

/* ---------- 工具 ---------- */
function formatNum(n) {
  if (n === undefined || n === null || Number.isNaN(n)) return '—';
  if (Math.abs(n) >= 1000) return n.toLocaleString('zh-CN', { maximumFractionDigits: 0 });
  return Number(n).toFixed(2);
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

init();
