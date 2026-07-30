package com.arthur.stock.service.precompute;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PrecomputeJob 模板基类：统一处理「同 (job, tradeDate) 去重 / 数据完整性校验 / 耗时统计 / 失败 evict 缓存」。
 * <p>
 * 子类需实现 {@link #name()}、{@link #isDataReady(String)}、{@link #doPrecompute(String)}、
 * {@link #cacheName()}、{@link #cacheKeys(String)}。
 * {@link #precompute(String)} 被 final 化，子类不应覆盖模板流程。
 * <p>
 * <b>缓存双写约定</b>：子类在 {@link #doPrecompute} 内部对每个缓存项 put <b>两个 key</b>：
 * <ul>
 *   <li>{@code tradeDate}（如 "20260729"）—— 历史日期精确查询</li>
 *   <li>{@code "latest"} —— 最新交易日查询</li>
 * </ul>
 * 由 {@link com.arthur.stock.util.CacheKeyResolver} 统一生成 key，保证 {@code @Cacheable} SpEL 与显式 put 一致。
 * <p>
 * <b>数据完整性校验（spec 026 Task F1 / B6.4）</b>：模板在 {@link #doPrecompute} 调用前先调
 * {@link #isDataReady(String)}，返回 false 时打 WARN 日志并直接 return（不调 doPrecompute、不写缓存、
 * <b>不 evict</b>——数据未就绪并非 Job 异常，不应清掉昨日有效缓存）。
 * 校验对所有 source 生效（SCHEDULED/SCHEDULED_PARTIAL/SCHEDULED_TIMEOUT/MANUAL），
 * 校验开销仅为一条 count SQL，统一校验更安全，并可避免节假日空数据被缓存为"latest"。
 * <p>
 * <b>失败 evict</b>：{@code doPrecompute} 抛异常时，主动 evict {@link #cacheKeys(String)} 返回的所有 key，
 * 避免半成品缓存被后续读取误用。
 */
@Slf4j
public abstract class AbstractPrecomputeJob implements PrecomputeJob {

    /** Spring CacheManager，用于失败时 evict 缓存。 */
    protected final CacheManager cacheManager;

    /** 去重表：key = jobName + ":" + tradeDate，value = true 表示正在执行。 */
    private final ConcurrentHashMap<String, Boolean> runningJobs = new ConcurrentHashMap<>();

    protected AbstractPrecomputeJob(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 模板方法，final 化防止子类破坏去重/校验/耗时/evict 流程。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查 (jobName, tradeDate) 是否正在执行（{@link #runningJobs} 原子去重）</li>
     *   <li>若正在执行，打 INFO 日志并 return</li>
     *   <li>标记开始、记录 startTime</li>
     *   <li>调用 {@link #isDataReady(String)} 做数据完整性校验；返回 false 时打 WARN 并 return
     *       （不调 doPrecompute、不写缓存、不 evict）</li>
     *   <li>调用 {@link #doPrecompute}；异常时打 ERROR + evict 缓存</li>
     *   <li>finally：记录耗时 + 清除去重标记</li>
     * </ol>
     */
    @Override
    public final void precompute(String tradeDate) {
        String jobName = name();
        String dedupKey = jobName + ":" + tradeDate;

        // 1+2+3 原子去重：putIfAbsent 返回 null 表示之前不存在（已标记），非 null 表示已在执行
        if (runningJobs.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            log.info("[Precompute][{}] tradeDate={} 已在执行，跳过", jobName, tradeDate);
            return;
        }

        long startTime = System.currentTimeMillis();
        boolean success = false;
        boolean skipped = false;
        try {
            // spec 026 Task F1 / B6.4：数据完整性校验。失败时跳过整个预计算（不 evict）
            if (!isDataReady(tradeDate)) {
                log.warn("[Precompute][{}] tradeDate={} 数据不完整，跳过预计算", jobName, tradeDate);
                skipped = true;
                return;
            }
            doPrecompute(tradeDate);
            success = true;
        } catch (Exception e) {
            log.error("[Precompute][{}] tradeDate={} 预计算失败: {}", jobName, tradeDate, e.getMessage(), e);
            evictCacheKeys(tradeDate);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            String resultLabel = skipped ? "skip" : (success ? "success" : "fail");
            log.info("[Precompute][{}] tradeDate={} 耗时={}ms 结果={}",
                    jobName, tradeDate, elapsed, resultLabel);
            runningJobs.remove(dedupKey);
        }
    }

    /**
     * 数据完整性校验（spec 026 Task F1 / B6.4）：子类检查 tradeDate 当日依赖的核心表是否有数据。
     * <p>
     * 模板在 {@link #doPrecompute} 调用前先调用本方法。返回 false 时模板打 WARN 日志并跳过预计算
     * （不调 doPrecompute、不写缓存、<b>不 evict</b>）。校验失败应尽快返回，避免阻塞其他 Job。
     * <p>
     * <b>实现建议</b>：仅校验最关键的一张表（如 daily_quote 当日 distinct ts_code 数 &gt; 0），
     * 一条 count SQL 即可；多表依赖的细粒度校验由 doPrecompute 内部读取异常兜底（触发 evict）。
     *
     * @param tradeDate YYYYMMDD
     * @return true 表示数据就绪，可以执行 doPrecompute；false 表示数据未就绪，跳过预计算
     */
    protected abstract boolean isDataReady(String tradeDate);

    /**
     * 子类实现具体预计算逻辑：读多表 → 聚合 → 缓存双写（tradeDate + "latest"）。
     *
     * @param tradeDate YYYYMMDD
     * @throws Exception 任意异常均会被模板捕获并触发缓存 evict
     */
    protected abstract void doPrecompute(String tradeDate) throws Exception;

    /**
     * 该 Job 写入的 Spring 缓存名（需在 {@link com.arthur.stock.config.CacheConfig} 注册）。
     */
    protected abstract String cacheName();

    /**
     * 该 Job 在缓存中写入的 key 列表，失败时需全部 evict。
     * 通常返回 {@code [tradeDate, "latest"]}（tradeDate 为空时由子类处理为 "latest"）。
     */
    protected abstract List<String> cacheKeys(String tradeDate);

    /** 失败时 evict {@link #cacheKeys(String)} 返回的所有 key，避免半成品缓存残留。 */
    private void evictCacheKeys(String tradeDate) {
        String cacheName = cacheName();
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.warn("[Precompute][{}] 缓存 {} 不存在，跳过 evict", name(), cacheName);
                return;
            }
            List<String> keys = cacheKeys(tradeDate);
            if (keys == null || keys.isEmpty()) {
                return;
            }
            for (String key : keys) {
                cache.evict(key);
            }
            log.info("[Precompute][{}] tradeDate={} 已 evict 缓存 {} keys={}", name(), tradeDate, cacheName, keys);
        } catch (Exception ex) {
            log.warn("[Precompute][{}] tradeDate={} evict 缓存 {} 失败: {}",
                    name(), tradeDate, cacheName, ex.getMessage());
        }
    }
}
