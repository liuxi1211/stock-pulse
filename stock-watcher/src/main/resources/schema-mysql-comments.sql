-- ============================================================
-- Stock Watcher 数据库字段注释补丁 (MySQL)
-- 用途：当表已建好（无注释），通过 ALTER TABLE 补加字段/表注释
-- 使用：直接在 MySQL 中执行本脚本即可（可重复执行，结果幂等）
-- ============================================================

-- -----------------------------------------------------------
-- 1. sys_user 用户表
-- -----------------------------------------------------------
ALTER TABLE sys_user COMMENT = '用户表';
ALTER TABLE sys_user MODIFY COLUMN username    VARCHAR(64)  NOT NULL COMMENT '用户名';
ALTER TABLE sys_user MODIFY COLUMN password    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密���储）';
ALTER TABLE sys_user MODIFY COLUMN totp_secret VARCHAR(64)  COMMENT 'TOTP 双因素认证密钥';
ALTER TABLE sys_user MODIFY COLUMN enabled     TINYINT      DEFAULT 1 COMMENT '是否启用：1=启用，0=禁用';
ALTER TABLE sys_user MODIFY COLUMN email       VARCHAR(128) COMMENT '邮箱';
ALTER TABLE sys_user MODIFY COLUMN phone       VARCHAR(32)  COMMENT '手机号';
ALTER TABLE sys_user MODIFY COLUMN role        VARCHAR(16)  DEFAULT 'USER' COMMENT '角色：USER=普通用户，ADMIN=管理员';
ALTER TABLE sys_user MODIFY COLUMN created_at  VARCHAR(32)  COMMENT '创建时间';
ALTER TABLE sys_user MODIFY COLUMN updated_at  VARCHAR(32)  COMMENT '更新时间';

-- -----------------------------------------------------------
-- 2. sys_watchlist 自选股表
-- -----------------------------------------------------------
ALTER TABLE sys_watchlist COMMENT = '自选股表';
ALTER TABLE sys_watchlist MODIFY COLUMN user_id    BIGINT       NOT NULL COMMENT '用户ID';
ALTER TABLE sys_watchlist MODIFY COLUMN stock_code VARCHAR(16)  NOT NULL COMMENT '股票代码';
ALTER TABLE sys_watchlist MODIFY COLUMN group_id   BIGINT       NULL COMMENT '分组ID，NULL表示未分组';
ALTER TABLE sys_watchlist MODIFY COLUMN note       VARCHAR(255) NULL COMMENT '用户备注';
ALTER TABLE sys_watchlist MODIFY COLUMN target_price_high DECIMAL(20,4) NULL COMMENT '目标价上限';
ALTER TABLE sys_watchlist MODIFY COLUMN target_price_low  DECIMAL(20,4) NULL COMMENT '目标价下限';
ALTER TABLE sys_watchlist MODIFY COLUMN sort_order INT          DEFAULT 0 COMMENT '排序序号';
ALTER TABLE sys_watchlist MODIFY COLUMN created_at VARCHAR(32)  COMMENT '创建时间';

-- -----------------------------------------------------------
-- 2.1 sys_watchlist_group 自选股分组表
-- -----------------------------------------------------------
ALTER TABLE sys_watchlist_group COMMENT = '自选股分组表';
ALTER TABLE sys_watchlist_group MODIFY COLUMN user_id    BIGINT       NOT NULL COMMENT '用户ID';
ALTER TABLE sys_watchlist_group MODIFY COLUMN group_name VARCHAR(64)  NOT NULL COMMENT '分组名称';
ALTER TABLE sys_watchlist_group MODIFY COLUMN sort_order INT          DEFAULT 0 COMMENT '排序序号';
ALTER TABLE sys_watchlist_group MODIFY COLUMN created_at VARCHAR(32)  COMMENT '创建时间';

