package com.arthur.stock.service;

import com.arthur.stock.cache.ScreenerResultCache;
import com.arthur.stock.client.ScreenerClient;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.ScreenLockMapper;
import com.arthur.stock.mapper.ScreenPlanMapper;
import com.arthur.stock.mapper.ScreenResultMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.ScreenLockDO;
import com.arthur.stock.model.ScreenResultDO;
import com.arthur.stock.service.impl.ScreenerServiceImpl;
import com.arthur.stock.vo.ScreenLockDetailVO;
import com.arthur.stock.vo.ScreenLockVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScreenerServiceImpl 纯单元测试（spec 003 阶段 3 Task 13 / 修复 checklist F33）。
 * <p>
 * 覆盖「结果锁定 + 收益追踪」核心逻辑：
 * <ul>
 *   <li>lockResult：正常 / 防重复 / 结果不存在（checklist F33）</li>
 *   <li>getLockDetail：stocksJson 解析</li>
 *   <li>applyTracking：等权组合收益 + 基准 / 交易日推进 / DONE 状态 / 交易日不足</li>
 * </ul>
 * <p>
 * 实现要点：用 Mockito mock 所有 mapper / client / 新依赖（tradeCalendarService / resultCache / screenerExecutor），
 * 不启动 Spring 上下文、不依赖 DB。
 * <p>
 * 重构后调用契约：
 * <ul>
 *   <li>交易日历走 {@code tradeCalendarService.getSortedTradeDates()}（返回 List&lt;String&gt; 升序），
 *   第 N 个交易日走 {@code tradeCalendarService.findNthTradeDateAfter(baseDate, n)}。</li>
 *   <li>收盘价走 {@code dailyQuoteMapper.selectByCodesAndTradeDate(codes, date)} 批量查询。</li>
 *   <li>{@code screenerExecutor} 用同步直跑 Executor（避免引入并发不确定性）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ScreenerServiceImplTest {

    @Mock
    private ScreenPlanMapper screenPlanMapper;
    @Mock
    private ScreenResultMapper screenResultMapper;
    @Mock
    private ScreenLockMapper screenLockMapper;
    @Mock
    private StockBasicMapper stockBasicMapper;
    @Mock
    private DailyQuoteMapper dailyQuoteMapper;
    @Mock
    private ScreenerClient screenerClient;
    @Mock
    private DailyQuoteService dailyQuoteService;
    @Mock
    private TradeCalendarService tradeCalendarService;
    @Mock
    private ScreenerResultCache resultCache;
    @Mock
    private Executor screenerExecutor;

    @InjectMocks
    private ScreenerServiceImpl screenerService;

    // ==================== lockResult ====================

    /**
     * F33-1：lockResult 正常流程。
     * 校验：result 存在 + 未锁定 → insert 一条 TRACKING 锁定记录（ret* 全 null），返回 VO。
     */
    @Test
    void lockResult_正常_应插入TRACKING锁定记录() {
        // given
        ScreenResultDO result = new ScreenResultDO();
        result.setId(1L);
        result.setPlanId(10L);
        result.setScreenDate("20260101");
        result.setStocksJson("[{\"symbol\":\"000001.SZ\"}]");
        when(screenResultMapper.selectById(1L)).thenReturn(result);

        when(screenLockMapper.selectCount(any())).thenReturn(0L);

        // insert 后再 selectById 返回完整记录（模拟 MyBatis-Plus 回填主键）
        when(screenLockMapper.insert(any(ScreenLockDO.class))).thenAnswer(inv -> {
            ScreenLockDO lock = inv.getArgument(0);
            lock.setId(100L);
            return 1;
        });
        ScreenLockDO persisted = new ScreenLockDO();
        persisted.setId(100L);
        persisted.setResultId(1L);
        persisted.setPlanId(10L);
        persisted.setLockDate("20260101");
        persisted.setStocksJson("[{\"symbol\":\"000001.SZ\"}]");
        persisted.setStatus("TRACKING");
        when(screenLockMapper.selectById(100L)).thenReturn(persisted);

        // when
        ScreenLockVO vo = screenerService.lockResult(1L);

        // then：捕获 insert 的 DO，校验字段
        ArgumentCaptor<ScreenLockDO> captor = ArgumentCaptor.forClass(ScreenLockDO.class);
        verify(screenLockMapper).insert(captor.capture());
        ScreenLockDO inserted = captor.getValue();
        assertThat(inserted.getResultId()).isEqualTo(1L);
        assertThat(inserted.getPlanId()).isEqualTo(10L);
        assertThat(inserted.getLockDate()).isEqualTo("20260101");
        assertThat(inserted.getStatus()).isEqualTo("TRACKING");
        assertThat(inserted.getRet5d()).isNull();
        assertThat(inserted.getRet10d()).isNull();
        assertThat(inserted.getRet20d()).isNull();
        assertThat(inserted.getBenchmarkRet5d()).isNull();

        // 返回 VO 校验
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(100L);
        assertThat(vo.getStatus()).isEqualTo("TRACKING");
    }

    /**
     * F33-2：lockResult 防重复锁定。
     * 已存在锁定记录（selectCount>0）→ 抛 SCREEN_LOCK_ALREADY_EXISTS。
     */
    @Test
    void lockResult_已锁定_应抛SCREEN_LOCK_ALREADY_EXISTS() {
        ScreenResultDO result = new ScreenResultDO();
        result.setId(1L);
        result.setPlanId(10L);
        result.setScreenDate("20260101");
        result.setStocksJson("[]");
        when(screenResultMapper.selectById(1L)).thenReturn(result);
        when(screenLockMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> screenerService.lockResult(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SCREEN_LOCK_ALREADY_EXISTS.getCode());

        verify(screenLockMapper, never()).insert(any(ScreenLockDO.class));
    }

    /**
     * F33-3：lockResult 结果不存在。
     * selectById 返回 null → 抛 SCREEN_RESULT_NOT_FOUND。
     */
    @Test
    void lockResult_结果不存在_应抛SCREEN_RESULT_NOT_FOUND() {
        when(screenResultMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> screenerService.lockResult(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SCREEN_RESULT_NOT_FOUND.getCode());

        verify(screenLockMapper, never()).insert(any(ScreenLockDO.class));
    }

    // ==================== getLockDetail ====================

    /**
     * F33-4：getLockDetail 解析 stocksJson + 回填 ret 字段。
     */
    @Test
    void getLockDetail_应解析stocksJson并回填收益() {
        ScreenLockDO lock = new ScreenLockDO();
        lock.setId(50L);
        lock.setResultId(1L);
        lock.setPlanId(10L);
        lock.setLockDate("20260101");
        lock.setStocksJson("[{\"symbol\":\"000001.SZ\",\"rank\":1,\"score\":1.5}]");
        lock.setRet5d(new BigDecimal("0.05"));
        lock.setStatus("TRACKING");
        when(screenLockMapper.selectById(50L)).thenReturn(lock);

        // buildContributions 走 tradeCalendarService.getLatestTradeDate + 批量 selectByCodesAndTradeDate
        when(tradeCalendarService.getLatestTradeDate()).thenReturn("20260110");
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), anyString())).thenReturn(List.of());

        ScreenLockDetailVO vo = screenerService.getLockDetail(50L);

        assertThat(vo).isNotNull();
        assertThat(vo.getStocks()).hasSize(1);
        assertThat(vo.getStocks().get(0).getSymbol()).isEqualTo("000001.SZ");
        assertThat(vo.getStocks().get(0).getRank()).isEqualTo(1);
        // score 1.5 用比较值断言（避免 BigDecimal 标度差异）
        assertThat(vo.getStocks().get(0).getScore()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(vo.getRet5d()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    // ==================== applyTracking ====================

    /**
     * F33-5：applyTracking 等权组合收益（核心）。
     * <p>
     * 构造：2 只股票（000001.SZ 起价 10、600000.SH 起价 20），每个周期各涨 10%/10%、20%/20%、30%/30%。
     * 基准 000300.SH：+1% / +2% / +4%。
     * 预期：ret5d=0.1, ret10d=0.2, ret20d=0.3；benchmarkRet5d=0.01；status=DONE。
     * <p>
     * 重构后 mock 契约：
     * <ul>
     *   <li>{@code tradeCalendarService.findNthTradeDateAfter} 返回 n=5/10/20 对应交易日；</li>
     *   <li>{@code dailyQuoteMapper.selectByCodesAndTradeDate(codes, date)} 按入参 date 返回预设收盘价列表。</li>
     * </ul>
     */
    @Test
    void applyTracking_满20交易日_应填齐收益并DONE() {
        ScreenLockDO lock = new ScreenLockDO();
        lock.setId(1L);
        lock.setResultId(10L);
        lock.setLockDate("20260101");
        lock.setStocksJson("[{\"symbol\":\"000001.SZ\"},{\"symbol\":\"600000.SH\"}]");
        lock.setStatus("TRACKING");

        // 1. 交易日历：非空即可（findNthTradeDateAfter 由 mock 直接给结果）
        when(tradeCalendarService.getSortedTradeDates()).thenReturn(List.of("20260101", "20260121"));
        // 2. 第 n 个交易日
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 5)).thenReturn("20260106");
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 10)).thenReturn("20260111");
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 20)).thenReturn("20260121");

        // 3. 批量收盘价：按入参 tradeDate 返回对应行情
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260101"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260101", "10"),
                quote("600000.SH", "20260101", "20"),
                quote("000300.SH", "20260101", "3000")
        ));
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260106"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260106", "11"),
                quote("600000.SH", "20260106", "22"),
                quote("000300.SH", "20260106", "3030")
        ));
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260111"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260111", "12"),
                quote("600000.SH", "20260111", "24"),
                quote("000300.SH", "20260111", "3060")
        ));
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260121"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260121", "13"),
                quote("600000.SH", "20260121", "26"),
                quote("000300.SH", "20260121", "3120")
        ));

        // when
        screenerService.applyTracking(lock);

        // then：捕获 updateById 参数
        ArgumentCaptor<ScreenLockDO> captor = ArgumentCaptor.forClass(ScreenLockDO.class);
        verify(screenLockMapper).updateById(captor.capture());
        ScreenLockDO updated = captor.getValue();

        // 组合收益 = mean((closeN - close0)/close0)
        // ret5d = mean((11-10)/10, (22-20)/20) = mean(0.1,0.1) = 0.1
        assertThat(updated.getRet5d()).isEqualByComparingTo(new BigDecimal("0.1"));
        // ret10d = mean((12-10)/10, (24-20)/20) = 0.2
        assertThat(updated.getRet10d()).isEqualByComparingTo(new BigDecimal("0.2"));
        // ret20d = mean((13-10)/10, (26-20)/20) = 0.3
        assertThat(updated.getRet20d()).isEqualByComparingTo(new BigDecimal("0.3"));
        // benchmarkRet5d = (3030-3000)/3000 = 0.01
        assertThat(updated.getBenchmarkRet5d()).isEqualByComparingTo(new BigDecimal("0.01"));
        // benchmarkRet10d = (3060-3000)/3000 = 0.02
        assertThat(updated.getBenchmarkRet10d()).isEqualByComparingTo(new BigDecimal("0.02"));
        // benchmarkRet20d = (3120-3000)/3000 = 0.04
        assertThat(updated.getBenchmarkRet20d()).isEqualByComparingTo(new BigDecimal("0.04"));
        // ret20d 已填齐 → DONE
        assertThat(updated.getStatus()).isEqualTo("DONE");
    }

    /**
     * F33-6：applyTracking 交易日不足（第 20 个交易日越界）。
     * 预期：ret5d / ret10d 有值，ret20d=null，status 保持 TRACKING（未 DONE）。
     */
    @Test
    void applyTracking_交易日不足20_应保留ret20d为null且仍TRACKING() {
        ScreenLockDO lock = new ScreenLockDO();
        lock.setId(1L);
        lock.setLockDate("20260101");
        lock.setStocksJson("[{\"symbol\":\"000001.SZ\"},{\"symbol\":\"600000.SH\"}]");
        lock.setStatus("TRACKING");

        when(tradeCalendarService.getSortedTradeDates()).thenReturn(List.of("20260101", "20260111"));
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 5)).thenReturn("20260106");
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 10)).thenReturn("20260111");
        when(tradeCalendarService.findNthTradeDateAfter("20260101", 20)).thenReturn(null);

        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260101"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260101", "10"),
                quote("600000.SH", "20260101", "20"),
                quote("000300.SH", "20260101", "3000")
        ));
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260106"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260106", "11"),
                quote("600000.SH", "20260106", "22"),
                quote("000300.SH", "20260106", "3030")
        ));
        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260111"))).thenReturn(Arrays.asList(
                quote("000001.SZ", "20260111", "12"),
                quote("600000.SH", "20260111", "24"),
                quote("000300.SH", "20260111", "3060")
        ));

        screenerService.applyTracking(lock);

        ArgumentCaptor<ScreenLockDO> captor = ArgumentCaptor.forClass(ScreenLockDO.class);
        verify(screenLockMapper).updateById(captor.capture());
        ScreenLockDO updated = captor.getValue();

        assertThat(updated.getRet5d()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(updated.getRet10d()).isEqualByComparingTo(new BigDecimal("0.2"));
        assertThat(updated.getRet20d()).isNull();
        assertThat(updated.getBenchmarkRet20d()).isNull();
        // ret20d 未填齐 → 仍 TRACKING
        assertThat(updated.getStatus()).isEqualTo("TRACKING");
    }

    // ==================== 辅助 ====================

    /** 构造一条只关心 tsCode/tradeDate/close 的日线（其余字段为 null）。 */
    private static DailyQuoteDO quote(String tsCode, String tradeDate, String close) {
        return DailyQuoteDO.builder()
                .tsCode(tsCode)
                .tradeDate(tradeDate)
                .close(new BigDecimal(close))
                .build();
    }
}
