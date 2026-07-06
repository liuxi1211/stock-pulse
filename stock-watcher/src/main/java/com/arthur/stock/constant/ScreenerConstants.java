package com.arthur.stock.constant;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * 选股中心业务常量集中类（spec 003 阶段 2）。
 * <p>
 * 收纳 ScreenerServiceImpl 中原本散落的基准代码、候选规模、收益精度、
 * 涨跌停阈值、ST 关键字、日期格式等字面量。
 * 常量组（状态/枚举值）请放 {@code *Enum}。
 */
public final class ScreenerConstants {

    private ScreenerConstants() {
    }

    /** 基准指数代码（沪深 300）。简化：直接查 daily_quote，若无指数日线则基准降级为 null。 */
    public static final String BENCHMARK_CODE = "000300.SH";

    /** OHLCV 历史窗口长度（根）。简化为固定值；后续可从 conditions 推断。 */
    public static final int SCREEN_HISTORY_BARS = 60;

    /** 单次 snapshot 候选股上限，避免 HTTP 超时。后续支持分批/异步。 */
    public static final int SCREEN_MAX_CANDIDATES = 500;

    /** 手动指定（manual）候选池的更紧上限：用户挑选的自选股集合，典型场景 ≤50，控制 HTTP 体积和 engine 计算量。 */
    public static final int SCREEN_MANUAL_MAX_CANDIDATES = 50;

    /** 追踪周期（交易日）。注意：switch case 分支与此数组同源。 */
    public static final int[] TRACKING_PERIODS = {5, 10, 20};

    /** 条件树最大递归深度，防止恶意深层嵌套导致栈溢出。 */
    public static final int MAX_CONDITION_DEPTH = 32;

    /** 收益计算中间除法小数位（足够精度避免截断误差）。 */
    public static final int INTERMEDIATE_SCALE = 10;

    /** 收益率最终小数位。 */
    public static final int RETURN_SCALE = 6;

    /** 涨停阈值（主板简化值，创业板/科创板 20%、ST 5% 的精确化留待 Phase 2）。 */
    public static final BigDecimal LIMIT_UP_PCT = new BigDecimal("9.9");

    /** 跌停阈值（主板简化值，负数存储）。 */
    public static final BigDecimal LIMIT_DOWN_PCT = new BigDecimal("-9.9");

    /** ST 股票名称关键字。 */
    public static final String ST_KEYWORD = "ST";

    /** 空股票列表 JSON 占位。 */
    public static final String EMPTY_STOCKS_JSON = "[]";

    /** 紧凑日期长度（yyyyMMdd）。 */
    public static final int COMPACT_DATE_LENGTH = 8;

    /** ISO 日期长度（yyyy-MM-dd）。 */
    public static final int ISO_DATE_LENGTH = 10;

    /** ISO 日期格式（yyyy-MM-dd）。DateTimeFormatter 线程安全，可全局共享。 */
    public static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 紧凑日期格式（yyyyMMdd）。 */
    public static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
}
