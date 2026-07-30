package com.arthur.stock.util;

/**
 * 缓存 key 解析工具：供 {@code @Cacheable} 的 SpEL 与 PrecomputeJob 显式 put 共用，
 * 保证两侧 key 一致 + latest 双写口径统一。
 * <p>
 * <b>使用约定</b>：
 * <ul>
 *   <li>PrecomputeJob 在 {@code doPrecompute} 内 put 两个 key：{@code tradeDate} 与 {@code "latest"}</li>
 *   <li>{@code @Cacheable} 用 SpEL 引用本类方法，如：
 *       <pre>{@code
 *       @Cacheable(value = "sectorRanking",
 *                  key = "@com.arthur.stock.util.CacheKeyResolver.resolveSectorKey(#tradeDate)")
 *       }</pre>
 *   </li>
 * </ul>
 */
public final class CacheKeyResolver {

    private CacheKeyResolver() {
    }

    /**
     * 板块类缓存 key：tradeDate 非空返回 tradeDate，否则返回 {@code "latest"}。
     * <p>
     * 用于 sectorRanking / sectorMoneyflow / sectorValuation 等板块缓存的双写 key 解析。
     */
    public static String resolveSectorKey(String tradeDate) {
        return (tradeDate != null && !tradeDate.isBlank()) ? tradeDate : "latest";
    }

    /**
     * latest 通道 key：latestTradeDate 非空返回其本身，否则返回 {@code "empty"}。
     * <p>
     * 用于 tradeDate 未解析出时的兜底（避免与 {@code "latest"} 混淆，便于区分「最新」与「无数据」语义）。
     */
    public static String resolveLatestKey(String latestTradeDate) {
        return latestTradeDate != null ? latestTradeDate : "empty";
    }

    /**
     * 资金流排行缓存 key：{@code {tradeDate|latest}_{limit}_{sortBy}_{order}}。
     * <p>
     * 多参数组合 key，保证不同 limit/sortBy/order 缓存互不覆盖。
     */
    public static String resolveMoneyflowRankingKey(String tradeDate, Integer limit, String sortBy, String order) {
        String prefix = (tradeDate != null && !tradeDate.isBlank()) ? tradeDate : "latest";
        return prefix + "_" + limit + "_" + sortBy + "_" + order;
    }
}