-- -----------------------------------------------------------
-- 3. daily_quote 日线行情表
-- -----------------------------------------------------------
ALTER TABLE daily_quote COMMENT = '日线行情表';
ALTER TABLE daily_quote MODIFY COLUMN ts_code    VARCHAR(16)    NOT NULL COMMENT '股票代码（如 000001.SZ）';
ALTER TABLE daily_quote MODIFY COLUMN trade_date VARCHAR(8)     NOT NULL COMMENT '交易日期（YYYYMMDD）';
ALTER TABLE daily_quote MODIFY COLUMN open       DECIMAL(20,4)  COMMENT '开盘价';
ALTER TABLE daily_quote MODIFY COLUMN high       DECIMAL(20,4)  COMMENT '最高价';
ALTER TABLE daily_quote MODIFY COLUMN low        DECIMAL(20,4)  COMMENT '最低价';
ALTER TABLE daily_quote MODIFY COLUMN close      DECIMAL(20,4)  COMMENT '收盘价';
ALTER TABLE daily_quote MODIFY COLUMN pre_close  DECIMAL(20,4)  COMMENT '昨收价';
ALTER TABLE daily_quote MODIFY COLUMN change_amt DECIMAL(20,4)  COMMENT '涨跌额';
ALTER TABLE daily_quote MODIFY COLUMN pct_chg    DECIMAL(20,4)  COMMENT '涨跌幅（%）';
ALTER TABLE daily_quote MODIFY COLUMN vol        DECIMAL(20,4)  COMMENT '成交量（手）';
ALTER TABLE daily_quote MODIFY COLUMN amount     DECIMAL(20,4)  COMMENT '成交额（千元）';

-- -----------------------------------------------------------
-- 4. stock_basic 股票基本信息表
-- -----------------------------------------------------------
ALTER TABLE stock_basic COMMENT = '股票基本信息表';
ALTER TABLE stock_basic MODIFY COLUMN ts_code      VARCHAR(16)  NOT NULL COMMENT 'TS代码（如 000001.SZ）';
ALTER TABLE stock_basic MODIFY COLUMN symbol       VARCHAR(16)  COMMENT '股票简称代码（如 000001）';
ALTER TABLE stock_basic MODIFY COLUMN name         VARCHAR(64)  COMMENT '股票名称';
ALTER TABLE stock_basic MODIFY COLUMN area         VARCHAR(32)  COMMENT '地域';
ALTER TABLE stock_basic MODIFY COLUMN industry     VARCHAR(64)  COMMENT '所属行业';
ALTER TABLE stock_basic MODIFY COLUMN fullname     VARCHAR(128) COMMENT '公司全称';
ALTER TABLE stock_basic MODIFY COLUMN enname       VARCHAR(128) COMMENT '英文名称';
ALTER TABLE stock_basic MODIFY COLUMN cnspell      VARCHAR(32)  COMMENT '拼音简写';
ALTER TABLE stock_basic MODIFY COLUMN market       VARCHAR(16)  COMMENT '市场（主板/创业板/科创板/CDR）';
ALTER TABLE stock_basic MODIFY COLUMN exchange     VARCHAR(16)  COMMENT '交易所代码（SSE/SZSE）';
ALTER TABLE stock_basic MODIFY COLUMN curr_type    VARCHAR(8)   COMMENT '交易货币';
ALTER TABLE stock_basic MODIFY COLUMN list_status  VARCHAR(4)   COMMENT '上市状态：L=上市��D=退市，P=暂停上市';
ALTER TABLE stock_basic MODIFY COLUMN list_date    VARCHAR(8)   COMMENT '上市日期（YYYYMMDD）';
ALTER TABLE stock_basic MODIFY COLUMN delist_date  VARCHAR(8)   COMMENT '退市日期（YYYYMMDD）';
ALTER TABLE stock_basic MODIFY COLUMN is_hs        VARCHAR(4)   COMMENT '是否沪深港通标的：H=沪股通，S=深股通，N=否';
ALTER TABLE stock_basic MODIFY COLUMN act_name     VARCHAR(128) COMMENT '实控人名称';
ALTER TABLE stock_basic MODIFY COLUMN act_ent_type VARCHAR(32)  COMMENT '实控人企业性质';

