-- ============================================================
-- Stock Watcher 数据库 Schema (MySQL)
-- 注意：字段类型/长度变更时，请同步修改 schema-sqlite.sql
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username    VARCHAR(64)  NOT NULL UNIQUE COMMENT '用户名',
    password    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密存储）',
    totp_secret VARCHAR(64)  COMMENT 'TOTP 双因素认证密钥',
    enabled     TINYINT      DEFAULT 1 COMMENT '是否启用：1=启用，0=禁用',
    email       VARCHAR(128) COMMENT '邮箱',
    phone       VARCHAR(32)  COMMENT '手机号',
    role        VARCHAR(16)  DEFAULT 'USER' COMMENT '角色：USER=普通用户，ADMIN=管理员',
    created_at  VARCHAR(32)  COMMENT '创建时间',
    updated_at  VARCHAR(32)  COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 自选股表
CREATE TABLE IF NOT EXISTS sys_watchlist (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    stock_code  VARCHAR(16)  NOT NULL COMMENT '股票代码',
    created_at  VARCHAR(32)  COMMENT '创建时间',
    UNIQUE(user_id, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股表';

-- 3. 日线行情表
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    VARCHAR(16)    NOT NULL COMMENT '股票代码（如 000001.SZ）',
    trade_date VARCHAR(8)     NOT NULL COMMENT '交易日期（YYYYMMDD）',
    open       DECIMAL(20,4)  COMMENT '开盘价',
    high       DECIMAL(20,4)  COMMENT '最高价',
    low        DECIMAL(20,4)  COMMENT '最低价',
    close      DECIMAL(20,4)  COMMENT '收盘价',
    pre_close  DECIMAL(20,4)  COMMENT '昨收价',
    change_amt DECIMAL(20,4)  COMMENT '涨跌额',
    pct_chg    DECIMAL(20,4)  COMMENT '涨跌幅（%）',
    vol        DECIMAL(20,4)  COMMENT '成交量（手）',
    amount     DECIMAL(20,4)  COMMENT '成交额（千元）',
    PRIMARY KEY (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日线行情表';

-- 4. 股票基本信息表
CREATE TABLE IF NOT EXISTS stock_basic (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code      VARCHAR(16)  NOT NULL UNIQUE COMMENT 'TS代码（如 000001.SZ）',
    symbol       VARCHAR(16)  COMMENT '股票简称代码（如 000001）',
    name         VARCHAR(64)  COMMENT '股票名称',
    area         VARCHAR(32)  COMMENT '地域',
    industry     VARCHAR(64)  COMMENT '所属行业',
    fullname     VARCHAR(128) COMMENT '公司全称',
    enname       VARCHAR(128) COMMENT '英文名称',
    cnspell      VARCHAR(32)  COMMENT '拼音简写',
    market       VARCHAR(16)  COMMENT '市场（主板/创业板/科创板/CDR）',
    exchange     VARCHAR(16)  COMMENT '交易所代码（SSE/SZSE）',
    curr_type    VARCHAR(8)   COMMENT '交易货币',
    list_status  VARCHAR(4)   COMMENT '上市状态：L=上市，D=退市，P=暂停上市',
    list_date    VARCHAR(8)   COMMENT '上市日期（YYYYMMDD）',
    delist_date  VARCHAR(8)   COMMENT '退市日期（YYYYMMDD）',
    is_hs        VARCHAR(4)   COMMENT '是否沪深港通标的：H=沪股通，S=深股通，N=否',
    act_name     VARCHAR(128) COMMENT '实控人名称',
    act_ent_type VARCHAR(32)  COMMENT '实控人企业性质'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基本信息表';

-- 5. 交易日历表
CREATE TABLE IF NOT EXISTS trade_cal (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    exchange       VARCHAR(16) COMMENT '交易所代码（SSE/SZSE/CFFEX 等）',
    cal_date       VARCHAR(8)  NOT NULL COMMENT '日历日期（YYYYMMDD）',
    is_open        VARCHAR(4)  COMMENT '是否交易：0=休市，1=交易',
    pretrade_date  VARCHAR(8)  COMMENT '上一交易日（YYYYMMDD）',
    is_first_of_week     TINYINT DEFAULT 0 COMMENT '是否本周首个交易日：1=是，0=否',
    is_last_of_week      TINYINT DEFAULT 0 COMMENT '是否本周末个交易日：1=是，0=否',
    is_first_of_month    TINYINT DEFAULT 0 COMMENT '是否本月首个交易日：1=是，0=否',
    is_last_of_month     TINYINT DEFAULT 0 COMMENT '是否本月末个交易日：1=是，0=否',
    is_first_of_quarter  TINYINT DEFAULT 0 COMMENT '是否本季首个交易日：1=是，0=否',
    is_last_of_quarter   TINYINT DEFAULT 0 COMMENT '是否本季末个交易日：1=是，0=否',
    UNIQUE(exchange, cal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易日历表';

-- 6. 复权因子表
CREATE TABLE IF NOT EXISTS adj_factor (
    ts_code     VARCHAR(16)    NOT NULL COMMENT '股票代码',
    trade_date  VARCHAR(8)     NOT NULL COMMENT '交易日期（YYYYMMDD）',
    adj_factor  DECIMAL(20,4)  COMMENT '复权因子',
    PRIMARY KEY (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复权因子表';

-- 7. 分红送股表
CREATE TABLE IF NOT EXISTS dividend (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code       VARCHAR(16)    NOT NULL COMMENT '股票代码',
    end_date      VARCHAR(8)     COMMENT '分红年度截止日期（YYYYMMDD）',
    ann_date      VARCHAR(8)     COMMENT '公告日期（YYYYMMDD）',
    div_proc      VARCHAR(16)    COMMENT '分红进度',
    stk_div       DECIMAL(20,4)  COMMENT '每股送转股（股）',
    stk_bo_rate   DECIMAL(20,4)  COMMENT '送股比例',
    stk_co_rate   DECIMAL(20,4)  COMMENT '转增比例',
    cash_div      DECIMAL(20,4)  COMMENT '每股分红（税前，元）',
    cash_div_tax  DECIMAL(20,4)  COMMENT '每股分红（税后，元）',
    record_date   VARCHAR(8)     COMMENT '股权登记日（YYYYMMDD）',
    ex_date       VARCHAR(8)     COMMENT '除权除息日（YYYYMMDD）',
    pay_date      VARCHAR(8)     COMMENT '派息日（YYYYMMDD）',
    div_listdate  VARCHAR(8)     COMMENT '红上市日（YYYYMMDD）',
    imp_ann_date  VARCHAR(8)     COMMENT '实施公告日（YYYYMMDD）',
    base_date     VARCHAR(8)     COMMENT '基准日（YYYYMMDD）',
    base_share    DECIMAL(20,4)  COMMENT '基准总股本（万股）',
    UNIQUE(ts_code, end_date, ann_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分红送股表';

-- 8. 选股方案表
CREATE TABLE IF NOT EXISTS screen_plan (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name          VARCHAR(128) NOT NULL COMMENT '方案名称',
    description   VARCHAR(512) COMMENT '方案描述',
    screen_config TEXT         NOT NULL COMMENT '选股条件配置（JSON）',
    created_at    VARCHAR(32)  COMMENT '创建时间',
    updated_at    VARCHAR(32)  COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股方案表';

-- 9. 选股结果表
CREATE TABLE IF NOT EXISTS screen_result (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    plan_id      BIGINT       NOT NULL COMMENT '选股方案ID',
    screen_date  VARCHAR(8)   NOT NULL COMMENT '选股日期（YYYYMMDD）',
    total_count  INT          COMMENT '命中股票数量',
    stocks_json  TEXT         COMMENT '命中股票列表（JSON）',
    params_json  TEXT         COMMENT '本次选股参数（JSON）',
    created_at   VARCHAR(32)  COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股结果表';

-- 10. 选股锁定表
CREATE TABLE IF NOT EXISTS screen_lock (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    result_id         BIGINT       COMMENT '选股结果ID',
    plan_id           BIGINT       COMMENT '选股方案ID',
    lock_date         VARCHAR(8)   COMMENT '锁定日期（YYYYMMDD）',
    stocks_json       TEXT         COMMENT '锁定股票列表（JSON）',
    ret_5d            DECIMAL(20,4) COMMENT '5日收益率（%）',
    ret_10d           DECIMAL(20,4) COMMENT '10日收益率（%）',
    ret_20d           DECIMAL(20,4) COMMENT '20日收益率（%）',
    benchmark_ret_5d  DECIMAL(20,4) COMMENT '基准5日收益率（%）',
    benchmark_ret_10d DECIMAL(20,4) COMMENT '基准10日收益率（%）',
    benchmark_ret_20d DECIMAL(20,4) COMMENT '基准20日收益率（%）',
    status            VARCHAR(16)  COMMENT '状态',
    created_at        VARCHAR(32)  COMMENT '创建时间',
    updated_at        VARCHAR(32)  COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股锁定表';

-- 11. 每日基本面表（Tushare daily_basic：估值/换手率/市值）
CREATE TABLE IF NOT EXISTS daily_basic (
    trade_date      VARCHAR(8) NOT NULL COMMENT '交易日期（YYYYMMDD）',
    ts_code         VARCHAR(16) NOT NULL COMMENT '股票代码',
    close           DECIMAL(20,4) COMMENT '当日收盘价（元）',
    turnover_rate   DECIMAL(20,4) COMMENT '换手率（%）',
    turnover_rate_f DECIMAL(20,4) COMMENT '换手率（自由流通股，%）',
    volume_ratio    DECIMAL(20,4) COMMENT '量比',
    pe              DECIMAL(20,4) COMMENT '市盈率（总市值/净利润，亏损为空）',
    pe_ttm          DECIMAL(20,4) COMMENT '市盈率（TTM）',
    pb              DECIMAL(20,4) COMMENT '市净率（总市值/净资产）',
    ps              DECIMAL(20,4) COMMENT '市销率',
    ps_ttm          DECIMAL(20,4) COMMENT '市销率（TTM）',
    dv_ratio        DECIMAL(20,4) COMMENT '股息率（%）',
    dv_ttm          DECIMAL(20,4) COMMENT '股息率（TTM，%）',
    total_share     DECIMAL(20,4) COMMENT '总股本（万股）',
    float_share     DECIMAL(20,4) COMMENT '流通股本（万股）',
    free_share      DECIMAL(20,4) COMMENT '自由流通股本（万股）',
    total_mv        DECIMAL(20,4) COMMENT '总市值（万元）',
    circ_mv         DECIMAL(20,4) COMMENT '流通市值（万元）',
    PRIMARY KEY (trade_date, ts_code),
    INDEX idx_daily_basic_tscode (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日基本面表（估值/换手率/市值）';

-- 12. 财务指标表（Tushare fina_indicator：ROE/ROA/毛利率/同比/资产负债率等）
CREATE TABLE IF NOT EXISTS fina_indicator (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code            VARCHAR(16) NOT NULL COMMENT '股票代码',
    end_date           VARCHAR(8) NOT NULL COMMENT '报告期（YYYYMMDD）',
    ann_date           VARCHAR(8) COMMENT '公告日期（YYYYMMDD）',
    roe                DECIMAL(20,4) COMMENT '净资产收益率（%）',
    roa                DECIMAL(20,4) COMMENT '总资产收益率（%）',
    grossprofit_margin DECIMAL(20,4) COMMENT '销售毛利率（%）',
    netprofit_margin   DECIMAL(20,4) COMMENT '销售净利率（%）',
    dt_netprofit_yoy   DECIMAL(20,4) COMMENT '归母净利润同比增长率（%）',
    revenue_yoy        DECIMAL(20,4) COMMENT '营业收入同比增长率（%）',
    debt_to_assets     DECIMAL(20,4) COMMENT '资产负债率（%）',
    eps_yoy            DECIMAL(20,4) COMMENT '基本每股收益同比增长率（%）',
    UNIQUE KEY uk_fina (ts_code, end_date),
    INDEX idx_fina_indicator_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务指标表（ROE/ROA/毛利率/同比/资产负债率等）';

-- 13. 因子预计算快照表（每日收盘后预计算常用参数的技术面因子当日值）
CREATE TABLE IF NOT EXISTS factor_snapshot (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    trade_date   VARCHAR(8) NOT NULL COMMENT '交易日期（YYYYMMDD）',
    ts_code      VARCHAR(16) NOT NULL COMMENT '股票代码',
    factor_key   VARCHAR(32) NOT NULL COMMENT '因子标识（如 MA/MACD/RSI）',
    params_json  VARCHAR(128) NOT NULL DEFAULT '{}' COMMENT '因子参数（JSON，如 {"timeperiod":5}）',
    output_index INT NOT NULL DEFAULT 0 COMMENT '多输出因子结果索引（0=主输出）',
    factor_value DECIMAL(20,6) COMMENT '因子值',
    updated_at   VARCHAR(32) COMMENT '更新时间',
    UNIQUE KEY uk_snapshot (trade_date, ts_code, factor_key, params_json, output_index),
    INDEX idx_factor_snapshot_lookup (trade_date, ts_code, factor_key),
    INDEX idx_factor_snapshot_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='因子预计算快照表';

-- 14. 策略主表（quant_strategy）
CREATE TABLE IF NOT EXISTS quant_strategy (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    uuid            VARCHAR(64)  NOT NULL UNIQUE COMMENT '策略业务标识（UUID，仅用于前端交互，防止id遍历）',
    name            VARCHAR(128) NOT NULL COMMENT '策略名称',
    description     VARCHAR(512) COMMENT '策略描述',
    category        VARCHAR(32)  COMMENT '策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）',
    scope           VARCHAR(16)  COMMENT '适用范围（single/portfolio）',
    status          VARCHAR(16)  DEFAULT 'DRAFT' COMMENT '状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED）',
    tags            VARCHAR(512) COMMENT '标签（逗号分隔）',
    current_version INT          DEFAULT 1 COMMENT '当前生效版本号',
    created_at      VARCHAR(32)  COMMENT '创建时间（UTC ISO8601）',
    updated_at      VARCHAR(32)  COMMENT '更新时间（UTC ISO8601）',
    INDEX idx_quant_strategy_status (status),
    INDEX idx_quant_strategy_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略主表';

-- 15. 策略版本快照表（quant_strategy_version，strategy_id 为外键 → quant_strategy.id）
CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    strategy_id  BIGINT       NOT NULL COMMENT '策略主表ID（quant_strategy.id）',
    version_no   INT          NOT NULL COMMENT '版本号',
    config_json  MEDIUMTEXT   NOT NULL COMMENT '版本配置（统一策略 Schema JSON）',
    changelog    VARCHAR(512) COMMENT '版本变更说明',
    created_at   VARCHAR(32)  COMMENT '创建时间（UTC ISO8601）',
    UNIQUE KEY uk_strategy_version (strategy_id, version_no),
    INDEX idx_strategy_version_lookup (strategy_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略版本快照表';

-- 16. 回测主表/任务表（quant_backtest）
CREATE TABLE IF NOT EXISTS quant_backtest (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id         VARCHAR(64)  NOT NULL COMMENT '任务唯一ID（UUID）',
    strategy_id     BIGINT       NOT NULL COMMENT '策略主表ID（quant_strategy.id，内部关联用）',
    version_no      INT          NOT NULL COMMENT '策略版本号',
    mode            VARCHAR(16)  DEFAULT 'SINGLE' COMMENT '回测模式（SINGLE/GRID/WALK_FORWARD）',
    status          VARCHAR(16)  DEFAULT 'PENDING' COMMENT '任务状态（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）',
    progress        INT          DEFAULT 0 COMMENT '进度百分比（0-100）',
    error_message   TEXT         COMMENT '失败原因（截断1024字符）',
    override_config JSON         COMMENT '参数覆盖配置（JSON）',
    benchmark       VARCHAR(32)  DEFAULT '000300.SH' COMMENT '基准指数代码',
    created_by      VARCHAR(64)  COMMENT '创建人',
    started_at      VARCHAR(32)  COMMENT '开始执行时间（UTC ISO8601）',
    finished_at     VARCHAR(32)  COMMENT '完成时间（UTC ISO8601）',
    created_at      VARCHAR(32)  COMMENT '创建时间（UTC ISO8601）',
    UNIQUE KEY uk_backtest_task_id (task_id),
    INDEX idx_backtest_strategy_version (strategy_id, version_no),
    INDEX idx_backtest_status (status),
    INDEX idx_backtest_mode (mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测主表/任务表';

-- 17. 回测报告表（quant_backtest_report，SINGLE 模式全量 JSON）
CREATE TABLE IF NOT EXISTS quant_backtest_report (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    backtest_id          BIGINT       NOT NULL COMMENT '回测主表ID（quant_backtest.id）',
    metrics_json         JSON         COMMENT '指标集合（sharpe/return/drawdown等）',
    equity_curve_json    JSON         COMMENT '权益曲线（{dates,values}）',
    benchmark_curve_json JSON         COMMENT '基准归一化净值曲线（{dates,values}）',
    daily_returns_json   JSON         COMMENT '日收益率序列',
    trades_json          JSON         COMMENT '交易明细列表',
    orders_json          JSON         COMMENT '订单列表',
    positions_json       JSON         COMMENT '持仓快照列表',
    rebalance_diagnosis_json JSON     COMMENT '轮动调仓诊断（spec 011 P0-5）',
    effective_config_json JSON        COMMENT '实际生效配置（spec 011 P2-5，warmup_period 等）',
    execution_diagnosis_json JSON     COMMENT '执行诊断（spec 013 P2-9，分批调仓+冲击成本）',
    created_at           VARCHAR(32)  COMMENT '创建时间（UTC ISO8601）',
    UNIQUE KEY uk_backtest_report (backtest_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测报告全量JSON表';

-- 18. 指数成分股权重表（tushare index_weight）
CREATE TABLE IF NOT EXISTS index_weight (
    ts_code    VARCHAR(16)  NOT NULL COMMENT '指数代码（如 000300.SH）',
    trade_date VARCHAR(8)   NOT NULL COMMENT '交易日期（YYYYMMDD）',
    con_code   VARCHAR(16)  NOT NULL COMMENT '成分股代码（如 000001.SZ）',
    weight     DECIMAL(10,6) COMMENT '成分股权重（%）',
    PRIMARY KEY (ts_code, trade_date, con_code),
    INDEX idx_index_weight_date (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指数成分股权重表';

-- 19. 申万行业分类表（tushare index_classify，SWS2021 版本）
CREATE TABLE IF NOT EXISTS sw_industry (
    index_code  VARCHAR(32) NOT NULL COMMENT '行业代码（申万一级/二级/三级行业指数代码）',
    index_name  VARCHAR(64) COMMENT '行业名称',
    level       INT         COMMENT '行业层级（1/2/3）',
    parent_code VARCHAR(32) COMMENT '父级行业代码（一级行业为空）',
    src         VARCHAR(16) NOT NULL DEFAULT 'SWS2021' COMMENT '分类版本（SWS2021）',
    PRIMARY KEY (index_code, src)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申万行业分类表';

-- 20. 申万行业成分股表（tushare index_member_all）
CREATE TABLE IF NOT EXISTS sw_industry_member (
    ts_code     VARCHAR(16) NOT NULL COMMENT '股票代码（如 000001.SZ）',
    index_code  VARCHAR(32) NOT NULL COMMENT '所属行业代码（对应 sw_industry.index_code）',
    index_name  VARCHAR(64) COMMENT '行业名称',
    in_date     VARCHAR(8)  COMMENT '纳入日期（YYYYMMDD）',
    out_date    VARCHAR(8)  COMMENT '剔除日期（YYYYMMDD，为空表示当前在册）',
    is_new      VARCHAR(4)  COMMENT '是否最新（1=是，0=否）',
    src         VARCHAR(16) NOT NULL DEFAULT 'SWS2021' COMMENT '分类版本（SWS2021）',
    update_date VARCHAR(8)  NOT NULL COMMENT '更新日期（YYYYMMDD）',
    PRIMARY KEY (ts_code, index_code, update_date),
    INDEX idx_sw_member_tscode (ts_code),
    INDEX idx_sw_member_index (index_code, is_new)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申万行业成分股表';

-- 21. ST 戴帽摘帽表（tushare namechange）
CREATE TABLE IF NOT EXISTS stock_namechange (
    ts_code        VARCHAR(16) NOT NULL COMMENT '股票代码（如 000001.SZ）',
    name           VARCHAR(64) COMMENT '股票名称',
    start_date     VARCHAR(8)  COMMENT '生效日期（YYYYMMDD）',
    end_date       VARCHAR(8)  COMMENT '失效日期（YYYYMMDD，为空表示当前生效）',
    change_reason  VARCHAR(64) COMMENT '变更原因',
    PRIMARY KEY (ts_code, start_date),
    INDEX idx_namechange_tscode (ts_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ST 戴帽摘帽表';

-- 22. 停复牌表（tushare suspend_d）
CREATE TABLE IF NOT EXISTS stock_suspend_d (
    ts_code      VARCHAR(16)   NOT NULL COMMENT '股票代码（如 000001.SZ）',
    trade_date   VARCHAR(8)    NOT NULL COMMENT '停牌日期（YYYYMMDD）',
    susp_reason  VARCHAR(128)  COMMENT '停牌原因',
    resump_date  VARCHAR(8)    COMMENT '复牌日期（YYYYMMDD）',
    PRIMARY KEY (ts_code, trade_date),
    INDEX idx_suspend_tscode_date (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='停复牌表';

-- 23. 涨跌停价表（tushare stk_limit）
CREATE TABLE IF NOT EXISTS stock_stk_limit (
    ts_code     VARCHAR(16) NOT NULL COMMENT '股票代码（如 000001.SZ）',
    trade_date  VARCHAR(8)  NOT NULL COMMENT '交易日期（YYYYMMDD）',
    pre_close   DOUBLE      COMMENT '前收盘价',
    up_limit    DOUBLE      COMMENT '涨停价',
    down_limit  DOUBLE      COMMENT '跌停价',
    PRIMARY KEY (ts_code, trade_date),
    INDEX idx_limit_tscode_date (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='涨跌停价表';

-- 24. 利润表（tushare income，doc_id=33）
CREATE TABLE IF NOT EXISTS income (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code                    VARCHAR(16) NOT NULL COMMENT '股票代码',
    ann_date                   VARCHAR(8)  COMMENT '公告日期（YYYYMMDD）',
    f_ann_date                 VARCHAR(8)  COMMENT '实际公告日期（YYYYMMDD）',
    end_date                   VARCHAR(8)  NOT NULL COMMENT '报告期（YYYYMMDD）',
    report_type                VARCHAR(4)  COMMENT '报告类型：1=合并/2=单季合并/3=调整单季/4=调整合并/5=调整前/6=调整后',
    comp_type                   VARCHAR(4)  COMMENT '公司类型：1=一般工商/2=证券/3=保险/4=银行',
    basic_eps                  DECIMAL(20,4) COMMENT '基本每股收益',
    diluted_eps                DECIMAL(20,4) COMMENT '稀释每股收益',
    total_revenue              DECIMAL(20,4) COMMENT '营业总收入',
    revenue                    DECIMAL(20,4) COMMENT '营业收入',
    total_cogs                 DECIMAL(20,4) COMMENT '营业总成本',
    operate_cost               DECIMAL(20,4) COMMENT '营业成本',
    operate_profit             DECIMAL(20,4) COMMENT '营业利润',
    non_oper_income            DECIMAL(20,4) COMMENT '营业外收入',
    non_oper_exp               DECIMAL(20,4) COMMENT '营业外支出',
    total_profit               DECIMAL(20,4) COMMENT '利润总额',
    n_income                   DECIMAL(20,4) COMMENT '净利润（含少数股东）',
    n_income_attr_p            DECIMAL(20,4) COMMENT '归母净利润',
    minority_interest          DECIMAL(20,4) COMMENT '少数股东损益',
    adjust_profit              DECIMAL(20,4) COMMENT '调整后利润',
    income_tax                 DECIMAL(20,4) COMMENT '所得税费用',
    n_income_yoy               DECIMAL(20,4) COMMENT '净利润同比',
    dt_profit_yoy              DECIMAL(20,4) COMMENT '归母净利润同比',
    sell_exp                   DECIMAL(20,4) COMMENT '销售费用',
    admin_exp                  DECIMAL(20,4) COMMENT '管理费用',
    financial_exp              DECIMAL(20,4) COMMENT '财务费用',
    rd_exp                     DECIMAL(20,4) COMMENT '研发费用',
    impair_end_invest          DECIMAL(20,4) COMMENT '资产减值损失-投资',
    impair_end_oper            DECIMAL(20,4) COMMENT '资产减值损失-经营',
    invest_income              DECIMAL(20,4) COMMENT '投资收益',
    invest_income_inc          DECIMAL(20,4) COMMENT '对联营企业投资收益',
    invest_income_dec          DECIMAL(20,4) COMMENT '丧失投资收益',
    fairvalue_change_income    DECIMAL(20,4) COMMENT '公允价值变动收益',
    exchange_gain              DECIMAL(20,4) COMMENT '汇兑收益',
    asset_dispose_income       DECIMAL(20,4) COMMENT '资产处置收益',
    other_income               DECIMAL(20,4) COMMENT '其他收益',
    operate_n_income           DECIMAL(20,4) COMMENT '营业活动净利润',
    credit_impair_loss         DECIMAL(20,4) COMMENT '信用减值损失',
    asset_impair_loss          DECIMAL(20,4) COMMENT '资产减值损失',
    bbit                       DECIMAL(20,4) COMMENT '息税前利润',
    bbit_yoy                   DECIMAL(20,4) COMMENT '息税前利润同比',
    operate_profit_income_yoy  DECIMAL(20,4) COMMENT '营业利润同比',
    update_flag                VARCHAR(4)  COMMENT '更新标识',
    UNIQUE KEY uk_income (ts_code, end_date, report_type),
    INDEX idx_income_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润表';

-- 25. 资产负债表（tushare balancesheet，doc_id=36）
CREATE TABLE IF NOT EXISTS balancesheet (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code                     VARCHAR(16) NOT NULL COMMENT '股票代码',
    ann_date                    VARCHAR(8)  COMMENT '公告日期（YYYYMMDD）',
    f_ann_date                  VARCHAR(8)  COMMENT '实际公告日期（YYYYMMDD）',
    end_date                    VARCHAR(8)  NOT NULL COMMENT '报告期（YYYYMMDD）',
    report_type                 VARCHAR(4)  COMMENT '报告类型：1=合并/2=单季合并/3=调整单季/4=调整合并/5=调整前/6=调整后',
    comp_type                    VARCHAR(4)  COMMENT '公司类型：1=一般工商/2=证券/3=保险/4=银行',
    monetary_funds              DECIMAL(20,4) COMMENT '货币资金',
    accounts_rece               DECIMAL(20,4) COMMENT '应收票据及应收账款',
    notes_rece                  DECIMAL(20,4) COMMENT '应收票据',
    accounts_rece_fin          DECIMAL(20,4) COMMENT '应收账款',
    other_rece                  DECIMAL(20,4) COMMENT '其他应收款',
    prepayment                  DECIMAL(20,4) COMMENT '预付款项',
    dividends_rece              DECIMAL(20,4) COMMENT '应收股利',
    int_rece                    DECIMAL(20,4) COMMENT '应收利息',
    inventories                 DECIMAL(20,4) COMMENT '存货',
    non_current_assets_in_1_yr  DECIMAL(20,4) COMMENT '一年内到期的非流动资产',
    other_current_assets        DECIMAL(20,4) COMMENT '其他流动资产',
    total_current_assets        DECIMAL(20,4) COMMENT '流动资产合计',
    equity_joint_cap            DECIMAL(20,4) COMMENT '联营企业投资',
    lt_receivable               DECIMAL(20,4) COMMENT '长期应收款',
    eqt_invest                  DECIMAL(20,4) COMMENT '长期股权投资',
    inv_real_estate             DECIMAL(20,4) COMMENT '投资性房地产',
    fix_assets_nca              DECIMAL(20,4) COMMENT '固定资产净额',
    cip                         DECIMAL(20,4) COMMENT '在建工程',
    construction_materials      DECIMAL(20,4) COMMENT '工程物资',
    intang_assets               DECIMAL(20,4) COMMENT '无形资产',
    goodwill                    DECIMAL(20,4) COMMENT '商誉',
    lt_amort_deferred_exp       DECIMAL(20,4) COMMENT '长期待摊费用',
    defer_tax_assets            DECIMAL(20,4) COMMENT '递延所得税资产',
    other_non_current_assets    DECIMAL(20,4) COMMENT '其他非流动资产',
    total_non_current_assets    DECIMAL(20,4) COMMENT '非流动资产合计',
    total_assets                DECIMAL(20,4) COMMENT '资产总计',
    lt_borr                     DECIMAL(20,4) COMMENT '短期借款',
    notes_payable               DECIMAL(20,4) COMMENT '应付票据',
    accounts_payable            DECIMAL(20,4) COMMENT '应付票据及应付账款',
    accounts_payable_fin        DECIMAL(20,4) COMMENT '应付账款',
    prepayment_receivables      DECIMAL(20,4) COMMENT '预收款项',
    wage_payable                DECIMAL(20,4) COMMENT '应付职工薪酬',
    taxes_surcharges            DECIMAL(20,4) COMMENT '应交税费',
    other_payable               DECIMAL(20,4) COMMENT '其他应付款',
    non_current_liab_in_1_yr    DECIMAL(20,4) COMMENT '一年内到期的非流动负债',
    other_current_liab          DECIMAL(20,4) COMMENT '其他流动负债',
    total_current_liab          DECIMAL(20,4) COMMENT '流动负债合计',
    long_term_borr              DECIMAL(20,4) COMMENT '长期借款',
    ppayable_bonds              DECIMAL(20,4) COMMENT '应付债券',
    long_term_payable           DECIMAL(20,4) COMMENT '长期应付款',
    specific_payable            DECIMAL(20,4) COMMENT '专项应付款',
    estimated_liab              DECIMAL(20,4) COMMENT '预计负债',
    defer_tax_liab              DECIMAL(20,4) COMMENT '递延所得税负债',
    defer_inc_non_curr_liab     DECIMAL(20,4) COMMENT '递延收益-非流动负债',
    other_non_current_liab      DECIMAL(20,4) COMMENT '其他非流动负债',
    total_non_current_liab      DECIMAL(20,4) COMMENT '非流动负债合计',
    total_liab                  DECIMAL(20,4) COMMENT '负债合计',
    share_capital               DECIMAL(20,4) COMMENT '实收资本/股本',
    capital_reserve             DECIMAL(20,4) COMMENT '资本公积',
    treasury_stock             DECIMAL(20,4) COMMENT '减:库存股',
    specific_reserves           DECIMAL(20,4) COMMENT '专项储备',
    surplus_reserve             DECIMAL(20,4) COMMENT '盈余公积',
    general_risk_reserve        DECIMAL(20,4) COMMENT '一般风险准备',
    undistributed_profit        DECIMAL(20,4) COMMENT '未分配利润',
    equity_parent_company       DECIMAL(20,4) COMMENT '归母股东权益合计',
    minority_interest           DECIMAL(20,4) COMMENT '少数股东权益',
    total_equity                DECIMAL(20,4) COMMENT '所有者权益合计',
    total_liab_equity           DECIMAL(20,4) COMMENT '负债及所有者权益总计',
    accounts_rece_decr          DECIMAL(20,4) COMMENT '应收账款-坏账准备',
    accounts_rece_fin_decr     DECIMAL(20,4) COMMENT '应收账款-坏账准备（金融类）',
    minority_interest_inc       DECIMAL(20,4) COMMENT '少数股东权益增加',
    minority_interest_dec       DECIMAL(20,4) COMMENT '少数股东权益减少',
    update_flag                 VARCHAR(4)  COMMENT '更新标识',
    UNIQUE KEY uk_balancesheet (ts_code, end_date, report_type),
    INDEX idx_balancesheet_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产负债表';

-- 26. 现金流量表（tushare cashflow，doc_id=44）
CREATE TABLE IF NOT EXISTS cashflow (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code                     VARCHAR(16) NOT NULL COMMENT '股票代码',
    ann_date                    VARCHAR(8)  COMMENT '公告日期（YYYYMMDD）',
    f_ann_date                  VARCHAR(8)  COMMENT '实际公告日期（YYYYMMDD）',
    end_date                    VARCHAR(8)  NOT NULL COMMENT '报告期（YYYYMMDD）',
    report_type                 VARCHAR(4)  COMMENT '报告类型：1=合并/2=单季合并/3=调整单季/4=调整合并/5=调整前/6=调整后',
    comp_type                    VARCHAR(4)  COMMENT '公司类型：1=一般工商/2=证券/3=保险/4=银行',
    n_cashflow_act              DECIMAL(20,4) COMMENT '经营活动产生的现金流量净额',
    n_cashflow_inv_act          DECIMAL(20,4) COMMENT '投资活动产生的现金流量净额',
    n_cash_flows_fnc_act        DECIMAL(20,4) COMMENT '筹资活动产生的现金流量净额',
    free_cashflow               DECIMAL(20,4) COMMENT '自由现金流',
    c_fr_sale_sg                DECIMAL(20,4) COMMENT '销售商品提供劳务收到的现金',
    c_fr_oth_sg                 DECIMAL(20,4) COMMENT '收到的其他与经营活动有关的现金',
    c_paid_goods_s              DECIMAL(20,4) COMMENT '购买商品接受劳务支付的现金',
    c_paid_to_for_empl          DECIMAL(20,4) COMMENT '支付给职工以及为职工支付的现金',
    c_paid_for_taxes            DECIMAL(20,4) COMMENT '支付的各项税费',
    c_paid_oth_op_f             DECIMAL(20,4) COMMENT '支付其他与经营活动有关的现金',
    c_paid_invest                DECIMAL(20,4) COMMENT '投资支付的现金',
    c_paid_invest_f             DECIMAL(20,4) COMMENT '支付其他与投资活动有关的现金',
    c_pay_acq_const_fiolta      DECIMAL(20,4) COMMENT '购建固定资产无形资产支付的现金',
    c_pay_acq_int_long_loan     DECIMAL(20,4) COMMENT '偿还债务支付的现金',
    disp_fix_assets_oth         DECIMAL(20,4) COMMENT '处置固定资产收回的现金净额',
    n_invest_loss               DECIMAL(20,4) COMMENT '投资损失',
    c_fr_fnc_loan               DECIMAL(20,4) COMMENT '取得借款收到的现金',
    c_fr_fnc_oth                DECIMAL(20,4) COMMENT '收到其他与筹资活动有关的现金',
    proceeds_long_loan          DECIMAL(20,4) COMMENT '取得长期借款收到的现金',
    c_paid_fin_fees             DECIMAL(20,4) COMMENT '支付其他与筹资活动有关的现金',
    c_pay_dist_dpcp_int_exp     DECIMAL(20,4) COMMENT '分配股利利润偿付利息支付的现金',
    end_bal_cash                DECIMAL(20,4) COMMENT '期末现金及现金等价物余额',
    beg_bal_cash                DECIMAL(20,4) COMMENT '期初现金及现金等价物余额',
    n_cash_equ                  DECIMAL(20,4) COMMENT '现金及现金等价物净增加额',
    n_increase_incl_child       DECIMAL(20,4) COMMENT '净增加额-含子公司',
    prov_depr_assets            DECIMAL(20,4) COMMENT '资产减值准备',
    depr_fa_coga_dpba           DECIMAL(20,4) COMMENT '固定资产折旧',
    amort_intang                DECIMAL(20,4) COMMENT '无形资产摊销',
    amort_lt_deferred_exp       DECIMAL(20,4) COMMENT '长期待摊费用摊销',
    loss_disp_fa                DECIMAL(20,4) COMMENT '处置固定资产损失',
    loss_scr_fa                 DECIMAL(20,4) COMMENT '固定资产报废损失',
    loss_fair_valu              DECIMAL(20,4) COMMENT '公允价值变动损失',
    fin_exp                     DECIMAL(20,4) COMMENT '财务费用',
    loss_inv                    DECIMAL(20,4) COMMENT '投资损失',
    dec_def_inc_tax_assets      DECIMAL(20,4) COMMENT '递延所得税资产减少',
    inc_def_inc_tax_liab        DECIMAL(20,4) COMMENT '递延所得税负债增加',
    dec_inv                     DECIMAL(20,4) COMMENT '存货的减少',
    dec_oper_rece               DECIMAL(20,4) COMMENT '经营性应收项目的减少',
    inc_oper_payable            DECIMAL(20,4) COMMENT '经营性应付项目的增加',
    net_profit                  DECIMAL(20,4) COMMENT '净利润',
    minority_interest           DECIMAL(20,4) COMMENT '少数股东损益',
    undistributed_profit_in     DECIMAL(20,4) COMMENT '未分配利润增加',
    update_flag                 VARCHAR(4)  COMMENT '更新标识',
    UNIQUE KEY uk_cashflow (ts_code, end_date, report_type),
    INDEX idx_cashflow_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='现金流量表';

-- 27. 业绩预告（tushare forecast，doc_id=45，保留多次预告历史）
CREATE TABLE IF NOT EXISTS forecast (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code           VARCHAR(16) NOT NULL COMMENT '股票代码',
    ann_date          VARCHAR(8)  COMMENT '公告日期（YYYYMMDD）',
    end_date          VARCHAR(8)  NOT NULL COMMENT '报告期（YYYYMMDD）',
    type              VARCHAR(16) COMMENT '业绩预告类型：预增/预减/扭亏/续盈/续亏/略增/略减/不确定',
    p_change_min      DECIMAL(20,4) COMMENT '预告净利润变动幅度下限（%）',
    p_change_max      DECIMAL(20,4) COMMENT '预告净利润变动幅度上限（%）',
    net_profit_min    DECIMAL(20,4) COMMENT '预告净利润下限（万元）',
    net_profit_max    DECIMAL(20,4) COMMENT '预告净利润上限（万元）',
    last_parent_net  DECIMAL(20,4) COMMENT '上年同期归属母公司净利润',
    summary           VARCHAR(1000) COMMENT '业绩预告内容',
    change_reason     VARCHAR(2000) COMMENT '业绩变动原因',
    UNIQUE KEY uk_forecast (ts_code, end_date, ann_date),
    INDEX idx_forecast_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业绩预告表';

-- 28. 业绩快报（tushare express，doc_id=46，一个报告期一条快报）
CREATE TABLE IF NOT EXISTS express (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ts_code                         VARCHAR(16) NOT NULL COMMENT '股票代码',
    ann_date                         VARCHAR(8)  COMMENT '公告日期（YYYYMMDD）',
    end_date                         VARCHAR(8)  NOT NULL COMMENT '报告期（YYYYMMDD）',
    revenue                          DECIMAL(20,4) COMMENT '营业收入',
    operate_profit                   DECIMAL(20,4) COMMENT '营业利润',
    total_profit                     DECIMAL(20,4) COMMENT '利润总额',
    n_income                         DECIMAL(20,4) COMMENT '净利润',
    total_assets                     DECIMAL(20,4) COMMENT '总资产',
    total_hldr_eqy_exc_min_int      DECIMAL(20,4) COMMENT '股东权益合计-不含少数股东权益',
    basic_eps                        DECIMAL(20,4) COMMENT '每股收益（摊薄）',
    diluted_eps                      DECIMAL(20,4) COMMENT '每股收益（摊薄）（稀释）',
    growth_yield                     DECIMAL(20,4) COMMENT '净利润增长率（%）',
    or_growth_yield                  DECIMAL(20,4) COMMENT '营业收入增长率（%）',
    yst_net_profit                   DECIMAL(20,4) COMMENT '上年三季度净利润',
    bm_net_profit                    DECIMAL(20,4) COMMENT '上年全年净利润',
    bm_growth_sales                  DECIMAL(20,4) COMMENT '上年全年营业收入增长率（%）',
    update_flag                      VARCHAR(4)  COMMENT '更新标识',
    UNIQUE KEY uk_express (ts_code, end_date),
    INDEX idx_express_tscode (ts_code, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业绩快报表';

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
