package com.arthur.stock.service.precompute;

import com.arthur.stock.event.DataBatchReadyEvent;
import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.impl.MoneyflowServiceImpl;
import com.arthur.stock.client.TushareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 非交易日/跨日场景测试（spec 026 Phase D9）。
 * <p>
 * D9.1：验证 {@link DataBatchCompletionTracker} 在非交易日（周末/节假日）仍正常聚合 4 任务报告并发布事件。
 * D9.2：验证 {@code @Cacheable(unless = "#result == null || #result.isEmpty()")} 在 Service 层生效——
 *       空 List 结果不被缓存，后续调用仍触发 compute。
 * <p>
 * 用最小化 Spring 上下文（{@code @EnableCaching} + Caffeine + mock 依赖），
 * 不启动完整 {@code @SpringBootTest}，不连 DB。
 */
@ExtendWith(SpringExtension.class)
@org.springframework.test.context.ContextConfiguration(
        classes = NonTradingDayTest.TestConfig.class)
class NonTradingDayTest {

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            // 动态缓存名，任一 cacheName 都可用
            return new CaffeineCacheManager();
        }

        @Bean
        ApplicationEventPublisher eventPublisher() {
            return org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        }

        // ---- MoneyflowService 依赖 ----
        @Bean MoneyflowMapper moneyflowMapper() {
            return org.mockito.Mockito.mock(MoneyflowMapper.class);
        }
        @Bean TushareClient tushareClient() {
            return org.mockito.Mockito.mock(TushareClient.class);
        }
        @Bean TransactionTemplate transactionTemplate() {
            return org.mockito.Mockito.mock(TransactionTemplate.class);
        }
        @Bean MoneyflowService moneyflowService(MoneyflowMapper mapper, TushareClient tc,
                                                 TransactionTemplate tt) {
            return new MoneyflowServiceImpl(mapper, tc, tt);
        }
    }

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private MoneyflowService moneyflowService;
    @Autowired
    private MoneyflowMapper moneyflowMapper;
    @Autowired
    private CacheManager cacheManager;

    /** tracker 手动构造，确保使用 mock eventPublisher（避免 Spring 将 ApplicationEventPublisher
     *  解析为 ApplicationContext 而非 mock bean） */
    private DataBatchCompletionTracker tracker;

    /** spec 指定的 4 个核心任务 taskKey */
    private static final String T1 = "BATCH_1600";
    private static final String T2 = "BATCH_1630_DAILY_BASIC";
    private static final String T3 = "BATCH_1630_MONEYFLOW";
    private static final String T4 = "BATCH_1630_INDEX_DAILY";

    /** 非交易日：2026-08-01（周六） */
    private static final String NON_TRADING_DATE = "20260801";

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(eventPublisher, moneyflowMapper);
        // 每个测试前重建 tracker，确保使用 reset 后的 mock 且无跨测试状态污染
        tracker = new DataBatchCompletionTracker(eventPublisher);
        // 清空所有缓存，避免跨测试污染
        cacheManager.getCacheNames().forEach(name -> {
            var c = cacheManager.getCache(name);
            if (c != null) c.clear();
        });
    }

    // ==================== D9.1 ====================

    /**
     * 非交易日 tracker 行为验证。
     * <p>
     * {@link DataBatchCompletionTracker} 不校验 tradeDate 是否为交易日，
     * 只关心 4 个 EXPECTED_TASKS 是否都报告。非交易日（周末/节假日）4 任务报告后
     * 仍应正常发布 {@link DataBatchReadyEvent}（source=SCHEDULED）。
     * <p>
     * spec 提到「mock LocalDate.now(ZoneId.of("Asia/Shanghai")) 返回非交易日」，
     * 但 tracker 实现不读 LocalDate.now()（只用 tradeDate 入参），
     * 故用周六日期 "20260801" 作为 tradeDate 入参即可覆盖。
     */
    @Test
    void D9_1_非交易日4任务报告后仍正常发布事件() {
        tracker.reportCompletion(T1, NON_TRADING_DATE);
        tracker.reportCompletion(T2, NON_TRADING_DATE);
        tracker.reportCompletion(T3, NON_TRADING_DATE);
        tracker.reportCompletion(T4, NON_TRADING_DATE);

        verify(eventPublisher, times(1)).publishEvent(any(DataBatchReadyEvent.class));

        // 用 ArgumentCaptor 验证事件内容
        org.mockito.ArgumentCaptor<DataBatchReadyEvent> captor =
                org.mockito.ArgumentCaptor.forClass(DataBatchReadyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DataBatchReadyEvent event = captor.getValue();
        assertThat(event.getTradeDate()).isEqualTo(NON_TRADING_DATE);
        assertThat(event.getSource()).isEqualTo("SCHEDULED");
    }

    // ==================== D9.1b：非交易日部分失败仍发布 SCHEDULED_PARTIAL ====================

    /**
     * 非交易日 + 部分任务异常 → source=SCHEDULED_PARTIAL。
     * <p>
     * 验证非交易日不影响 hasError 聚合逻辑：1 个任务报 hasError=true，
     * 收齐 4 个后事件 source=SCHEDULED_PARTIAL。
     */
    @Test
    void D9_1b_非交易日部分失败发布SCHEDULED_PARTIAL() {
        tracker.reportCompletion(T1, NON_TRADING_DATE, true); // hasError=true
        tracker.reportCompletion(T2, NON_TRADING_DATE);
        tracker.reportCompletion(T3, NON_TRADING_DATE);
        tracker.reportCompletion(T4, NON_TRADING_DATE);

        org.mockito.ArgumentCaptor<DataBatchReadyEvent> captor =
                org.mockito.ArgumentCaptor.forClass(DataBatchReadyEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        DataBatchReadyEvent event = captor.getValue();
        assertThat(event.getTradeDate()).isEqualTo(NON_TRADING_DATE);
        assertThat(event.getSource()).isEqualTo("SCHEDULED_PARTIAL");
    }

    // ==================== D9.2 ====================

    /**
     * 预计算空数据不缓存（unless 生效）。
     * <p>
     * {@code @Cacheable(value = "moneyflowRanking", unless = "#result == null || #result.isEmpty()")}
     * 在 Service 层生效：computeXXX 返回空 List 时，{@code unless} 阻止缓存写入。
     * <p>
     * 验证步骤：
     * <ol>
     *   <li>mock moneyflowMapper.selectTopByTradeDate 返回空 List；</li>
     *   <li>调 service.queryTop（@Cacheable 入口）→ cache miss → compute → 空 List → unless 阻止缓存；</li>
     *   <li>验证 cache 中无对应 key；</li>
     *   <li>再调一次 service.queryTop → 仍 cache miss → compute 再次执行；</li>
     *   <li>验证 mapper 被调用 2 次（未被缓存命中）。</li>
     * </ol>
     * <p>
     * <b>注</b>：Job 的 doPrecompute 内部 {@code cache.put} 不受 unless 影响，
     * 故本测试调 Service.getXXX() 而非 Job.precompute()。
     */
    @Test
    void D9_2_空结果不被Cacheable缓存除非unless生效() {
        // mock 返回空 List（触发 unless = "#result == null || #result.isEmpty()"）
        when(moneyflowMapper.selectTopByTradeDate(eq(NON_TRADING_DATE), eq(10), eq("net_mf_amount"), eq("desc")))
                .thenReturn(Collections.emptyList());

        // 第一次调用：cache miss → compute → 空 List → unless 阻止缓存
        List<MoneyflowDO> result1 = moneyflowService.queryTop(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(result1).isEmpty();

        // 验证 cache 中无对应 key（unless 生效）
        var cache = cacheManager.getCache("moneyflowRanking");
        assertThat(cache).isNotNull();
        String cacheKey = com.arthur.stock.util.CacheKeyResolver
                .resolveMoneyflowRankingKey(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(cache.get(cacheKey))
                .as("空 List 结果应被 unless 阻止缓存")
                .isNull();

        // 第二次调用：仍 cache miss → compute 再次执行
        List<MoneyflowDO> result2 = moneyflowService.queryTop(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(result2).isEmpty();

        // mapper 被调用 2 次（未被缓存命中）
        verify(moneyflowMapper, times(2))
                .selectTopByTradeDate(eq(NON_TRADING_DATE), eq(10), eq("net_mf_amount"), eq("desc"));
    }

    // ==================== D9.2b：非空结果被缓存，第二次命中 ====================

    /**
     * 对比用例：非空结果正常缓存，第二次调用命中缓存。
     * <p>
     * 与 D9.2 形成对照：当 computeXXX 返回<b>非空</b> List 时，{@code unless} 不阻止缓存，
     * 第二次调用命中缓存，mapper 只被调用 1 次。
     */
    @Test
    void D9_2b_非空结果正常缓存第二次命中() {
        List<MoneyflowDO> data = List.of(MoneyflowDO.builder().tsCode("000001.SZ").build());
        when(moneyflowMapper.selectTopByTradeDate(eq(NON_TRADING_DATE), eq(10), eq("net_mf_amount"), eq("desc")))
                .thenReturn(data);

        List<MoneyflowDO> result1 = moneyflowService.queryTop(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(result1).hasSize(1);

        // 验证 cache 已写入
        var cache = cacheManager.getCache("moneyflowRanking");
        assertThat(cache).isNotNull();
        String cacheKey = com.arthur.stock.util.CacheKeyResolver
                .resolveMoneyflowRankingKey(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(cache.get(cacheKey)).isNotNull();

        // 第二次调用：cache hit
        List<MoneyflowDO> result2 = moneyflowService.queryTop(NON_TRADING_DATE, 10, "main_net", "desc");
        assertThat(result2).isEqualTo(result1);

        // mapper 只被调用 1 次（第二次命中缓存）
        verify(moneyflowMapper, times(1))
                .selectTopByTradeDate(eq(NON_TRADING_DATE), eq(10), eq("net_mf_amount"), eq("desc"));
    }
}