-- -----------------------------------------------------------
-- 5. trade_cal 交易日历表
-- -----------------------------------------------------------
ALTER TABLE trade_cal COMMENT = '交易日历表';
ALTER TABLE trade_cal MODIFY COLUMN exchange       VARCHAR(16) COMMENT '交易所代码（SSE/SZSE/CFFEX 等）';
ALTER TABLE trade_cal MODIFY COLUMN cal_date       VARCHAR(8)  NOT NULL COMMENT '日历日期（YYYYMMDD）';
ALTER TABLE trade_cal MODIFY COLUMN is_open        VARCHAR(4)  COMMENT '是否交易：0=休市，1=交易';
ALTER TABLE trade_cal MODIFY COLUMN pretrade_date  VARCHAR(8)  COMMENT '上一交易日（YYYYMMDD）';

-- -----------------------------------------------------------
-- 6. adj_factor 复权因子表
-- -----------------------------------------------------------
ALTER TABLE adj_factor COMMENT = '复权因子表';
ALTER TABLE adj_factor MODIFY COLUMN ts_code     VARCHAR(16)    NOT NULL COMMENT '股票代码';
ALTER TABLE adj_factor MODIFY COLUMN trade_date  VARCHAR(8)     NOT NULL COMMENT '交易日期（YYYYMMDD）';
ALTER TABLE adj_factor MODIFY COLUMN adj_factor  DECIMAL(20,4)  COMMENT '复权因子';

-- -----------------------------------------------------------
-- 7. dividend 分红送股表
-- -----------------------------------------------------------
ALTER TABLE dividend COMMENT = '分红送股表';
ALTER TABLE dividend MODIFY COLUMN ts_code       VARCHAR(16)    NOT NULL COMMENT '股票代码';
ALTER TABLE dividend MODIFY COLUMN end_date      VARCHAR(8)     COMMENT '分红年度截止日期（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN ann_date      VARCHAR(8)     COMMENT '公告日期（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN div_proc      VARCHAR(16)    COMMENT '分红进度';
ALTER TABLE dividend MODIFY COLUMN stk_div       DECIMAL(20,4)  COMMENT '每股送转股（股）';
ALTER TABLE dividend MODIFY COLUMN stk_bo_rate   DECIMAL(20,4)  COMMENT '送股比例';
ALTER TABLE dividend MODIFY COLUMN stk_co_rate   DECIMAL(20,4)  COMMENT '转增比例';
ALTER TABLE dividend MODIFY COLUMN cash_div      DECIMAL(20,4)  COMMENT '每股分红（税前，元）';
ALTER TABLE dividend MODIFY COLUMN cash_div_tax  DECIMAL(20,4)  COMMENT '每股分红（税后，元）';
ALTER TABLE dividend MODIFY COLUMN record_date   VARCHAR(8)     COMMENT '股权登记日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN ex_date       VARCHAR(8)     COMMENT '除权除息日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN pay_date      VARCHAR(8)     COMMENT '派息日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN div_listdate  VARCHAR(8)     COMMENT '红上市日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN imp_ann_date  VARCHAR(8)     COMMENT '实施公告日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN base_date     VARCHAR(8)     COMMENT '基准日（YYYYMMDD）';
ALTER TABLE dividend MODIFY COLUMN base_share    DECIMAL(20,4)  COMMENT '基准总股本（万股）';

-- -----------------------------------------------------------
-- 8. screen_plan 选股方案表
-- -----------------------------------------------------------
ALTER TABLE screen_plan COMMENT = '选股方案表';
ALTER TABLE screen_plan MODIFY COLUMN name          VARCHAR(128) NOT NULL COMMENT '方案名称';
ALTER TABLE screen_plan MODIFY COLUMN description   VARCHAR(512) COMMENT '方案描述';
ALTER TABLE screen_plan MODIFY COLUMN screen_config TEXT         NOT NULL COMMENT '选股条件配置（JSON）';
ALTER TABLE screen_plan MODIFY COLUMN created_at    VARCHAR(32)  COMMENT '创建时间';
ALTER TABLE screen_plan MODIFY COLUMN updated_at    VARCHAR(32)  COMMENT '更新时间';

