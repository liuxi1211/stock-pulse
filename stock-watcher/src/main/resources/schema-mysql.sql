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

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
