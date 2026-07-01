-- ============================================================
-- Stock Watcher 数据库 Schema
-- 数据库类型: SQLite
-- 说明: 本文件定义了股票监控系统的所有数据表结构
-- ============================================================

-- ============================================================
-- 1. 用户表 - 存储系统用户信息
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键ID，自增
    username    TEXT    NOT NULL UNIQUE,            -- 用户名，唯一
    password    TEXT    NOT NULL,                   -- 密码（BCrypt加密存储）
    totp_secret TEXT,                                -- TOTP二次验证密钥
    enabled     INTEGER DEFAULT 1,                  -- 是否启用（1=启用，0=禁用）
    email       TEXT,                                -- 邮箱
    phone       TEXT,                                -- 手机号
    role        TEXT    DEFAULT 'USER',             -- 角色（ADMIN=管理员，USER=普通用户）
    created_at  TEXT,                                -- 创建时间
    updated_at  TEXT                                 -- 更新时间
);

-- ============================================================
-- 2. 自选股表 - 存储用户的自选股票
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_watchlist (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键ID，自增
    user_id     INTEGER NOT NULL,                   -- 用户ID（关联 sys_user.id）
    stock_code  TEXT    NOT NULL,                   -- 股票代码（ts_code 格式，如 000001.SZ）
    created_at  TEXT,                                -- 添加时间
    UNIQUE(user_id, stock_code)                     -- 唯一约束：同一用户不能重复添加同一股票
);

-- ============================================================
-- 3. 日线行情表 - 存储股票日线行情数据
-- ============================================================
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    TEXT    NOT NULL,                    -- 股票代码（如 000001.SZ）
    trade_date TEXT    NOT NULL,                    -- 交易日期（YYYYMMDD 格式）
    open       REAL,                                 -- 开盘价
    high       REAL,                                 -- 最高价
    low        REAL,                                 -- 最低价
    close      REAL,                                 -- 收盘价
    pre_close  REAL,                                 -- 昨收价
    change_amt REAL,                                 -- 涨跌额
    pct_chg    REAL,                                 -- 涨跌幅（%）
    vol        REAL,                                 -- 成交量（手）
    amount     REAL,                                 -- 成交额（千元）
    PRIMARY KEY (ts_code, trade_date)               -- 主键：股票代码 + 交易日期
);

-- ============================================================
-- 4. 股票基本信息表 - 存储股票基础资料
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_basic (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键ID，自增
    ts_code     TEXT    NOT NULL UNIQUE,            -- 股票代码（如 000001.SZ）
    symbol      TEXT,                                -- 股票代码（纯数字，如 000001）
    name        TEXT,                                -- 股票名称
    area        TEXT,                                -- 所在省份/地区
    industry    TEXT,                                -- 所属行业
    fullname    TEXT,                                -- 公司全称
    enname      TEXT,                                -- 公司英文名称
    cnspell     TEXT,                                -- 拼音缩写
    market      TEXT,                                -- 市场类型（主板/创业板/科创板等）
    exchange    TEXT,                                -- 交易所（SSE=上交所，SZSE=深交所）
    curr_type   TEXT,                                -- 币种（CNY=人民币）
    list_status TEXT,                                -- 上市状态（L=上市，D=退市，P=暂停上市）
    list_date   TEXT,                                -- 上市日期
    delist_date TEXT,                                -- 退市日期
    is_hs       TEXT,                                -- 是否沪深港通标的（H=沪股通，S=深股通，N=否）
    act_name    TEXT,                                -- 审计机构名称
    act_ent_type TEXT                               -- 审计机构类型
);

-- ============================================================
-- 5. 交易日历表 - 存储各交易所交易日历
-- ============================================================
CREATE TABLE IF NOT EXISTS trade_cal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键ID，自增
    exchange        TEXT,                                -- 交易所（SSE=上交所，SZSE=深交所）
    cal_date        TEXT    NOT NULL,                    -- 日历日期（YYYYMMDD 格式）
    is_open         TEXT,                                -- 是否开盘（1=开盘，0=休市）
    pretrade_date   TEXT,                                -- 上一个交易日
    UNIQUE(exchange, cal_date)                           -- 唯一约束：交易所 + 日期
);

-- ============================================================
-- 6. 复权因子表 - 存储股票复权因子数据
-- ============================================================
CREATE TABLE IF NOT EXISTS adj_factor (
    ts_code     TEXT    NOT NULL,                    -- 股票代码（如 000001.SZ）
    trade_date  TEXT    NOT NULL,                    -- 交易日期（YYYYMMDD 格式）
    adj_factor  REAL,                                 -- 复权因子
    PRIMARY KEY (ts_code, trade_date)                -- 主键：股票代码 + 交易日期
);

-- ============================================================
-- 7. 分红送股表 - 存储股票分红送配数据
-- ============================================================
CREATE TABLE IF NOT EXISTS dividend (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键ID，自增
    ts_code      TEXT    NOT NULL,                   -- 股票代码（如 000001.SZ）
    end_date     TEXT,                                -- 分红期末（报告期）
    ann_date     TEXT,                                -- 预案公告日
    div_proc     TEXT,                                -- 实施进度（S=预案，A=实施，F=取消等）
    stk_div      REAL,                                -- 每股送股比例
    stk_bo_rate  REAL,                                -- 每股转增比例
    stk_co_rate  REAL,                                -- 每股配股比例
    cash_div     REAL,                                -- 每股派息（税前，元）
    cash_div_tax REAL,                                -- 每股派息（税后，元）
    record_date  TEXT,                                -- 股权登记日
    ex_date      TEXT,                                -- 除权除息日
    pay_date     TEXT,                                -- 派息日
    div_listdate TEXT,                                -- 红股上市日
    imp_ann_date TEXT,                                -- 实施公告日
    base_date    TEXT,                                -- 基准日
    base_share   REAL,                                -- 基准股本（股）
    UNIQUE(ts_code, end_date, ann_date)              -- 唯一约束：股票 + 期末 + 公告日
);

-- ============================================================
-- 初始数据
-- ============================================================

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
