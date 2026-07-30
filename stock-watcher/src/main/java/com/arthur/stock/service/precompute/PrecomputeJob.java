package com.arthur.stock.service.precompute;

/**
 * 预计算 Job 接口。
 * <p>
 * 所有 Job <b>订阅同一个 {@link com.arthur.stock.event.DataBatchReadyEvent}</b>，
 * 在事件触发时各自执行 {@link #precompute(String)}。
 * <p>
 * <b>多表依赖关系</b>：本接口<b>不</b>暴露 {@code dependsOnTables()} 方法——
 * 表级依赖只在各个 Job 的 Javadoc 中注明供人工查阅，<b>不参与运行时路由</b>。
 * 运行时各 Job 独立执行、互不阻塞，避免依赖编排带来的复杂度与脆性；
 * Job 内部对所需表的读取异常自行降级（重试/跳过/evict 缓存）。
 *
 * @see com.arthur.stock.event.DataBatchReadyEvent
 * @see AbstractPrecomputeJob
 */
public interface PrecomputeJob {

    /**
     * Job 名称，用于 {@code PrecomputeService.precomputeNow(jobName, ...)} 路由
     * 与日志/去重 key 构造。建议全局唯一且稳定（如 {@code "sectorRanking"}）。
     */
    String name();

    /**
     * 对指定交易日执行预计算（通常为：读取多张表 → 计算聚合 → 写入缓存双 key）。
     *
     * @param tradeDate YYYYMMDD；为 null/空时由实现自行决定是否降级为 latest
     */
    void precompute(String tradeDate);
}
