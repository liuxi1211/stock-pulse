package com.arthur.stock.constant;

import java.util.List;
import java.util.Map;

/**
 * 指数相关公共常量。
 * <p>
 * 供 MarketService / IndexDailyFetchService 等多处复用，避免大盘指数代码与名称映射散落重复定义。
 */
public final class IndexConstants {

    private IndexConstants() {}

    /** 默认展示的大盘指数代码（上证 / 深证 / 创业板 / 科创50） */
    public static final List<String> DEFAULT_INDEX_CODES = List.of(
            "000001.SH", "399001.SZ", "399006.SZ", "000688.SH");

    /** 指数代码 -> 中文名称映射 */
    public static final Map<String, String> INDEX_NAME_MAP = Map.of(
            "000001.SH", "上证指数",
            "399001.SZ", "深证成指",
            "399006.SZ", "创业板指",
            "000688.SH", "科创50");
}
