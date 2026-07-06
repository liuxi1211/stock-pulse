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

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
