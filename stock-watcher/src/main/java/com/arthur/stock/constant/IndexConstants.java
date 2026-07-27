package com.arthur.stock.constant;

import java.util.List;
import java.util.Map;

/**
 * 指数相关公共常量。
 * <p>
 * 供 MarketService / IndexDailyFetchService / IndexWeightService 等多处复用，
 * 避免指数代码与名称映射散落重复定义。
 */
public final class IndexConstants {

    private IndexConstants() {}

    /**
     * 默认展示的大盘指数代码（仪表盘/行情页首屏展示）
     * 包含：上证指数、深证成指、创业板指、科创50
     */
    public static final List<String> DEFAULT_INDEX_CODES = List.of(
            "000001.SH", "399001.SZ", "399006.SZ", "000688.SH");

    /**
     * 核心宽基指数代码列表（用于 index_daily 数据管控完整性检测）
     * 覆盖市场主流宽基指数，用于数据质量检测的核心指数集合。
     */
    public static final List<String> CORE_BROAD_INDEX_CODES = List.of(
            "000001.SH",
            "399001.SZ",
            "399006.SZ",
            "000688.SH",
            "000300.SH",
            "000905.SH",
            "000852.SH",
            "000016.SH",
            "000903.SH",
            "399330.SZ",
            "399673.SZ",
            "000010.SH"
    );

    /**
     * 指数成分股权重同步的指数列表（用于 index_weight 定时同步与数据管控）
     * 选择市场常用且具有代表性的宽基指数：
     * <ul>
     *   <li>沪深300 - 大盘蓝筹代表</li>
     *   <li>中证500 - 中盘代表</li>
     *   <li>中证1000 - 小盘代表</li>
     *   <li>上证50 - 超大盘代表</li>
     *   <li>中证100 - 大盘核心资产</li>
     *   <li>创业板50 - 创业板龙头</li>
     *   <li>科创50 - 科创板龙头</li>
     *   <li>上证180 - 沪市核心蓝筹</li>
     * </ul>
     */
    public static final List<String> INDEX_WEIGHT_CODES = List.of(
            "000300.SH",
            "000905.SH",
            "000852.SH",
            "000016.SH",
            "000903.SH",
            "399673.SZ",
            "000688.SH",
            "000010.SH"
    );

    /**
     * 回测基准指数白名单（code -> name）
     * 提供给前端回测配置时选择的基准指数列表。
     */
    public static final List<Map<String, String>> BENCHMARK_WHITELIST = List.of(
            Map.of("code", "000300.SH", "name", "沪深300"),
            Map.of("code", "000905.SH", "name", "中证500"),
            Map.of("code", "000852.SH", "name", "中证1000"),
            Map.of("code", "000016.SH", "name", "上证50"),
            Map.of("code", "000903.SH", "name", "中证100"),
            Map.of("code", "399673.SZ", "name", "创业板50"),
            Map.of("code", "000688.SH", "name", "科创50"),
            Map.of("code", "000010.SH", "name", "上证180"),
            Map.of("code", "399006.SZ", "name", "创业板指"),
            Map.of("code", "000001.SH", "name", "上证指数"),
            Map.of("code", "399001.SZ", "name", "深证成指")
    );

    /** 指数代码 -> 中文名称映射（全覆盖） */
    public static final Map<String, String> INDEX_NAME_MAP = Map.ofEntries(
            Map.entry("000001.SH", "上证指数"),
            Map.entry("399001.SZ", "深证成指"),
            Map.entry("399006.SZ", "创业板指"),
            Map.entry("000688.SH", "科创50"),
            Map.entry("000300.SH", "沪深300"),
            Map.entry("000905.SH", "中证500"),
            Map.entry("000852.SH", "中证1000"),
            Map.entry("000016.SH", "上证50"),
            Map.entry("000903.SH", "中证100"),
            Map.entry("399330.SZ", "深证100"),
            Map.entry("399673.SZ", "创业板50"),
            Map.entry("000010.SH", "上证180")
    );
}
