package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.vo.IndustryRankingVO;
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
 * 板块排行预计算 Job 测试（spec 026 Phase D5 + Task F1）。
 * <p>
 * 用真实 {@link CaffeineCacheManager}（内存缓存，无外部依赖），mock {@link SwIndustryService}
 * 与 {@link DailyQuoteMapper}，验证缓存双写（tradeDate + latest）、异常 evict 与数据完整性校验跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SectorRankingPrecomputeJobTest {

    @Mock
    private SwIndustryService swIndustryService;

    @Mock
    private DailyQuoteMapper dailyQuoteMapper;

    private CacheManager cacheManager;
    private SectorRankingPrecomputeJob job;

    @BeforeEach
    void setUp() {
        cacheManager = new CaffeineCacheManager();
        job = new SectorRankingPrecomputeJob(cacheManager, swIndustryService, dailyQuoteMapper);
        // 默认：数据就绪（daily_quote 当日有记录）
        when(dailyQuoteMapper.selectCount(any())).thenReturn(1L);
    }

    // ==================== D5.1 ====================

    @Test
    void D5_1_precompute后缓存双写tradeDate和latest() {
        List<IndustryRankingVO> result = List.of(
                IndustryRankingVO.builder().industryCode("801010.SI").build());
        when(swIndustryService.computeIndustryRanking(eq("20260729"))).thenReturn(result);

        job.precompute("20260729");

        Cache cache = cacheManager.getCache("sectorRanking");
        assertThat(cache).isNotNull();

        // tradeDate 精确 key
        Cache.ValueWrapper dateValue = cache.get("20260729");
        assertThat(dateValue).isNotNull();
        assertThat(dateValue.get()).isEqualTo(result);

        // latest key
        Cache.ValueWrapper latestValue = cache.get("latest");
        assertThat(latestValue).isNotNull();
        assertThat(latestValue.get()).isEqualTo(result);
    }

    // ==================== D5.2 ====================

    @Test
    void D5_2_service抛异常时evict缓存tradeDate和latest() {
        Cache cache = cacheManager.getCache("sectorRanking");
        // 预置旧缓存（模拟历史成功缓存残留）
        cache.put("20260729", List.of(IndustryRankingVO.builder().build()));
        cache.put("latest", List.of(IndustryRankingVO.builder().build()));
        assertThat(cache.get("20260729")).isNotNull();
        assertThat(cache.get("latest")).isNotNull();

        // service 抛异常 → 模板 evict cacheKeys 返回的所有 key
        when(swIndustryService.computeIndustryRanking(eq("20260729")))
                .thenThrow(new RuntimeException("compute failed"));

        job.precompute("20260729");

        // 两个 key 都被 evict
        assertThat(cache.get("20260729")).isNull();
        assertThat(cache.get("latest")).isNull();
    }

    // ==================== Task F1：数据完整性校验 ====================

    @Test
    void F1_数据不完整时跳过预计算且不evict不写缓存() {
        Cache cache = cacheManager.getCache("sectorRanking");
        // 预置旧缓存（验证不会被 evict）
        List<IndustryRankingVO> oldResult = List.of(IndustryRankingVO.builder().build());
        cache.put("20260729", oldResult);
        cache.put("latest", oldResult);

        // daily_quote 当日无数据 → isDataReady 返回 false
        when(dailyQuoteMapper.selectCount(any())).thenReturn(0L);

        job.precompute("20260729");

        // doPrecompute 未被调用
        verify(swIndustryService, never()).computeIndustryRanking(any());
        // 旧缓存保留（未 evict）
        assertThat(cache.get("20260729")).isNotNull();
        assertThat(cache.get("20260729").get()).isEqualTo(oldResult);
        assertThat(cache.get("latest")).isNotNull();
        assertThat(cache.get("latest").get()).isEqualTo(oldResult);
    }

    @Test
    void F1_tradeDate为空时跳过预计算() {
        when(dailyQuoteMapper.selectCount(any())).thenReturn(1L);

        job.precompute("");

        // doPrecompute 未被调用
        verify(swIndustryService, never()).computeIndustryRanking(any());
        // selectCount 也未被调用（tradeDate 空字符串短路返回）
        verify(dailyQuoteMapper, never()).selectCount(any());
    }
}
