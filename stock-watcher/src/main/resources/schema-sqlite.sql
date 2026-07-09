-- ============================================================
-- Stock Watcher 数据库 Schema (SQLite)
-- 注意：字段类型/长度变更时，请同步修改 schema-mysql.sql
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT    NOT NULL UNIQUE,
    password    TEXT    NOT NULL,
    totp_secret TEXT,
    enabled     INTEGER DEFAULT 1,
    email       TEXT,
    phone       TEXT,
    role        TEXT    DEFAULT 'USER',
    created_at  TEXT,
    updated_at  TEXT
);

-- 2. 自选股表
CREATE TABLE IF NOT EXISTS sys_watchlist (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    stock_code  TEXT    NOT NULL,
    created_at  TEXT,
    UNIQUE(user_id, stock_code)
);

-- 3. 日线行情表
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    TEXT    NOT NULL,
    trade_date TEXT    NOT NULL,
    open       REAL,
    high       REAL,
    low        REAL,
    close      REAL,
    pre_close  REAL,
    change_amt REAL,
    pct_chg    REAL,
    vol        REAL,
    amount     REAL,
    PRIMARY KEY (ts_code, trade_date)
);

-- 4. 股票基本信息表
CREATE TABLE IF NOT EXISTS stock_basic (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code      TEXT    NOT NULL UNIQUE,
    symbol       TEXT,
    name         TEXT,
    area         TEXT,
    industry     TEXT,
    fullname     TEXT,
    enname       TEXT,
    cnspell      TEXT,
    market       TEXT,
    exchange     TEXT,
    curr_type    TEXT,
    list_status  TEXT,
    list_date    TEXT,
    delist_date  TEXT,
    is_hs        TEXT,
    act_name     TEXT,
    act_ent_type TEXT
);

-- 5. 交易日历表
CREATE TABLE IF NOT EXISTS trade_cal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    exchange        TEXT,
    cal_date        TEXT    NOT NULL,
    is_open         TEXT,
    pretrade_date   TEXT,
    UNIQUE(exchange, cal_date)
);

-- 6. 复权因子表
CREATE TABLE IF NOT EXISTS adj_factor (
    ts_code     TEXT    NOT NULL,
    trade_date  TEXT    NOT NULL,
    adj_factor  REAL,
    PRIMARY KEY (ts_code, trade_date)
);

-- 7. 分红送股表
CREATE TABLE IF NOT EXISTS dividend (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code       TEXT    NOT NULL,
    end_date      TEXT,
    ann_date      TEXT,
    div_proc      TEXT,
    stk_div       REAL,
    stk_bo_rate   REAL,
    stk_co_rate   REAL,
    cash_div      REAL,
    cash_div_tax  REAL,
    record_date   TEXT,
    ex_date       TEXT,
    pay_date      TEXT,
    div_listdate  TEXT,
    imp_ann_date  TEXT,
    base_date     TEXT,
    base_share    REAL,
    UNIQUE(ts_code, end_date, ann_date)
);

-- 8. 选股方案表
CREATE TABLE IF NOT EXISTS screen_plan (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    screen_config TEXT         NOT NULL,
    created_at    VARCHAR(32),
    updated_at    VARCHAR(32)
);

-- 9. 选股结果表
CREATE TABLE IF NOT EXISTS screen_result (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id      INTEGER      NOT NULL,
    screen_date  VARCHAR(8)   NOT NULL,
    total_count  INTEGER,
    stocks_json  TEXT,
    params_json  TEXT,
    created_at   VARCHAR(32)
);

-- 10. 选股锁定表
CREATE TABLE IF NOT EXISTS screen_lock (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    result_id         INTEGER,
    plan_id           INTEGER,
    lock_date         VARCHAR(8),
    stocks_json       TEXT,
    ret_5d            REAL,
    ret_10d           REAL,
    ret_20d           REAL,
    benchmark_ret_5d  REAL,
    benchmark_ret_10d REAL,
    benchmark_ret_20d REAL,
    status            VARCHAR(16),
    created_at        VARCHAR(32),
    updated_at        VARCHAR(32)
);

-- 11. 每日基本面表（Tushare daily_basic：估值/换手率/市值）
CREATE TABLE IF NOT EXISTS daily_basic (
    trade_date      TEXT NOT NULL,
    ts_code         TEXT NOT NULL,
    close           REAL,
    turnover_rate   REAL,
    turnover_rate_f REAL,
    volume_ratio    REAL,
    pe              REAL,
    pe_ttm          REAL,
    pb              REAL,
    ps              REAL,
    ps_ttm          REAL,
    dv_ratio        REAL,
    dv_ttm          REAL,
    total_share     REAL,
    float_share     REAL,
    free_share      REAL,
    total_mv        REAL,
    circ_mv         REAL,
    PRIMARY KEY (trade_date, ts_code)
);
CREATE INDEX IF NOT EXISTS idx_daily_basic_tscode ON daily_basic (ts_code, trade_date);

-- 12. 财务指标表（Tushare fina_indicator：ROE/ROA/毛利率/同比/资产负债率等）
CREATE TABLE IF NOT EXISTS fina_indicator (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code            TEXT NOT NULL,
    end_date           TEXT NOT NULL,
    ann_date           TEXT,
    roe                REAL,
    roa                REAL,
    grossprofit_margin REAL,
    netprofit_margin   REAL,
    dt_netprofit_yoy   REAL,
    revenue_yoy        REAL,
    debt_to_assets     REAL,
    eps_yoy            REAL,
    UNIQUE (ts_code, end_date)
);
CREATE INDEX IF NOT EXISTS idx_fina_indicator_tscode ON fina_indicator (ts_code, end_date);

-- 13. 因子预计算快照表（每日收盘后预计算常用参数的技术面因子当日值）
CREATE TABLE IF NOT EXISTS factor_snapshot (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date   TEXT    NOT NULL,
    ts_code      TEXT    NOT NULL,
    factor_key   TEXT    NOT NULL,
    params_json  TEXT    NOT NULL DEFAULT '{}',
    output_index INTEGER NOT NULL DEFAULT 0,
    factor_value REAL,
    updated_at   TEXT,
    UNIQUE (trade_date, ts_code, factor_key, params_json, output_index)
);
CREATE INDEX IF NOT EXISTS idx_factor_snapshot_lookup ON factor_snapshot (trade_date, ts_code, factor_key);
CREATE INDEX IF NOT EXISTS idx_factor_snapshot_date ON factor_snapshot (trade_date);

-- 14. 策略主表（quant_strategy）
CREATE TABLE IF NOT EXISTS quant_strategy (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     TEXT         NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    category        VARCHAR(32),
    scope           VARCHAR(16),
    status          VARCHAR(16)  DEFAULT 'DRAFT',
    tags            VARCHAR(512),
    current_version INTEGER      DEFAULT 1,
    created_at      VARCHAR(32),
    updated_at      VARCHAR(32)
);

-- 15. 策略版本快照表（quant_strategy_version，strategy_id 为外键 → quant_strategy.id）
CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id  INTEGER NOT NULL,
    version_no   INTEGER NOT NULL,
    config_json  TEXT    NOT NULL,
    changelog    VARCHAR(512),
    created_at   VARCHAR(32),
    UNIQUE(strategy_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_strategy_version_lookup ON quant_strategy_version (strategy_id, version_no);

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
