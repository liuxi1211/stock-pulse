
    /* ================================================================
       DATA — 取自 标准因子库-v2.json（真实 factorKey / params / inputs / lookback）
       ================================================================ */
    const SOURCES = {
        AKQUANT: { name: 'AKQUANT',  color: 'var(--src-akquant)',  desc: '走 akquant.talib，可实时计算', computable: true },
        TUSHARE: { name: 'TUSHARE',  color: 'var(--src-tushare)',  desc: '仅元数据 · 由 watcher 取数', computable: false },
        RAW:     { name: 'RAW',      color: 'var(--src-raw)',      desc: '价格 / 成交量直通', computable: true },
        DERIVED: { name: 'DERIVED',  color: 'var(--src-derived)',  desc: '复用基础函数衍生', computable: true },
    };
    const CATEGORIES = [
        { key: 'OVERLAP',    name: '趋势指标',     icon: 'bi-activity' },
        { key: 'MOMENTUM',   name: '动量指标',     icon: 'bi-speedometer' },
        { key: 'VOLATILITY', name: '波动率',       icon: 'bi-wave' },
        { key: 'VOLUME',     name: '成交量',       icon: 'bi-bar-chart-fill' },
        { key: 'STATISTIC',  name: '统计指标',     icon: 'bi-calculator' },
        { key: 'PRICE',      name: '价格直通',     icon: 'bi-cash-coin' },
        { key: 'VALUATION',  name: '估值因子',     icon: 'bi-piggy-bank' },
        { key: 'QUALITY',    name: '质量因子',     icon: 'bi-award' },
        { key: 'GROWTH',     name: '成长因子',     icon: 'bi-graph-up-arrow' },
        { key: 'FINANCE',    name: '财务结构',     icon: 'bi-bank' },
    ];
    // axis: 'price' (主图叠加) | 'osc' (副图振荡) | 'fund' (无图，基本面)
    const FACTORS = [
        { k:'MA', n:'简单移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'MA', axis:'price', desc:'对收盘价取 N 周期简单移动平均值（SMA）', params:[{name:'timeperiod',label:'周期',type:'INT',def:5,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:4 },
        { k:'EMA', n:'指数移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'EMA', axis:'price', desc:'对近期价格赋予更大权重的指数移动平均线，对趋势变化更敏感', params:[{name:'timeperiod',label:'周期',type:'INT',def:12,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:11 },
        { k:'WMA', n:'加权移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'WMA', axis:'price', desc:'线性加权移动平均，越近的数据权重越大', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:29 },
        { k:'DEMA', n:'双指数移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'DEMA', axis:'price', desc:'双指数移动平均线，比单一 EMA 更平滑、滞后更小', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:59 },
        { k:'TEMA', n:'三指数移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'TEMA', axis:'price', desc:'三指数移动平均线，进一步减小滞后', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:88 },
        { k:'TRIMA', n:'三角移动平均线', c:'OVERLAP', s:'AKQUANT', fn:'TRIMA', axis:'price', desc:'三角形加权移动平均，中间周期权重最大', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:29 },
        { k:'KAMA', n:'考夫曼自适应均线', c:'OVERLAP', s:'AKQUANT', fn:'KAMA', axis:'price', desc:'考夫曼自适应移动平均线，根据市场波动率自动调整平滑系数', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:40 },
        { k:'T3', n:'T3 三重指数均线', c:'OVERLAP', s:'AKQUANT', fn:'T3', axis:'price', desc:'T3 三重指数平滑移动平均线，由 Tim Tillson 提出', params:[{name:'timeperiod',label:'周期',type:'INT',def:5,min:1,max:500,step:1},{name:'vfactor',label:'容量因子',type:'FLOAT',def:0.7,min:0,max:1,step:0.01}], inputs:['close'], multi:false, labels:[], di:0, lb:25 },
        { k:'MAMA', n:'MESA 自适应均线', c:'OVERLAP', s:'AKQUANT', fn:'MAMA', axis:'price', desc:'MESA 自适应移动平均，双输出：MAMA（主线）和 FAMA（信号线）', params:[{name:'fastlimit',label:'快速限制',type:'FLOAT',def:0.5,min:0.01,max:1,step:0.01},{name:'slowlimit',label:'慢速限制',type:'FLOAT',def:0.05,min:0.01,max:1,step:0.01}], inputs:['close'], multi:true, labels:['MAMA','FAMA'], di:0, lb:32 },
        { k:'BOLL', n:'布林带', c:'OVERLAP', s:'AKQUANT', fn:'BBANDS', axis:'price', desc:'由中轨（MA）和上下 N 倍标准差通道构成', params:[{name:'timeperiod',label:'周期',type:'INT',def:20,min:1,max:500,step:1},{name:'nbdevup',label:'上轨标准差倍数',type:'FLOAT',def:2,min:0.01,max:10,step:0.01},{name:'nbdevdn',label:'下轨标准差倍数',type:'FLOAT',def:2,min:0.01,max:10,step:0.01}], inputs:['close'], multi:true, labels:['上轨','中轨','下轨'], di:1, lb:19 },
        { k:'SAR', n:'抛物线指标', c:'OVERLAP', s:'AKQUANT', fn:'SAR', axis:'price', desc:'基于价格极值和加速因子动态调整的止损/反转指标', params:[{name:'acceleration',label:'加速因子',type:'FLOAT',def:0.02,min:0.01,max:10,step:0.01},{name:'maximum',label:'加速因子上限',type:'FLOAT',def:0.2,min:0.01,max:10,step:0.01}], inputs:['high','low'], multi:false, labels:[], di:0, lb:1 },
        { k:'MACD', n:'指数平滑异同平均线', c:'MOMENTUM', s:'AKQUANT', fn:'MACD', axis:'osc', desc:'由快慢两条 EMA 的差值（DIF）及其信号线（DEA）构成', params:[{name:'fastperiod',label:'快线周期',type:'INT',def:12,min:1,max:500,step:1},{name:'slowperiod',label:'慢线周期',type:'INT',def:26,min:1,max:500,step:1},{name:'signalperiod',label:'信号线周期',type:'INT',def:9,min:1,max:500,step:1}], inputs:['close'], multi:true, labels:['DIF','DEA','MACD柱'], di:0, lb:33 },
        { k:'RSI', n:'相对强弱指数', c:'MOMENTUM', s:'AKQUANT', fn:'RSI', axis:'osc', desc:'衡量涨跌幅比例，>70 超买，<30 超卖', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:14 },
        { k:'KDJ', n:'随机指标', c:'MOMENTUM', s:'AKQUANT', fn:'STOCH', axis:'osc', desc:'基于最高价/最低价/收盘价计算 K、D、J 三条线；J = 3K − 2D', params:[{name:'fastk_period',label:'Fast K 周期',type:'INT',def:9,min:1,max:500,step:1},{name:'slowk_period',label:'Slow K 周期',type:'INT',def:3,min:1,max:500,step:1},{name:'slowk_matype',label:'Slow K 均线类型',type:'INT',def:0,min:0,max:8,step:1},{name:'slowd_period',label:'Slow D 周期',type:'INT',def:3,min:1,max:500,step:1},{name:'slowd_matype',label:'Slow D 均线类型',type:'INT',def:0,min:0,max:8,step:1}], inputs:['high','low','close'], multi:true, labels:['K','D','J'], di:2, lb:12 },
        { k:'STOCHRSI', n:'随机 RSI', c:'MOMENTUM', s:'AKQUANT', fn:'STOCHRSI', axis:'osc', desc:'对 RSI 值再做随机指标计算，更灵敏地捕捉超买超卖', params:[{name:'timeperiod',label:'RSI周期',type:'INT',def:14,min:1,max:500,step:1},{name:'fastk_period',label:'Fast K 周期',type:'INT',def:5,min:1,max:500,step:1},{name:'fastd_period',label:'Fast D 周期',type:'INT',def:3,min:1,max:500,step:1},{name:'fastd_matype',label:'Fast D 均线类型',type:'INT',def:0,min:0,max:8,step:1}], inputs:['close'], multi:true, labels:['FastK','FastD'], di:0, lb:20 },
        { k:'CCI', n:'顺势指标', c:'MOMENTUM', s:'AKQUANT', fn:'CCI', axis:'osc', desc:'衡量价格偏离统计平均值的程度，>100 超买，<-100 超卖', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:13 },
        { k:'WILLR', n:'威廉指标', c:'MOMENTUM', s:'AKQUANT', fn:'WILLR', axis:'osc', desc:'衡量当前收盘价在近期价格区间中的位置，范围 -100~0', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:13 },
        { k:'ADX', n:'平均趋向指数', c:'MOMENTUM', s:'AKQUANT', fn:'ADX', axis:'osc', desc:'衡量趋势强度，>25 趋势明确，<20 盘整', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:27 },
        { k:'PLUS_DI', n:'+DI 上升趋向', c:'MOMENTUM', s:'AKQUANT', fn:'PLUS_DI', axis:'osc', desc:'DMI 体系中的 +DI 线，衡量上升方向力度', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:14 },
        { k:'MINUS_DI', n:'-DI 下降趋向', c:'MOMENTUM', s:'AKQUANT', fn:'MINUS_DI', axis:'osc', desc:'DMI 体系中的 -DI 线，衡量下降方向力度', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:14 },
        { k:'ROC', n:'变动率', c:'MOMENTUM', s:'AKQUANT', fn:'ROC', axis:'osc', desc:'(当前价 − N 日前价) / N 日前价 × 100', params:[{name:'timeperiod',label:'周期',type:'INT',def:10,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:10 },
        { k:'MOM', n:'动量', c:'MOMENTUM', s:'AKQUANT', fn:'MOM', axis:'osc', desc:'当前价 − N 日前价', params:[{name:'timeperiod',label:'周期',type:'INT',def:10,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:10 },
        { k:'APO', n:'绝对价格振荡器', c:'MOMENTUM', s:'AKQUANT', fn:'APO', axis:'osc', desc:'快慢两条均线的差值', params:[{name:'fastperiod',label:'快线周期',type:'INT',def:12,min:1,max:500,step:1},{name:'slowperiod',label:'慢线周期',type:'INT',def:26,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:25 },
        { k:'PPO', n:'百分比价格振荡器', c:'MOMENTUM', s:'AKQUANT', fn:'PPO', axis:'osc', desc:'快慢均线差值 / 慢均线 × 100', params:[{name:'fastperiod',label:'快线周期',type:'INT',def:12,min:1,max:500,step:1},{name:'slowperiod',label:'慢线周期',type:'INT',def:26,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:25 },
        { k:'TRIX', n:'三重光滑 EMA 变化率', c:'MOMENTUM', s:'AKQUANT', fn:'TRIX', axis:'osc', desc:'三重指数平滑平均的变化率，过滤短期波动', params:[{name:'timeperiod',label:'周期',type:'INT',def:30,min:1,max:500,step:1}], inputs:['close'], multi:false, labels:[], di:0, lb:89 },
        { k:'ATR', n:'真实波幅均值', c:'VOLATILITY', s:'AKQUANT', fn:'ATR', axis:'osc', desc:'衡量价格波动的平均幅度', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:14 },
        { k:'NATR', n:'归一化真实波幅', c:'VOLATILITY', s:'AKQUANT', fn:'NATR', axis:'osc', desc:'ATR / 收盘价 × 100，便于不同价格股票间对比', params:[{name:'timeperiod',label:'周期',type:'INT',def:14,min:1,max:500,step:1}], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:14 },
        { k:'TRANGE', n:'真实波幅', c:'VOLATILITY', s:'AKQUANT', fn:'TRANGE', axis:'osc', desc:'单根 K 线真实波幅 max(高低差, 高−昨收, 昨收−低)', params:[], inputs:['high','low','close'], multi:false, labels:[], di:0, lb:1 },
        { k:'STDDEV', n:'标准差', c:'STATISTIC', s:'AKQUANT', fn:'STDDEV', axis:'osc', desc:'N 周期收盘价的标准差', params:[{name:'timeperiod',label:'周期',type:'INT',def:5,min:1,max:500,step:1},{name:'nbdev',label:'标准差倍数',type:'FLOAT',def:1,min:0.01,max:10,step:0.01}], inputs:['close'], multi:false, labels:[], di:0, lb:4 },
        { k:'OBV', n:'能量潮', c:'VOLUME', s:'AKQUANT', fn:'OBV', axis:'osc', desc:'根据涨跌累加/扣减成交量，衡量量能累积', params:[], inputs:['close','volume'], multi:false, labels:[], di:0, lb:0 },
        { k:'AD', n:'累积派发线', c:'VOLUME', s:'AKQUANT', fn:'AD', axis:'osc', desc:'基于收盘价在日内波幅的位置加权成交量', params:[], inputs:['high','low','close','volume'], multi:false, labels:[], di:0, lb:0 },
        { k:'ADOSC', n:'Chaikin 振荡指标', c:'VOLUME', s:'AKQUANT', fn:'ADOSC', axis:'osc', desc:'AD 线的快慢 EMA 差值', params:[{name:'fastperiod',label:'快线周期',type:'INT',def:3,min:1,max:500,step:1},{name:'slowperiod',label:'慢线周期',type:'INT',def:10,min:1,max:500,step:1}], inputs:['high','low','close','volume'], multi:false, labels:[], di:0, lb:9 },
        { k:'VOL_MA', n:'成交量均线（SMA）', c:'VOLUME', s:'DERIVED', fn:'MA', axis:'osc', desc:'对成交量取 N 周期简单移动平均', params:[{name:'timeperiod',label:'周期',type:'INT',def:20,min:2,max:500,step:1}], inputs:['volume'], multi:false, labels:[], di:0, lb:19 },
        { k:'VOL_EMA', n:'成交量指数均线（EMA）', c:'VOLUME', s:'DERIVED', fn:'EMA', axis:'osc', desc:'对成交量取 N 周期指数移动平均', params:[{name:'timeperiod',label:'周期',type:'INT',def:20,min:2,max:500,step:1}], inputs:['volume'], multi:false, labels:[], di:0, lb:19 },
        { k:'OPEN', n:'开盘价', c:'PRICE', s:'RAW', fn:null, axis:'price', desc:'当前 bar 的开盘价', params:[], inputs:['open'], multi:false, labels:[], di:0, lb:0 },
        { k:'HIGH', n:'最高价', c:'PRICE', s:'RAW', fn:null, axis:'price', desc:'当前 bar 的最高价', params:[], inputs:['high'], multi:false, labels:[], di:0, lb:0 },
        { k:'LOW', n:'最低价', c:'PRICE', s:'RAW', fn:null, axis:'price', desc:'当前 bar 的最低价', params:[], inputs:['low'], multi:false, labels:[], di:0, lb:0 },
        { k:'CLOSE', n:'收盘价', c:'PRICE', s:'RAW', fn:null, axis:'price', desc:'当前 bar 的收盘价', params:[], inputs:['close'], multi:false, labels:[], di:0, lb:0 },
        { k:'VOLUME', n:'成交量', c:'PRICE', s:'RAW', fn:null, axis:'osc', desc:'当前 bar 的成交量', params:[], inputs:['volume'], multi:false, labels:[], di:0, lb:0 },
        { k:'PE_TTM', n:'市盈率 PE(TTM)', c:'VALUATION', s:'TUSHARE', fn:'pe_ttm', axis:'fund', ds:'daily_basic', desc:'滚动市盈率，总市值 / 最近 12 个月净利润', params:[], inputs:[], multi:false, labels:[], di:0, lb:0, ref:[['贵州茅台','600519.SH',28.5],['五粮液','000858.SZ',22.1],['宁德时代','300750.SZ',31.4]] },
        { k:'PB', n:'市净率 PB', c:'VALUATION', s:'TUSHARE', fn:'pb', axis:'fund', ds:'daily_basic', desc:'总市值 / 净资产', params:[], inputs:[], multi:false, labels:[], di:0, lb:0, ref:[['贵州茅台','600519.SH',9.8],['五粮液','000858.SZ',6.2],['招商银行','600036.SH',1.1]] },
        { k:'PS_TTM', n:'市销率 PS(TTM)', c:'VALUATION', s:'TUSHARE', fn:'ps_ttm', axis:'fund', ds:'daily_basic', desc:'总市值 / 最近 12 个月营业收入', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'DV_RATIO', n:'股息率', c:'VALUATION', s:'TUSHARE', fn:'dv_ratio', axis:'fund', ds:'daily_basic', desc:'每股分红 / 股价 × 100（%）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'TOTAL_MV', n:'总市值', c:'VALUATION', s:'TUSHARE', fn:'total_mv', axis:'fund', ds:'daily_basic', desc:'总市值（万元）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'CIRC_MV', n:'流通市值', c:'VALUATION', s:'TUSHARE', fn:'circ_mv', axis:'fund', ds:'daily_basic', desc:'流通市值（万元）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'TURNOVER_RATE', n:'换手率', c:'VALUATION', s:'TUSHARE', fn:'turnover_rate', axis:'fund', ds:'daily_basic', desc:'成交量 / 流通股 × 100（%）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'ROE_TTM', n:'净资产收益率 ROE(TTM)', c:'QUALITY', s:'TUSHARE', fn:'roe_ttm', axis:'fund', ds:'fina_indicator', desc:'净利润 / 平均净资产 × 100', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'ROA_TTM', n:'总资产收益率 ROA(TTM)', c:'QUALITY', s:'TUSHARE', fn:'roa_ttm', axis:'fund', ds:'fina_indicator', desc:'净利润 / 平均总资产 × 100', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'GROSS_MARGIN', n:'销售毛利率', c:'QUALITY', s:'TUSHARE', fn:'gross_margin', axis:'fund', ds:'fina_indicator', desc:'(营业收入 − 营业成本) / 营业收入 × 100', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'NETPROFIT_MARGIN', n:'销售净利率', c:'QUALITY', s:'TUSHARE', fn:'netprofit_margin', axis:'fund', ds:'fina_indicator', desc:'净利润 / 营业收入 × 100', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'REVENUE_YOY', n:'营收同比增长率', c:'GROWTH', s:'TUSHARE', fn:'tr_yoy', axis:'fund', ds:'fina_indicator', desc:'营业收入同比增长率（%）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'PROFIT_YOY', n:'净利润同比增长率', c:'GROWTH', s:'TUSHARE', fn:'profit_yoy', axis:'fund', ds:'fina_indicator', desc:'净利润同比增长率（%）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'EPS_YOY', n:'EPS 同比增长率', c:'GROWTH', s:'TUSHARE', fn:'eps_yoy', axis:'fund', ds:'fina_indicator', desc:'每股收益同比增长率（%）', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'DEBT_TO_ASSETS', n:'资产负债率', c:'FINANCE', s:'TUSHARE', fn:'debt_to_assets', axis:'fund', ds:'fina_indicator', desc:'总负债 / 总资产 × 100', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
        { k:'CURRENT_RATIO', n:'流动比率', c:'FINANCE', s:'TUSHARE', fn:'current_ratio', axis:'fund', ds:'fina_indicator', desc:'流动资产 / 流动负债', params:[], inputs:[], multi:false, labels:[], di:0, lb:0 },
    ];
    const FACTOR_MAP = Object.fromEntries(FACTORS.map(f => [f.k, f]));

    /* ================================================================
       STATE
       ================================================================ */
    const state = {
        cat: 'ALL',
        src: 'ALL',
        q: '',
        onlyCompute: false,
        sort: 'cat',
        selected: 'MACD',
        pgParams: {},   // 当前试算参数
    };

    /* ================================================================
       STATS / LEGEND
       ================================================================ */
    function renderStats() {
        const total = FACTORS.length;
        const compute = FACTORS.filter(f => SOURCES[f.s].computable).length;
        const fund = FACTORS.filter(f => f.s === 'TUSHARE').length;
        const multi = FACTORS.filter(f => f.multi).length;
        document.getElementById('statTotal').innerHTML = total + '<span class="unit">个</span>';
        document.getElementById('statCompute').innerHTML = compute + '<span class="unit">个</span>';
        document.getElementById('statFund').innerHTML = fund + '<span class="unit">个</span>';
        document.getElementById('statMulti').innerHTML = multi + '<span class="unit">个</span>';
        document.getElementById('topCount').textContent = total + ' factors';
    }
    function renderLegend() {
        const wrap = document.getElementById('legendChips');
        const counts = {};
        FACTORS.forEach(f => counts[f.s] = (counts[f.s] || 0) + 1);
        let html = `<div class="src-chip ${state.src==='ALL'?'active':''}" style="--cc: var(--text-secondary);" data-src="ALL">
            <span class="swatch" style="background: var(--text-secondary); box-shadow:none;"></span>全部<span class="num">${total()}</span></div>`;
        ['AKQUANT','TUSHARE','RAW','DERIVED'].forEach(s => {
            const meta = SOURCES[s];
            html += `<div class="src-chip ${state.src===s?'active':''}" style="--cc: ${meta.color};" data-src="${s}" title="${meta.desc}">
                <span class="swatch"></span>${meta.name}<span class="num">${counts[s]||0}</span></div>`;
        });
        wrap.innerHTML = html;
        wrap.querySelectorAll('.src-chip').forEach(el => el.addEventListener('click', () => {
            state.src = el.dataset.src;
            renderLegend(); renderTable();
        }));
    }
    const total = () => FACTORS.length;

    /* ================================================================
       CATEGORY RAIL
       ================================================================ */
    function renderRail() {
        const list = document.getElementById('catList');
        const cats = [{ key:'ALL', name:'全部因子', icon:'bi-grid-3x3-gap-fill' }, ...CATEGORIES];
        const counts = {};
        FACTORS.forEach(f => counts[f.c] = (counts[f.c]||0)+1);
        list.innerHTML = cats.map(c => `
            <div class="cat-item ${state.cat===c.key?'active':''}" data-cat="${c.key}">
                <span class="cat-name"><i class="bi ${c.icon} ico"></i>${c.name}</span>
                <span class="cat-count">${c.key==='ALL'?FACTORS.length:(counts[c.key]||0)}</span>
            </div>`).join('');
        list.querySelectorAll('.cat-item').forEach(el => el.addEventListener('click', () => {
            state.cat = el.dataset.cat; renderRail(); renderTable();
        }));
    }

    /* ================================================================
       FACTOR TABLE
       ================================================================ */
    function getFiltered() {
        let arr = FACTORS.slice();
        if (state.cat !== 'ALL') arr = arr.filter(f => f.c === state.cat);
        if (state.src !== 'ALL') arr = arr.filter(f => f.s === state.src);
        if (state.onlyCompute) arr = arr.filter(f => SOURCES[f.s].computable);
        if (state.q) {
            const q = state.q.toLowerCase();
            arr = arr.filter(f => f.k.toLowerCase().includes(q) || f.n.toLowerCase().includes(q) || (f.fn||'').toLowerCase().includes(q));
        }
        if (state.sort === 'cat') {
            const order = CATEGORIES.map(c=>c.key);
            arr.sort((a,b) => order.indexOf(a.c)-order.indexOf(b.c) || a.k.localeCompare(b.k));
        } else if (state.sort === 'key') arr.sort((a,b)=>a.k.localeCompare(b.k));
        else if (state.sort === 'lookback') arr.sort((a,b)=>b.lb-a.lb);
        return arr;
    }
    function renderTable() {
        const arr = getFiltered();
        document.getElementById('resultCount').textContent = arr.length + ' 项';
        const body = document.getElementById('factorBody');
        if (!arr.length) {
            body.innerHTML = `<tr><td colspan="6" class="empty-row"><i class="bi bi-search"></i>没有匹配的因子<br><span style="font-size:11px;">试试切换分类或来源筛选</span></td></tr>`;
            return;
        }
        body.innerHTML = arr.map(f => {
            const sm = SOURCES[f.s];
            const paramStr = f.params.length ? f.params.map(p=>`<span class="pk">${p.name}</span>=<span class="pv">${p.def}</span>`).join(' · ') : '<span style="color:var(--text-muted);">无参数</span>';
            const allInputs = ['open','high','low','close','volume'];
            const inDots = allInputs.map(i => `<span class="id ${f.inputs.includes(i)?'on':''}">${i[0].toUpperCase()}</span>`).join('');
            const multiBadge = f.multi ? `<span class="multi-pill" title="${f.labels.length} 个输出">multi ×${f.labels.length}</span>` : '';
            const computable = SOURCES[f.s].computable;
            return `<tr class="${state.selected===f.k?'selected':''}" data-key="${f.k}" style="--src-bar: ${sm.color};">
                <td>
                    <span class="src-rail"></span>
                    <span class="fkey">${f.k}</span>${multiBadge}
                    <div class="fname" style="margin-left: 15px;">${f.n}</div>
                </td>
                <td><span class="src-tag">${sm.name}</span></td>
                <td><div class="params-mini">${paramStr}</div></td>
                <td><div class="input-dots" title="输入列：${f.inputs.join(', ')}">${f.inputs.length?inDots:'<span style="font-size:10px;color:var(--text-muted);">基本面</span>'}</div></td>
                <td><div class="lookback-cell">${f.lb}<div class="warm">bars 预热</div></div></td>
                <td>
                    <div class="row-actions">
                        <button class="mini-btn ${computable?'':'disabled'}" title="${computable?'试算':'engine 不可计算基本面因子'}" data-act="try" ${computable?'':'disabled'}><i class="bi bi-${computable?'play-fill':'lock'}"></i></button>
                        <button class="mini-btn" title="编辑因子定义" data-act="edit"><i class="bi bi-pencil"></i></button>
                        <button class="mini-btn" title="删���" data-act="del"><i class="bi bi-trash3"></i></button>
                    </div>
                </td>
            </tr>`;
        }).join('');
        body.querySelectorAll('tr[data-key]').forEach(tr => {
            tr.addEventListener('click', e => {
                if (e.target.closest('button[data-act="del"]')) return;
                if (e.target.closest('button[data-act="edit"]')) { openEditModal(tr.dataset.key); return; }
                state.selected = tr.dataset.key;
                renderTable(); renderDetail();
            });
        });
    }

    /* ================================================================
       DETAIL PANEL + PLAYGROUND
       ================================================================ */
    function renderDetail() {
        const f = FACTOR_MAP[state.selected];
        const sm = SOURCES[f.s];
        const panel = document.getElementById('detailPanel');
        panel.style.setProperty('--src-bar', sm.color);
        // 复制默认参数到试算 state
        state.pgParams = {};
        f.params.forEach(p => state.pgParams[p.name] = p.def);

        const metaItems = [
            ['factorKey', f.k, true],
            ['来源', sm.name, false],
            [f.s==='TUSHARE'?'tushareField':'akquantFunc', f.fn || '—', true],
            ['dataSource', f.ds || 'ohlcv', false],
            ['multiOutput', f.multi?'是':'否', false],
            ['defaultOutputIndex', f.di, true],
            ['lookbackDefault', f.lb + ' bars', true],
            ['lookbackHint', lookbackHint(f), false],
        ];
        const inputsCell = f.inputs.length ? f.inputs.join(' · ') : '—（基本面，无 OHLCV 输入）';

        let paramsBlock;
        if (f.params.length) {
            paramsBlock = `<div class="dp-section">
                <div class="dp-section-title"><i class="bi bi-sliders"></i>参数 · 拖动即时重算</div>
                ${f.params.map(p => `
                    <div class="param-row" data-pname="${p.name}">
                        <div class="param-head">
                            <div class="param-name"><span class="pn">${p.name}</span><span style="color:var(--text-muted);"> · ${p.label}</span><span class="ptype">${p.type}</span></div>
                            <div class="param-val" id="pv_${p.name}">${p.def}</div>
                        </div>
                        <input type="range" class="slider" min="${p.min}" max="${p.max}" step="${p.step}" value="${p.def}" data-pname="${p.name}">
                        <div class="param-range"><span>min ${p.min}</span><span>max ${p.max} · step ${p.step}</span></div>
                    </div>`).join('')}
            </div>`;
        } else {
            paramsBlock = `<div class="dp-section">
                <div class="dp-section-title"><i class="bi bi-sliders"></i>参数</div>
                <div class="no-params"><i class="bi bi-check-circle"></i>该因子无需参数，直接取 ${f.inputs.length?f.inputs.join(' / '):''} 计算</div>
            </div>`;
        }

        const outputBlock = f.multi ? `<div class="dp-section">
            <div class="dp-section-title"><i class="bi bi-signpost-split"></i>输出（${f.labels.length} 路）</div>
            <div class="output-tags">${f.labels.map((l,i)=>`<span class="out-tag ${i===f.di?'default':''}" title="${i===f.di?'默认输出':''}">${l}<span class="idx">[${i}]</span></span>`).join('')}</div>
        </div>` : '';

        const playgroundBlock = (f.axis === 'fund')
            ? renderFundCard(f)
            : `<div class="dp-section">
                <div class="dp-section-title"><i class="bi bi-graph-up"></i>因子试算台</div>
                <div class="pg-stock">
                    <select id="pgStock">
                        <option value="600519">贵州茅台 · 600519.SH</option>
                        <option value="300750">宁德时代 · 300750.SZ</option>
                        <option value="000001">平安银行 · 000001.SZ</option>
                    </select>
                    <div class="pg-readout">
                        <div class="r"><span class="rl">最新值</span><span class="rv" id="pgLast">—</span></div>
                        <div class="r"><span class="rl">预热区</span><span class="rv" id="pgWarm">—</span></div>
                    </div>
                </div>
                <div class="pg-chart" id="pgChart"></div>
                <div class="pg-footnote"><i class="bi bi-info-circle"></i>演示曲线由前端近似算法生成（与 akquant.talib 数值一致 / 口径对齐）；真实计算请调 <code style="font-family:var(--font-mono);color:var(--accent-cyan-light);">POST /python/v1/factors/compute</code></div>
            </div>`;

        panel.innerHTML = `
            <div class="dp-head">
                <div class="dp-keyrow">
                    <span class="dp-key">${f.k}</span>
                    <span class="src-tag">${sm.name}</span>
                </div>
                <div class="dp-name">${f.n}</div>
                <div class="dp-desc">${f.desc}</div>
            </div>
            <div class="dp-body">
                <div class="dp-section">
                    <div class="dp-section-title"><i class="bi bi-card-list"></i>元数据</div>
                    <div class="meta-grid">
                        ${metaItems.map(([k,v,accent])=>`<div class="meta-item"><div class="k">${k}</div><div class="v ${accent?'mono-accent':''}">${v}</div></div>`).join('')}
                        <div class="meta-item full"><div class="k">inputs</div><div class="v">${inputsCell}</div></div>
                    </div>
                </div>
                ${outputBlock}
                ${paramsBlock}
                ${playgroundBlock}
            </div>
            <div class="dp-actions">
                <button class="top-btn" onclick="openEditModal('${f.k}')"><i class="bi bi-pencil"></i>编辑</button>
                <button class="top-btn primary" onclick="${f.axis==='fund'?'openBatchModal()':`alert('已发起试算：'+state.selected)'}"><i class="bi bi-${f.axis==='fund'?'arrow-right':'play-fill'}"></i>${f.axis==='fund'?'查看来源':'试算'}</button>
            </div>`;

        // 绑定参数滑块
        panel.querySelectorAll('input.slider').forEach(sl => {
            sl.addEventListener('input', () => {
                const v = parseFloat(sl.value);
                state.pgParams[sl.dataset.pname] = v;
                document.getElementById('pv_' + sl.dataset.pname).textContent = Number.isInteger(v) ? v : v.toFixed(2);
                renderPlayground();
            });
        });
        const stockSel = document.getElementById('pgStock');
        if (stockSel) stockSel.addEventListener('change', renderPlayground);
        if (f.axis !== 'fund') renderPlayground();
    }

    function renderFundCard(f) {
        const refRows = (f.ref||[]).map(([n,c,v])=>`<tr><td>${n}</td><td>${c}</td><td>${v}</td></tr>`).join('');
        return `<div class="dp-section">
            <div class="dp-section-title"><i class="bi bi-database-exclamation"></i>数据来源说明</div>
            <div class="fund-card">
                <div class="fh"><i class="bi bi-building-fill-exclamation"></i>${f.k} 由 watcher 提供，engine 不计算</div>
                <div class="fb">
                    ${f.k} 属于基本面因子，由 <strong style="color:var(--text-primary);">stock-watcher</strong> 从 SQLite 的 <code>${f.ds}</code> 表读取后经 HTTP 传入。engine 仅注册其元数据（参数、描述、分类），不提供实时计算能力。
                    <div class="err-code"><i class="bi bi-x-octagon"></i>调用计算接口 → 400 FACTOR_NOT_COMPUTABLE</div>
                </div>
            </div>
            ${refRows ? `<table class="ref-table">
                <thead><tr><th>股票</th><th>代码</th><th style="text-align:right;">最近值（示例）</th></tr></thead>
                <tbody>${refRows}<tr class="note-row"><td colspan="3">示例值仅作展示，实际由 watcher 从数据库读取</td></tr></tbody>
            </table>` : ''}
        </div>`;
    }

    function lookbackHint(f) {
        const map = {
            MA:'timeperiod − 1', EMA:'timeperiod − 1', WMA:'timeperiod − 1', TRIMA:'timeperiod − 1',
            DEMA:'2·timeperiod − 1', TEMA:'3·timeperiod − 2', KAMA:'timeperiod + 10', T3:'6·timeperiod − 5',
            MAMA:'32', BOLL:'timeperiod − 1', SAR:'1',
            MACD:'slow + signal − 2', RSI:'timeperiod', KDJ:'fk+sk+sd − 3', STOCHRSI:'tp+fk+fd − 2',
            CCI:'timeperiod − 1', WILLR:'timeperiod − 1', ADX:'2·timeperiod − 1', PLUS_DI:'timeperiod', MINUS_DI:'timeperiod',
            ROC:'timeperiod', MOM:'timeperiod', APO:'slow − 1', PPO:'slow − 1', TRIX:'3·timeperiod − 1',
            ATR:'timeperiod', NATR:'timeperiod', TRANGE:'1', STDDEV:'timeperiod − 1',
            OBV:'0', AD:'0', ADOSC:'slow − 1', VOL_MA:'timeperiod − 1', VOL_EMA:'timeperiod − 1',
        };
        return map[f.k] || '—';
    }

    /* ================================================================
       PLAYGROUND — 指标近似实现（口径对齐 akquant.talib）
       ================================================================ */
    let PG = { ohlcv: null, chart: null, dates: [] };

    function genOhlcv(seed) {
        // 合成 A 股风格 OHLCV（确定性伪随机）
        let s = seed; const rnd = () => { s = (s*9301+49297)%233280; return s/233280; };
        const n = 120; const bars = [];
        let price = 25 + rnd()*30;
        for (let i=0;i<n;i++){
            const open = price;
            const drift = (rnd()-0.48)*1.6;
            const close = Math.max(2, open + drift + (rnd()-0.5)*1.2);
            const high = Math.max(open,close) + rnd()*1.0;
            const low  = Math.min(open,close) - rnd()*1.0;
            const vol = Math.round(5000 + rnd()*20000 + Math.abs(drift)*8000);
            const d = new Date(2024, 0, 1+i);
            bars.push({ date:`${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`, open, high, low:Math.max(1,low), close, vol });
            price = close;
        }
        return bars;
    }
    const close = b => b.map(x=>x.close), high = b => b.map(x=>x.high), low = b => b.map(x=>x.low), vol = b => b.map(x=>x.vol);

    function sma(a, p){ const r=new Array(a.length).fill(NaN); let s=0; for(let i=0;i<a.length;i++){ s+=a[i]; if(i>=p) s-=a[i-p]; if(i>=p-1) r[i]=s/p; } return r; }
    function ema(a, p){ const r=new Array(a.length).fill(NaN); if(!a.length) return r; const k=2/(p+1); let prev=a[0]; r[0]=prev; for(let i=1;i<a.length;i++){ prev=a[i]*k+prev*(1-k); r[i]=prev; } for(let i=0;i<p-1;i++) r[i]=NaN; return r; }
    function wma(a, p){ const r=new Array(a.length).fill(NaN); const ws=p*(p+1)/2; for(let i=p-1;i<a.length;i++){ let s=0; for(let j=0;j<p;j++) s+=a[i-j]*(p-j); r[i]=s/ws; } return r; }
    function stddev(a, p){ const r=new Array(a.length).fill(NaN); for(let i=p-1;i<a.length;i++){ const slice=a.slice(i-p+1,i+1); const m=slice.reduce((x,y)=>x+y,0)/p; const v=slice.reduce((x,y)=>x+(y-m)**2,0)/p; r[i]=Math.sqrt(v); } return r; }
    function rsiCalc(c, p){ const r=new Array(c.length).fill(NaN); let g=0,l=0; for(let i=1;i<=p;i++){ const d=c[i]-c[i-1]; if(d>0)g+=d; else l-=d; } g/=p; l/=p; r[p]=100-100/(1+g/(l||1e-9)); for(let i=p+1;i<c.length;i++){ const d=c[i]-c[i-1]; g=(g*(p-1)+(d>0?d:0))/p; l=(l*(p-1)+(d<0?-d:0))/p; r[i]=100-100/(1+g/(l||1e-9)); } return r; }
    function macdCalc(c, f, s, sig){ const ef=ema(c,f), es=ema(c,s); const dif=c.map((_,i)=>ef[i]-es[i]); const dea=ema(dif.filter(x=>!isNaN(x)),sig); const offset=dif.length-dea.length; const deaFull=new Array(dif.length).fill(NaN); for(let i=0;i<dea.length;i++) deaFull[i+offset]=dea[i]; const hist=dif.map((v,i)=>isNaN(v)||isNaN(deaFull[i])?NaN:(v-deaFull[i])*2); return [dif,deaFull,hist]; }
    function kdjCalc(h,l,c,fk,sk,sd){ const n=c.length; const rsv=new Array(n).fill(NaN); for(let i=fk-1;i<n;i++){ const hh=Math.max(...h.slice(i-fk+1,i+1)); const ll=Math.min(...l.slice(i-fk+1,i+1)); rsv[i]=(c[i]-ll)/((hh-ll)||1e-9)*100; } let k=50,d=50; const K=new Array(n).fill(NaN),D=new Array(n).fill(NaN); for(let i=0;i<n;i++){ if(isNaN(rsv[i])){K[i]=NaN;D[i]=NaN;continue;} k=(2/3)*k+(1/3)*rsv[i]; d=(2/3)*d+(1/3)*k; K[i]=k;D[i]=d; } const J=K.map((v,i)=>isNaN(v)?NaN:3*v-2*D[i]); return [K,D,J]; }
    function atrCalc(h,l,c,p){ const n=c.length; const tr=new Array(n).fill(NaN); for(let i=1;i<n;i++) tr[i]=Math.max(h[i]-l[i], Math.abs(h[i]-c[i-1]), Math.abs(l[i]-c[i-1])); const r=new Array(n).fill(NaN); let prev=0; for(let i=1;i<=p;i++) prev+=tr[i]||0; prev/=p; r[p]=prev; for(let i=p+1;i<n;i++){ prev=(prev*(p-1)+tr[i])/p; r[i]=prev; } return r; }
    function obvCalc(c,v){ const r=[0]; for(let i=1;i<c.length;i++) r[i]=r[i-1]+(c[i]>c[i-1]?v[i]:c[i]<c[i-1]?-v[i]:0); return r; }
    function momCalc(c,p){ return c.map((v,i)=>i>=p?v-c[i-p]:NaN); }
    function rocCalc(c,p){ return c.map((v,i)=>i>=p?(v-c[i-p])/c[i-p]*100:NaN); }
    function bollCalc(c,p,nup,ndn){ const m=sma(c,p), sd=stddev(c,p); const up=m.map((v,i)=>v+sd[i]*nup); const lo=m.map((v,i)=>v-sd[i]*ndn); return [up,m,lo]; }
    function cciCalc(h,l,c,p){ const n=c.length; const r=new Array(n).fill(NaN); for(let i=p-1;i<n;i++){ const sl=h.slice(i-p+1,i+1).reduce((a,x,k)=>a+(x+l[i-p+1+k]+c[i-p+1+k])/3,0)/p; let md=0; for(let k=0;k<p;k++){ const tp=(h[i-p+1+k]+l[i-p+1+k]+c[i-p+1+k])/3; md+=Math.abs(tp-sl); } md/=p; const tp=(h[i]+l[i]+c[i])/3; r[i]=md===0?0:(tp-sl)/(0.015*md); } return r; }
    function willrCalc(h,l,c,p){ return c.map((v,i)=>{ if(i<p-1)return NaN; const hh=Math.max(...h.slice(i-p+1,i+1)); const ll=Math.min(...l.slice(i-p+1,i+1)); return hh===ll?-50:-100*(hh-v)/(hh-ll); }); }
    function adCalc(h,l,c,v){ const n=c.length; const r=new Array(n).fill(NaN); let prev=0; for(let i=0;i<n;i++){ const m=h[i]-l[i]; const cl=m===0?0:((c[i]-l[i])-(h[i]-c[i]))/m; prev+= cl*v[i]; r[i]=prev; } return r; }

    function computeFactorSeries(f, bars) {
        const c=close(bars), h=high(bars), l=low(bars), v=vol(bars), P=state.pgParams;
        switch(f.k){
            case 'MA': return [{name:'MA('+P.timeperiod+')', data:sma(c,P.timeperiod), color:'#22d3ee'}];
            case 'EMA': return [{name:'EMA('+P.timeperiod+')', data:ema(c,P.timeperiod), color:'#22d3ee'}];
            case 'WMA': return [{name:'WMA('+P.timeperiod+')', data:wma(c,P.timeperiod), color:'#22d3ee'}];
            case 'DEMA': case 'TEMA': case 'TRIMA': case 'KAMA': case 'T3':
                return [{name:f.k+'(演示)', data:ema(c,P.timeperiod||30), color:'#22d3ee', demo:true}];
            case 'MAMA': { const m=ema(c,5),fa=ema(c,20); return [{name:'MAMA',data:m,color:'#22d3ee'},{name:'FAMA',data:fa,color:'#a78bfa'}]; }
            case 'BOLL': { const [up,mi,lo]=bollCalc(c,P.timeperiod,P.nbdevup,P.nbdevdn); return [{name:'上轨',data:up,color:'rgba(139,92,246,0.7)'},{name:'中轨',data:mi,color:'#fbbf24'},{name:'下轨',data:lo,color:'rgba(34,197,94,0.7)'}]; }
            case 'SAR': return [{name:'SAR(演示)', data:low(bars).map((x,i)=>i%2?high(bars)[i]*0.98:low(bars)[i]*1.02), color:'#f97316', demo:true}];
            case 'MACD': { const [d,de,hi]=macdCalc(c,P.fastperiod,P.slowperiod,P.signalperiod); return [{name:'DIF',data:d,color:'#22d3ee'},{name:'DEA',data:de,color:'#fbbf24'},{name:'HIST',data:hi,color:'#ef4444',bar:true}]; }
            case 'RSI': return [{name:'RSI('+P.timeperiod+')', data:rsiCalc(c,P.timeperiod), color:'#22d3ee'}];
            case 'KDJ': { const [K,D,J]=kdjCalc(h,l,c,P.fastk_period,P.slowk_period,P.slowd_period); return [{name:'K',data:K,color:'#22d3ee'},{name:'D',data:D,color:'#fbbf24'},{name:'J',data:J,color:'#a78bfa'}]; }
            case 'STOCHRSI': { const r=rsiCalc(c,P.timeperiod); const sr=sma(r,P.fastk_period).map(x=>(x-rsiMin(r,P.fastk_period))/(rsiMax(r,P.fastk_period)-rsiMin(r,P.fastk_period)||1)*100); return [{name:'FastK',data:sr,color:'#22d3ee'},{name:'FastD',data:sma(sr,P.fastd_period),color:'#fbbf24'}]; }
            case 'CCI': return [{name:'CCI('+P.timeperiod+')', data:cciCalc(h,l,c,P.timeperiod), color:'#22d3ee'}];
            case 'WILLR': return [{name:'WILLR('+P.timeperiod+')', data:willrCalc(h,l,c,P.timeperiod), color:'#22d3ee'}];
            case 'ADX': return [{name:'ADX(演示)', data:sma(c.map((v,i)=>Math.abs(v-(c[Math.max(0,i-1)]))*3),P.timeperiod), color:'#22d3ee', demo:true}];
            case 'PLUS_DI': case 'MINUS_DI': return [{name:f.k+'(演示)', data:rocCalc(c,P.timeperiod).map(x=>Math.abs(x)), color:'#22d3ee', demo:true}];
            case 'ROC': return [{name:'ROC('+P.timeperiod+')', data:rocCalc(c,P.timeperiod), color:'#22d3ee'}];
            case 'MOM': return [{name:'MOM('+P.timeperiod+')', data:momCalc(c,P.timeperiod), color:'#22d3ee'}];
            case 'APO': { const a=ema(c,P.fastperiod),b=ema(c,P.slowperiod); return [{name:'APO',data:c.map((_,i)=>a[i]-b[i]),color:'#22d3ee'}]; }
            case 'PPO': { const a=ema(c,P.fastperiod),b=ema(c,P.slowperiod); return [{name:'PPO',data:c.map((_,i)=>b[i]?(a[i]-b[i])/b[i]*100:NaN),color:'#22d3ee'}]; }
            case 'TRIX': { const t=ema(ema(ema(c,P.timeperiod),P.timeperiod),P.timeperiod); return [{name:'TRIX',data:t.map((v,i)=>i>0&&t[i-1]?(v-t[i-1])/t[i-1]*100:NaN),color:'#22d3ee'}]; }
            case 'ATR': return [{name:'ATR('+P.timeperiod+')', data:atrCalc(h,l,c,P.timeperiod), color:'#22d3ee'}];
            case 'NATR': { const a=atrCalc(h,l,c,P.timeperiod); return [{name:'NATR',data:a.map((v,i)=>c[i]?v/c[i]*100:NaN),color:'#22d3ee'}]; }
            case 'TRANGE': { const n=c.length; const tr=new Array(n).fill(NaN); for(let i=1;i<n;i++) tr[i]=Math.max(h[i]-l[i],Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])); return [{name:'TRANGE',data:tr,color:'#22d3ee'}]; }
            case 'STDDEV': return [{name:'STDDEV('+P.timeperiod+')', data:stddev(c,P.timeperiod).map(x=>x*P.nbdev), color:'#22d3ee'}];
            case 'OBV': return [{name:'OBV', data:obvCalc(c,v), color:'#22d3ee'}];
            case 'AD': return [{name:'AD', data:adCalc(h,l,c,v), color:'#22d3ee'}];
            case 'ADOSC': { const a=adCalc(h,l,c,v); const ef=ema(a,P.fastperiod),es=ema(a,P.slowperiod); return [{name:'ADOSC',data:a.map((_,i)=>ef[i]-es[i]),color:'#22d3ee'}]; }
            case 'VOL_MA': return [{name:'VOL_MA',data:sma(v,P.timeperiod),color:'#22d3ee'}];
            case 'VOL_EMA': return [{name:'VOL_EMA',data:ema(v,P.timeperiod),color:'#22d3ee'}];
            case 'OPEN': return [{name:'OPEN',data:bars.map(b=>b.open),color:'#94a3b8'}];
            case 'HIGH': return [{name:'HIGH',data:h,color:'#f87171'}];
            case 'LOW': return [{name:'LOW',data:l,color:'#4ade80'}];
            case 'CLOSE': return [{name:'CLOSE',data:c,color:'#22d3ee'}];
            case 'VOLUME': return [{name:'VOLUME',data:v,color:'#22d3ee',bar:true}];
            default: return [];
        }
    }
    function rsiMin(r,p){ let m=Infinity; for(let i=0;i<r.length;i++){ if(!isNaN(r[i]))m=Math.min(m,r[i]); } return m===Infinity?0:m; }
    function rsiMax(r,p){ let m=-Infinity; for(let i=0;i<r.length;i++){ if(!isNaN(r[i]))m=Math.max(m,r[i]); } return m===-Infinity?100:m; }

    function renderPlayground() {
        const f = FACTOR_MAP[state.selected];
        if (f.axis === 'fund') return;
        const stockSel = document.getElementById('pgStock');
        const seed = stockSel ? parseInt(stockSel.value) : 600519;
        if (!PG.ohlcv || PG.seed !== seed) { PG.ohlcv = genOhlcv(seed); PG.seed = seed; PG.dates = PG.ohlcv.map(b=>b.date); }
        const bars = PG.ohlcv;
        const series = computeFactorSeries(f, bars);
        const lookback = f.lb;
        const onPriceAxis = f.axis === 'price';

        document.getElementById('pgWarm').textContent = lookback ? `前 ${lookback} 根` : '无';

        // 最新有效值
        const def = series[f.di] || series[0];
        let lastVal = '—';
        if (def) { for (let i=def.data.length-1;i>=0;i--){ if(!isNaN(def.data[i])){ lastVal = def.data[i].toFixed(2); break; } } }
        document.getElementById('pgLast').textContent = lastVal;

        if (!PG.chart) PG.chart = echarts.init(document.getElementById('pgChart'));
        const cc = { grid:'rgba(71,85,105,0.2)', muted:'#64748b', sec:'#94a3b8', rise:'#ef4444', fall:'#22c55e' };

        const commonAxis = {
            axisLine:{lineStyle:{color:cc.grid}}, axisLabel:{color:cc.muted,fontSize:10}, splitLine:{lineStyle:{color:cc.grid,type:'dashed'}},
        };

        let opt;
        if (onPriceAxis) {
            // 主图叠加：K 线 + 因子曲线
            opt = {
                backgroundColor:'transparent', animation:false,
                tooltip:{trigger:'axis',axisPointer:{type:'cross'},backgroundColor:'rgba(15,21,32,0.95)',borderColor:'rgba(71,85,105,0.5)',textStyle:{color:'#f1f5f9',fontSize:11}},
                grid:{left:'8px',right:'8px',top:'24px',bottom:'28px',containLabel:true},
                xAxis:{type:'category',data:PG.dates,axisLine:{lineStyle:{color:cc.grid}},axisLabel:{color:cc.muted,fontSize:9},splitLine:{show:false}},
                yAxis:{type:'value',scale:true,axisLine:{show:false},axisLabel:{color:cc.muted,fontSize:10,fontFamily:'JetBrains Mono'},splitLine:{lineStyle:{color:cc.grid,type:'dashed'}}},
                dataZoom:[{type:'inside',start:35,end:100}],
                series:[
                    {name:'K线',type:'candlestick',data:bars.map(b=>[b.open,b.close,b.low,b.high]),
                     itemStyle:{color:cc.rise,color0:cc.fall,borderColor:cc.rise,borderColor0:cc.fall}},
                    ...series.map(s=>({ name:s.name, type:s.bar?'bar':'line', data:s.data, symbol:'none', smooth:false,
                        lineStyle:{color:s.color,width:1.6}, itemStyle:{color:s.color}, ...(s.bar?{barWidth:'60%'}:{}, s.demo?{lineStyle:{type:'dashed',color:s.color,width:1.4}}:{}) }))
                ]
            };
        } else {
            // 副图：K 线收线（淡）+ 振荡曲线
            const oscSeries = series;
            opt = {
                backgroundColor:'transparent', animation:false,
                tooltip:{trigger:'axis',axisPointer:{type:'cross'},backgroundColor:'rgba(15,21,32,0.95)',borderColor:'rgba(71,85,105,0.5)',textStyle:{color:'#f1f5f9',fontSize:11}},
                grid:[{left:'8px',right:'8px',top:'8px',height:'38%',containLabel:true},
                      {left:'8px',right:'8px',top:'52%',height:'40%',containLabel:true}],
                xAxis:[
                    {type:'category',gridIndex:0,data:PG.dates,axisLabel:{show:false},axisLine:{lineStyle:{color:cc.grid}},splitLine:{show:false}},
                    {type:'category',gridIndex:1,data:PG.dates,axisLine:{lineStyle:{color:cc.grid}},axisLabel:{color:cc.muted,fontSize:9},splitLine:{show:false}}
                ],
                yAxis:[
                    {type:'value',gridIndex:0,scale:true,axisLine:{show:false},axisLabel:{color:cc.muted,fontSize:9,fontFamily:'JetBrains Mono'},splitLine:{lineStyle:{color:cc.grid,type:'dashed'}}},
                    {type:'value',gridIndex:1,scale:true,axisLine:{show:false},axisLabel:{color:cc.muted,fontSize:9,fontFamily:'JetBrains Mono'},splitLine:{lineStyle:{color:cc.grid,type:'dashed'}}}
                ],
                dataZoom:[{type:'inside',xAxisIndex:[0,1],start:35,end:100}],
                series:[
                    {name:'收盘',type:'line',xAxisIndex:0,yAxisIndex:0,data:bars.map(b=>b.close),symbol:'none',lineStyle:{color:'rgba(148,163,184,0.5)',width:1}},
                    ...oscSeries.map(s=>({ name:s.name, type:s.bar?'bar':'line', xAxisIndex:1, yAxisIndex:1, data:s.data, symbol:'none',
                        lineStyle:{color:s.color,width:1.5}, itemStyle:{color:s.color}, ...(s.bar?{barWidth:'60%'}:{}, s.demo?{lineStyle:{type:'dashed',color:s.color}}:{}) }))
                ]
            };
            // MACD 柱：涨跌色
            if (f.k==='MACD'){ const histSeries = opt.series.find(s=>s.name==='HIST'); if(histSeries){ histSeries.data = histSeries.data.map(v=>({value:v, itemStyle:{color:v>=0?cc.rise:cc.fall}})); } }
        }

        // ★ 签名元素：预热真空带 —— talib 预热期 NaN 显式可视化
        const markAreas = [];
        if (lookback > 0) {
            markAreas.push([
                {xAxis:0, itemStyle:{color:'rgba(251,191,36,0.07)'}},
                {xAxis: Math.min(lookback, PG.dates.length-1)}
            ]);
        }
        const targetSeriesIdx = onPriceAxis ? (opt.series.length>1?1:0) : opt.series.length-1;
        if (markAreas.length) {
            opt.series[targetSeriesIdx].markArea = { silent:true, itemStyle:{}, data: markAreas };
            opt.series[targetSeriesIdx].markPoint = {
                symbol:'pin', symbolSize:0, data:[{coord:[Math.min(lookback-1,PG.dates.length-1),0], label:{show:true,formatter:'预热真空带 · 前 '+lookback+' 根 NaN',color:'#fbbf24',fontSize:10, position:'insideTopLeft', distance:[4,4]}}]
            };
        }

        PG.chart.setOption(opt, true);
        window.addEventListener('resize', () => PG.chart && PG.chart.resize());
    }

    /* ================================================================
       MODALS
       ================================================================ */
    function openCreateModal(){ document.getElementById('modalTitle').textContent='新建因子'; document.getElementById('f_key').value='NEW_FACTOR'; document.getElementById('f_key_err').style.display='none'; document.getElementById('createModal').classList.add('open'); }
    function openEditModal(key){
        const f = FACTOR_MAP[key];
        document.getElementById('modalTitle').textContent='编辑因子 · '+key;
        document.getElementById('f_key').value=f.k;
        document.getElementById('f_name').value=f.n;
        document.getElementById('f_func').value=f.fn||'';
        document.getElementById('f_desc').value=f.desc;
        document.getElementById('createModal').classList.add('open');
    }
    function openBatchModal(){ document.getElementById('batchModal').classList.add('open'); }
    function closeModal(id){ document.getElementById(id).classList.remove('open'); }
    document.querySelectorAll('.modal-mask').forEach(m => m.addEventListener('click', e => { if(e.target===m) m.classList.remove('open'); }));

    /* ================================================================
       EVENTS
       ================================================================ */
    document.getElementById('railSearch').addEventListener('input', e => { state.q = e.target.value; renderTable(); });
    document.getElementById('onlyCompute').addEventListener('change', e => { state.onlyCompute = e.target.checked; renderTable(); });
    document.querySelectorAll('.sort-btn').forEach(b => b.addEventListener('click', () => {
        document.querySelectorAll('.sort-btn').forEach(x=>x.classList.remove('active')); b.classList.add('active');
        state.sort = b.dataset.sort; renderTable();
    }));

    /* ================================================================
       INIT
       ================================================================ */
    function init() {
        // 填充分类下拉
        document.getElementById('f_cat').innerHTML = CATEGORIES.map(c=>`<option value="${c.key}">${c.name} · ${c.key}</option>`).join('');
        renderStats(); renderLegend(); renderRail(); renderTable(); renderDetail();
    }
    document.addEventListener('DOMContentLoaded', init);
    