-- -----------------------------------------------------------
-- 9. screen_result 选股结果表
-- -----------------------------------------------------------
ALTER TABLE screen_result COMMENT = '选股结果表';
ALTER TABLE screen_result MODIFY COLUMN plan_id      BIGINT       NOT NULL COMMENT '选股方案ID';
ALTER TABLE screen_result MODIFY COLUMN screen_date  VARCHAR(8)   NOT NULL COMMENT '选股日期（YYYYMMDD）';
ALTER TABLE screen_result MODIFY COLUMN total_count  INT          COMMENT '命中股票数量';
ALTER TABLE screen_result MODIFY COLUMN stocks_json  TEXT         COMMENT '命中股票列表（JSON）';
ALTER TABLE screen_result MODIFY COLUMN params_json  TEXT         COMMENT '本次选股参数（JSON）';
ALTER TABLE screen_result MODIFY COLUMN created_at   VARCHAR(32)  COMMENT '创建时间';

-- -----------------------------------------------------------
-- 10. screen_lock 选股锁定表
-- -----------------------------------------------------------
ALTER TABLE screen_lock COMMENT = '选股锁定表';
ALTER TABLE screen_lock MODIFY COLUMN result_id         BIGINT       COMMENT '选股结果ID';
ALTER TABLE screen_lock MODIFY COLUMN plan_id           BIGINT       COMMENT '选股方案ID';
ALTER TABLE screen_lock MODIFY COLUMN lock_date         VARCHAR(8)   COMMENT '锁定日期（YYYYMMDD）';
ALTER TABLE screen_lock MODIFY COLUMN stocks_json       TEXT         COMMENT '锁定股票列表（JSON）';
ALTER TABLE screen_lock MODIFY COLUMN ret_5d            DECIMAL(20,4) COMMENT '5日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN ret_10d           DECIMAL(20,4) COMMENT '10日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN ret_20d           DECIMAL(20,4) COMMENT '20日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN benchmark_ret_5d  DECIMAL(20,4) COMMENT '基准5日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN benchmark_ret_10d DECIMAL(20,4) COMMENT '基准10日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN benchmark_ret_20d DECIMAL(20,4) COMMENT '基准20日收益率（%）';
ALTER TABLE screen_lock MODIFY COLUMN status            VARCHAR(16)  COMMENT '状态';
ALTER TABLE screen_lock MODIFY COLUMN created_at        VARCHAR(32)  COMMENT '创建时间';
ALTER TABLE screen_lock MODIFY COLUMN updated_at        VARCHAR(32)  COMMENT '更新时间';

-- -----------------------------------------------------------
-- 11. daily_basic 每日基本面表
-- -----------------------------------------------------------
ALTER TABLE daily_basic COMMENT = '每日基本面表（估值/换手率/市值）';
ALTER TABLE daily_basic MODIFY COLUMN trade_date      VARCHAR(8) NOT NULL COMMENT '交易日期（YYYYMMDD）';
ALTER TABLE daily_basic MODIFY COLUMN ts_code         VARCHAR(16) NOT NULL COMMENT '股票代码';
ALTER TABLE daily_basic MODIFY COLUMN close           DECIMAL(20,4) COMMENT '当日收盘价（元）';
ALTER TABLE daily_basic MODIFY COLUMN turnover_rate   DECIMAL(20,4) COMMENT '换手率（%）';
ALTER TABLE daily_basic MODIFY COLUMN turnover_rate_f DECIMAL(20,4) COMMENT '换手率（自由流通股，%）';
ALTER TABLE daily_basic MODIFY COLUMN volume_ratio    DECIMAL(20,4) COMMENT '量比';
ALTER TABLE daily_basic MODIFY COLUMN pe              DECIMAL(20,4) COMMENT '市盈率（总市值/净利润，亏损为空）';
ALTER TABLE daily_basic MODIFY COLUMN pe_ttm          DECIMAL(20,4) COMMENT '市盈率（TTM）';
ALTER TABLE daily_basic MODIFY COLUMN pb              DECIMAL(20,4) COMMENT '市净率（总市值/净资产）';
ALTER TABLE daily_basic MODIFY COLUMN ps              DECIMAL(20,4) COMMENT '市销率';
ALTER TABLE daily_basic MODIFY COLUMN ps_ttm          DECIMAL(20,4) COMMENT '市销率（TTM）';
ALTER TABLE daily_basic MODIFY COLUMN dv_ratio        DECIMAL(20,4) COMMENT '股息率（%）';
ALTER TABLE daily_basic MODIFY COLUMN dv_ttm          DECIMAL(20,4) COMMENT '股息率（TTM，%）';
ALTER TABLE daily_basic MODIFY COLUMN total_share     DECIMAL(20,4) COMMENT '总股本（万股）';
ALTER TABLE daily_basic MODIFY COLUMN float_share     DECIMAL(20,4) COMMENT '流通股本（万股）';
ALTER TABLE daily_basic MODIFY COLUMN free_share      DECIMAL(20,4) COMMENT '自由流通股本（万股）';
ALTER TABLE daily_basic MODIFY COLUMN total_mv        DECIMAL(20,4) COMMENT '总市值（万元）';
ALTER TABLE daily_basic MODIFY COLUMN circ_mv         DECIMAL(20,4) COMMENT '流通市值（万元）';

