package com.arthur.stock.service.precompute;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.mapper.SwIndustryMemberMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.model.SwIndustryDO;
import com.arthur.stock.service.IndexDailyService;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.service.impl.MarketServiceImpl;
import com.arthur.stock.service.impl.MoneyflowServiceImpl;
import com.arthur.stock.service.impl.SwIndustryServiceImpl;
import com.arthur.stock.util.StockDataHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 懒兜底验证测试（spec 026 Phase D7）。
 * <p>
 * 对 7 个 PrecomputeJob 对应的 Service {@code @Cacheable} 接口做懒兜底验证：
 * <ol>
 *   <li>清空缓存；</li>
 *   <li>调 Service.getXXX() → @Cacheable cache miss → computeXXX 执行 → 结果写缓存；</li>
 *   <li>验证缓存已写入；</li>
 *   <li>第二次调同样方法 → @Cacheable cache hit → computeXXX 不再执行；</li>
 *   <li>验证底层 mapper 调用次数仍为 1（缓存命中，未重复计算）。</li>
 * </ol>
 * <p>
 * 用最小化 Spring 上下文（{@code @EnableCaching} + Caffeine + mock mapper/service 依赖），
 * 不启动完整 {@code @SpringBootTest}，不连 DB。Service impl 由 Spring 创建代理，
 * {@code @Cacheable} 注解生效。
 * <p>
 * 7 个 cacheName：sectorRanking / sectorMoneyflow / sectorValuation /
 * indices / marketRanking / marketTemperature / moneyflowRanking。
 */
@ExtendWith(SpringExtension.class)
@org.springframework.test.context.ContextConfiguration(
        classes = LazyFallbackTest.TestConfig.class)
