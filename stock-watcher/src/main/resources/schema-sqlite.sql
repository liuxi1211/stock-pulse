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

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
