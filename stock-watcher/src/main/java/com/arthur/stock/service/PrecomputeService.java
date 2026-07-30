package com.arthur.stock.service;

/**
 * 预计算服务：手动触发预计算的入口（HTTP/管理后台/运维补跑用）。
 * <p>
 * 自动触发由各 {@link com.arthur.stock.service.precompute.PrecomputeJob} 订阅
 * {@link com.arthur.stock.event.DataBatchReadyEvent} 完成；本接口仅用于运维补跑或手动重算。
 */
public interface PrecomputeService {

    /**
     * 立即执行单个 Job（同步阻塞，等执行完返回）。
     *
     * @param jobName   Job 名称（{@link com.arthur.stock.service.precompute.PrecomputeJob#name()}）
     * @param tradeDate 交易日 YYYYMMDD
     * @throws IllegalArgumentException 找不到对应 jobName 时
     */
    void precomputeNow(String jobName, String tradeDate);

    /**
     * 立即执行所有 Job（逐个同步执行，单个失败不中断后续）。
     *
     * @param tradeDate 交易日 YYYYMMDD
     */
    void precomputeAll(String tradeDate);
}
