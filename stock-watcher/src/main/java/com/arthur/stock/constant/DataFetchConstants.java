package com.arthur.stock.constant;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 数据拉取相关常量：批次聚合 taskKey、时区、日期时间格式、默认操作人。
 *
 * <p>本类仅放数据拉取域的通用字面量；批次标识/任务名/cron 见 {@link DataFetchBatchEnum}。</p>
 */
public final class DataFetchConstants {

    private DataFetchConstants() {}

    /** 数据拉取使用的时区（A 股交易以东八区为准） */
    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 紧凑日期格式（yyyyMMdd），用于 trade_date */
    public static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 标准日期时间格式（yyyy-MM-dd HH:mm:ss），用于 data_pull_log 的 start_time/end_time */
    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 定时任务默认操作人 */
    public static final String OPERATOR_SYSTEM = "SYSTEM";

    // ===== 批次聚合 taskKey（DataBatchCompletionTracker 收齐 4 个核心任务后发 DataBatchReadyEvent） =====

    /** 16:00 批次聚合 taskKey */
    public static final String TRACKER_BATCH_1600 = "BATCH_1600";
    /** 16:30 批次-daily_basic 聚合 taskKey */
    public static final String TRACKER_BATCH_1630_DAILY_BASIC = "BATCH_1630_DAILY_BASIC";
    /** 16:30 批次-资金流聚合 taskKey */
    public static final String TRACKER_BATCH_1630_MONEYFLOW = "BATCH_1630_MONEYFLOW";
    /** 16:30 批次-指数日线聚合 taskKey */
    public static final String TRACKER_BATCH_1630_INDEX_DAILY = "BATCH_1630_INDEX_DAILY";

    /** error_message 最大长度（截断保护，对应 DB 列 VARCHAR(1024)） */
    public static final int ERROR_MESSAGE_MAX_LEN = 1024;
}
