package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.MoneyflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个股资金流排行预计算 Job 测试（spec 026 Phase D6 + Task F1）。
 * <p>
 * 固定参数：limit=10, sortBy="main_net", order="desc"。
 * 用真实 {@link CaffeineCacheManager}，mock {@link MoneyflowService} 与 {@link MoneyflowMapper}，
 * 验证缓存双写、异常 evict 与数据完整性校验跳过。
 * <p>
 * 期望缓存 key：
 * <ul>
 *   <li>{@code 20260729_10_main_net_desc}（tradeDate 精确）</li>
 *   <li>{@code latest_10_main_net_desc}（最新通道）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MoneyflowRankingPrecomputeJobTest {

    @Mock
    private MoneyflowService moneyflowService;

    @Mock
    private MoneyflowMapper moneyflowMapper;

    private CacheManager cacheManager;
    private MoneyflowRankingPrecomputeJob job;

    @BeforeEach
    void setUp() {
        cacheManager = new CaffeineCacheManager();
        job = new MoneyflowRankingPrecomputeJob(cacheManager, moneyflowService, moneyflowMapper);
        // 默认：数据就绪（stock_moneyflow 当日有记录）
        when(moneyflowMapper.selectCount(any())).thenReturn(1L);
    }

    // ==================== D6.1 ====================

    @Test
    void D6_1_precompute后固定参数缓存双写() {
        List<MoneyflowDO> result = List.of(new MoneyflowDO());
        // 固定参数：limit=10, sortBy="main_net", order="desc"
        when(moneyflowService.computeQueryTop(eq("20260729"), eq(10), eq("main_net"), eq("desc")))
                .thenReturn(result);

        job.precompute("20260729");

        Cache cache = cacheManager.getCache("moneyflowRanking");
        assertThat(cache).isNotNull();

        // tradeDate 精确 key：{tradeDate}_{limit}_{sortBy}_{order}
        Cache.ValueWrapper dateValue = cache.get("20260729_10_main_net_desc");
        assertThat(dateValue).isNotNull();
        assertThat(dateValue.get()).isEqualTo(result);

        // latest 通道 key：latest_{limit}_{sortBy}_{order}
        Cache.ValueWrapper latestValue = cache.get("latest_10_main_net_desc");
        assertThat(latestValue).isNotNull();
        assertThat(latestValue.get()).isEqualTo(result);
    }

    // ==================== D6.2：异常 evict ====================

    @Test
    void D6_2_service抛异常时evict固定参数缓存() {
        Cache cache = cacheManager.getCache("moneyflowRanking");
        // 预置旧缓存
        cache.put("20260729_10_main_net_desc", List.of(new MoneyflowDO()));
        cache.put("latest_10_main_net_desc", List.of(new MoneyflowDO()));
        assertThat(cache.get("20260729_10_main_net_desc")).isNotNull();
        assertThat(cache.get("latest_10_main_net_desc")).isNotNull();

        when(moneyflowService.computeQueryTop(eq("20260729"), eq(10), eq("main_net"), eq("desc")))
                .thenThrow(new RuntimeException("compute failed"));

        job.precompute("20260729");

        // 两个固定参数 key 都被 evict
        assertThat(cache.get("20260729_10_main_net_desc")).isNull();
        assertThat(cache.get("latest_10_main_net_desc")).isNull();
    }

    // ==================== Task F1：数据完整性校验 ====================

    @Test
    void F1_数据不完整时跳过预计算且不evict不写缓存() {
        Cache cache = cacheManager.getCache("moneyflowRanking");
        // 预置旧缓存（验证不会被 evict）
        List<MoneyflowDO> oldResult = List.of(new MoneyflowDO());
        cache.put("20260729_10_main_net_desc", oldResult);
        cache.put("latest_10_main_net_desc", oldResult);

        // stock_moneyflow 当日无数据 → isDataReady 返回 false
        when(moneyflowMapper.selectCount(any())).thenReturn(0L);

        job.precompute("20260729");

        // doPrecompute 未被调用
        verify(moneyflowService, never()).computeQueryTop(any(), eq(10), eq("main_net"), eq("desc"));
        // 旧缓存保留（未 evict）
        assertThat(cache.get("20260729_10_main_net_desc")).isNotNull();
        assertThat(cache.get("20260729_10_main_net_desc").get()).isEqualTo(oldResult);
        assertThat(cache.get("latest_10_main_net_desc")).isNotNull();
        assertThat(cache.get("latest_10_main_net_desc").get()).isEqualTo(oldResult);
    }
}