-- -----------------------------------------------------------
-- 12. fina_indicator 财务指标表
-- -----------------------------------------------------------
ALTER TABLE fina_indicator COMMENT = '财务指标表（ROE/ROA/毛利率/同比/资产负债率等）';
ALTER TABLE fina_indicator MODIFY COLUMN ts_code            VARCHAR(16) NOT NULL COMMENT '股票代码';
ALTER TABLE fina_indicator MODIFY COLUMN end_date           VARCHAR(8) NOT NULL COMMENT '报告期（YYYYMMDD）';
ALTER TABLE fina_indicator MODIFY COLUMN ann_date           VARCHAR(8) COMMENT '公告日期（YYYYMMDD）';
ALTER TABLE fina_indicator MODIFY COLUMN roe                DECIMAL(20,4) COMMENT '净资产收益率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN roa                DECIMAL(20,4) COMMENT '总资产收益率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN grossprofit_margin DECIMAL(20,4) COMMENT '销售毛利率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN netprofit_margin   DECIMAL(20,4) COMMENT '销售净利率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN dt_netprofit_yoy   DECIMAL(20,4) COMMENT '归母净利润同比增长率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN revenue_yoy        DECIMAL(20,4) COMMENT '营业收入同比增长率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN debt_to_assets     DECIMAL(20,4) COMMENT '资产负债率（%）';
ALTER TABLE fina_indicator MODIFY COLUMN eps_yoy            DECIMAL(20,4) COMMENT '基本每股收益同比增长率（%）';

-- -----------------------------------------------------------
-- 13. factor_snapshot 因子预计算快照表
-- -----------------------------------------------------------
ALTER TABLE factor_snapshot COMMENT = '因子预计算快照表';
ALTER TABLE factor_snapshot MODIFY COLUMN trade_date   VARCHAR(8) NOT NULL COMMENT '交易日期（YYYYMMDD）';
ALTER TABLE factor_snapshot MODIFY COLUMN ts_code      VARCHAR(16) NOT NULL COMMENT '股票代码';
ALTER TABLE factor_snapshot MODIFY COLUMN factor_key   VARCHAR(32) NOT NULL COMMENT '因子标识（如 MA/MACD/RSI）';
ALTER TABLE factor_snapshot MODIFY COLUMN params_json  VARCHAR(128) NOT NULL DEFAULT '{}' COMMENT '因子参数（JSON，如 {"timeperiod":5}）';
ALTER TABLE factor_snapshot MODIFY COLUMN output_index INT NOT NULL DEFAULT 0 COMMENT '多输出因子结果索引（0=主输出）';
ALTER TABLE factor_snapshot MODIFY COLUMN factor_value DECIMAL(20,6) COMMENT '因子值';
ALTER TABLE factor_snapshot MODIFY COLUMN updated_at   VARCHAR(32) COMMENT '更新时间';