class LazyFallbackTest {

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new CaffeineCacheManager();
        }

        // ---- Mock mappers ----
        @Bean SwIndustryMapper swIndustryMapper() { return org.mockito.Mockito.mock(SwIndustryMapper.class); }
        @Bean SwIndustryMemberMapper swIndustryMemberMapper() { return org.mockito.Mockito.mock(SwIndustryMemberMapper.class); }
        @Bean DailyQuoteMapper dailyQuoteMapper() { return org.mockito.Mockito.mock(DailyQuoteMapper.class); }
        @Bean StockBasicMapper stockBasicMapper() { return org.mockito.Mockito.mock(StockBasicMapper.class); }
        @Bean IndexDailyMapper indexDailyMapper() { return org.mockito.Mockito.mock(IndexDailyMapper.class); }
        @Bean MoneyflowMapper moneyflowMapper() { return org.mockito.Mockito.mock(MoneyflowMapper.class); }

        // ---- Mock service 依赖 ----
        @Bean IndexDailyService indexDailyService() { return org.mockito.Mockito.mock(IndexDailyService.class); }
        @Bean TushareClient tushareClient() { return org.mockito.Mockito.mock(TushareClient.class); }
        @Bean StockDataHelper stockDataHelper() { return org.mockito.Mockito.mock(StockDataHelper.class); }
        @Bean TransactionTemplate transactionTemplate() { return org.mockito.Mockito.mock(TransactionTemplate.class); }

        // ---- Service impl（Spring 创建 @Cacheable 代理）----
        @Bean SwIndustryService swIndustryService(SwIndustryMapper m, SwIndustryMemberMapper mm,
                                                   DailyQuoteMapper dq, IndexDailyService ids,
                                                   StockBasicMapper sb, TushareClient tc) {
            return new SwIndustryServiceImpl(tc, m, mm, dq, ids, sb);
        }
        @Bean MarketService marketService(DailyQuoteMapper dq, StockDataHelper sh,
                                          IndexDailyMapper im, IndexDailyService ids) {
            return new MarketServiceImpl(dq, sh, im, ids);
        }
        @Bean MoneyflowService moneyflowService(MoneyflowMapper m, TushareClient tc,
                                                 TransactionTemplate tt) {
            return new MoneyflowServiceImpl(m, tc, tt);
        }
    }

    @Autowired private SwIndustryService swIndustryService;
    @Autowired private MarketService marketService;
    @Autowired private MoneyflowService moneyflowService;
    @Autowired private CacheManager cacheManager;

    @Autowired private SwIndustryMapper swIndustryMapper;
    @Autowired private SwIndustryMemberMapper swIndustryMemberMapper;
    @Autowired private DailyQuoteMapper dailyQuoteMapper;
    @Autowired private IndexDailyMapper indexDailyMapper;
    @Autowired private IndexDailyService indexDailyService;
    @Autowired private MoneyflowMapper moneyflowMapper;

    private static final String TRADE_DATE = "20260729";

    @BeforeEach
    void setUp() {
        reset(swIndustryMapper, swIndustryMemberMapper, dailyQuoteMapper,
                indexDailyMapper, indexDailyService, moneyflowMapper);
        cacheManager.getCacheNames().forEach(name -> {
            Cache c = cacheManager.getCache(name);
            if (c != null) c.clear();
        });
    }

    /** 构造一个非空行业列表，使 listByLevel 返回非空（保证 compute 结果非空、unless 不生效） */
    private void mockIndustries() {
        when(swIndustryMapper.selectByLevel(eq(1), anyString()))
                .thenReturn(List.of(SwIndustryDO.builder()
                        .indexCode("801010.SI")
                        .indexName("农林牧渔")
                        .level(1)
                        .src("SW2021")
                        .build()));
        // 成员返回空 → buildStockBasicMap / buildStockQuoteMap 不被调用
        lenient().when(swIndustryMemberMapper.selectAllCurrentL1Members(anyString()))
                .thenReturn(Collections.emptyList());
        lenient().when(indexDailyService.getByCodesAndTradeDate(any(), eq(TRADE_DATE)))
                .thenReturn(Collections.emptyList());
        lenient().when(indexDailyService.getByCodeOrderByTradeDate(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    // ==================== D7.1 sectorRanking ====================

    @Test
    void D7_1_sectorRanking_懒兜底缓存命中() {
        mockIndustries();

        // 1. 清空缓存
        Cache cache = cacheManager.getCache("sectorRanking");
        assertThat(cache).isNotNull();
        cache.clear();

        // 2. 第一次调用 → cache miss → compute 执行 → 写缓存
        var result1 = swIndustryService.getIndustryRanking(TRADE_DATE);
        assertThat(result1).isNotEmpty();
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());

        // 3. 验证缓存已写入（key = tradeDate）
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        // 4. 第二次调用 → cache hit → compute 不执行
        var result2 = swIndustryService.getIndustryRanking(TRADE_DATE);
        assertThat(result2).isEqualTo(result1);

        // 5. mapper 仍只被调用 1 次（缓存命中）
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());
    }

    // ==================== D7.2 sectorMoneyflow ====================

    @Test
    void D7_2_sectorMoneyflow_懒兜底缓存命中() {
        mockIndustries();
        lenient().when(swIndustryMemberMapper.selectMoneyflowGroupByIndustry(eq(TRADE_DATE)))
                .thenReturn(Collections.emptyList());

        Cache cache = cacheManager.getCache("sectorMoneyflow");
        assertThat(cache).isNotNull();
        cache.clear();

        var result1 = swIndustryService.getIndustryMoneyflow(TRADE_DATE);
        assertThat(result1).isNotEmpty();
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        var result2 = swIndustryService.getIndustryMoneyflow(TRADE_DATE);
        assertThat(result2).isEqualTo(result1);
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());
    }

    // ==================== D7.3 sectorValuation ====================

    @Test
    void D7_3_sectorValuation_懒兜底缓存命中() {
        mockIndustries();
        lenient().when(swIndustryMemberMapper.selectValuationGroupByIndustry(eq(TRADE_DATE)))
                .thenReturn(Collections.emptyList());

        Cache cache = cacheManager.getCache("sectorValuation");
        assertThat(cache).isNotNull();
        cache.clear();

        var result1 = swIndustryService.getIndustryValuation(TRADE_DATE);
        assertThat(result1).isNotEmpty();
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        var result2 = swIndustryService.getIndustryValuation(TRADE_DATE);
        assertThat(result2).isEqualTo(result1);
        verify(swIndustryMapper, times(1)).selectByLevel(eq(1), anyString());
    }

    // ==================== D7.4 indices ====================

    @Test
    void D7_4_indices_懒兜底缓存命中() {
        // @Cacheable key = #root.target.getLatestTradeDate() = indexDailyMapper.selectLatestTradeDate()
        when(indexDailyMapper.selectLatestTradeDate()).thenReturn(TRADE_DATE);
        // computeMarketIndices → indexDailyService.getLatestByCodes → 非空
        when(indexDailyService.getLatestByCodes(any()))
                .thenReturn(List.of(IndexDailyDO.builder()
                        .tsCode("000001.SH")
                        .tradeDate(TRADE_DATE)
                        .build()));

        Cache cache = cacheManager.getCache("indices");
        assertThat(cache).isNotNull();
        cache.clear();

        // 第一次调用 → cache miss → compute 执行
        var result1 = marketService.getMarketIndices();
        assertThat(result1).isNotEmpty();
        verify(indexDailyService, times(1)).getLatestByCodes(any());
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        // 第二次调用 → cache hit → compute 不执行
        var result2 = marketService.getMarketIndices();
        assertThat(result2).isEqualTo(result1);
        verify(indexDailyService, times(1)).getLatestByCodes(any());
    }

    // ==================== D7.5 marketRanking ====================

    @Test
    void D7_5_marketRanking_懒兜底缓存命中() {
        // @Cacheable key = #root.target.getLatestTradeDate() = indexDailyMapper.selectLatestTradeDate()
        when(indexDailyMapper.selectLatestTradeDate()).thenReturn(TRADE_DATE);
        // computeMarketRanking → dailyQuoteMapper.selectLatestTradeDate → null → 早返回非 null VO
        when(dailyQuoteMapper.selectLatestTradeDate()).thenReturn(null);

        Cache cache = cacheManager.getCache("marketRanking");
        assertThat(cache).isNotNull();
        cache.clear();

        // 第一次调用 → cache miss → compute 执行
        var result1 = marketService.getMarketRanking();
        assertThat(result1).isNotNull();
        // compute 内调用了 dailyQuoteMapper.selectLatestTradeDate()
        verify(dailyQuoteMapper, times(1)).selectLatestTradeDate();
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        // 第二次调用 → cache hit → compute 不执行
        var result2 = marketService.getMarketRanking();
        assertThat(result2).isEqualTo(result1);
        // 仍只被调用 1 次（缓存命中）
        verify(dailyQuoteMapper, times(1)).selectLatestTradeDate();
    }

    // ==================== D7.6 marketTemperature ====================

    @Test
    void D7_6_marketTemperature_懒兜底缓存命中() {
        // @Cacheable key = #root.target.resolveTemperatureTradeDate(#tradeDate)
        // tradeDate="20260729" → resolveTemperatureTradeDate 返回 "20260729"
        // computeMarketTemperature → dailyQuoteMapper.selectMarketTemperature → null → 返回非 null VO
        when(dailyQuoteMapper.selectMarketTemperature(eq(TRADE_DATE))).thenReturn(null);

        Cache cache = cacheManager.getCache("marketTemperature");
        assertThat(cache).isNotNull();
        cache.clear();

        // 第一次调用 → cache miss → compute 执行
        var result1 = marketService.getMarketTemperature(TRADE_DATE);
        assertThat(result1).isNotNull();
        verify(dailyQuoteMapper, times(1)).selectMarketTemperature(eq(TRADE_DATE));
        assertThat(cache.get(TRADE_DATE)).isNotNull();

        // 第二次调用 → cache hit → compute 不执行
        var result2 = marketService.getMarketTemperature(TRADE_DATE);
        assertThat(result2).isEqualTo(result1);
        verify(dailyQuoteMapper, times(1)).selectMarketTemperature(eq(TRADE_DATE));
    }

    // ==================== D7.7 moneyflowRanking ====================

    @Test
    void D7_7_moneyflowRanking_懒兜底缓存命中() {
        when(moneyflowMapper.selectTopByTradeDate(eq(TRADE_DATE), eq(10), eq("net_mf_amount"), eq("desc")))
                .thenReturn(List.of(MoneyflowDO.builder().tsCode("000001.SZ").build()));

        Cache cache = cacheManager.getCache("moneyflowRanking");
        assertThat(cache).isNotNull();
        cache.clear();

        // 第一次调用 → cache miss → compute 执行
        var result1 = moneyflowService.queryTop(TRADE_DATE, 10, "main_net", "desc");
        assertThat(result1).isNotEmpty();
        verify(moneyflowMapper, times(1))
                .selectTopByTradeDate(eq(TRADE_DATE), eq(10), eq("net_mf_amount"), eq("desc"));

        String cacheKey = com.arthur.stock.util.CacheKeyResolver
                .resolveMoneyflowRankingKey(TRADE_DATE, 10, "main_net", "desc");
        assertThat(cache.get(cacheKey)).isNotNull();

        // 第二次调用 → cache hit → compute 不执行
        var result2 = moneyflowService.queryTop(TRADE_DATE, 10, "main_net", "desc");
        assertThat(result2).isEqualTo(result1);
        verify(moneyflowMapper, times(1))
                .selectTopByTradeDate(eq(TRADE_DATE), eq(10), eq("net_mf_amount"), eq("desc"));
    }
}
