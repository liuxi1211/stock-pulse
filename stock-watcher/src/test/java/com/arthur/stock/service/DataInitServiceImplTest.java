package com.arthur.stock.service;

import com.arthur.stock.cache.TaskProgressCache;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.mapper.*;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.service.impl.DataInitServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitServiceImplTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StockBasicService stockBasicService;
    @Mock private TradeCalService tradeCalService;
    @Mock private IndexWeightService indexWeightService;
    @Mock private SwIndustryService swIndustryService;
    @Mock private DailyQuoteService dailyQuoteService;
    @Mock private AdjFactorService adjFactorService;
    @Mock private IndexDailyMapper indexDailyMapper;
    @Mock private IndexBasicMapper indexBasicMapper;
    @Mock private IndexBasicService indexBasicService;
    @Mock private DividendMapper dividendMapper;
    @Mock private StockNamechangeMapper stockNamechangeMapper;
    @Mock private StockSuspendDMapper stockSuspendDMapper;
    @Mock private IncomeMapper incomeMapper;
    @Mock private BalancesheetMapper balancesheetMapper;
    @Mock private CashflowMapper cashflowMapper;
    @Mock private ForecastMapper forecastMapper;
    @Mock private ExpressMapper expressMapper;
    @Mock private FinaIndicatorMapper finaIndicatorMapper;
    @Mock private TradeCalMapper tradeCalMapper;
    @Mock private IndexWeightMapper indexWeightMapper;
    @Mock private DividendService dividendService;
    @Mock private StockNamechangeService stockNamechangeService;
    @Mock private StockSuspendDService stockSuspendDService;
    @Mock private StockStkLimitService stockStkLimitService;
    @Mock private StkHoldertradeService stkHoldertradeService;
    @Mock private StkHoldernumberService stkHoldernumberService;
    @Mock private IncomeService incomeService;
    @Mock private BalancesheetService balancesheetService;
    @Mock private CashflowService cashflowService;
    @Mock private ForecastService forecastService;
    @Mock private ExpressService expressService;
    @Mock private FinaIndicatorService finaIndicatorService;
    @Mock private TaskProgressCache taskProgressCache;
    @Mock private DataPullLogMapper dataPullLogMapper;
    @Mock private DataGovernanceService dataGovernanceService;
    @Mock private BasicDataService basicDataService;
    @Mock private MoneyflowService moneyflowService;
    @Mock private TopListService topListService;
    @Mock private BlockTradeService blockTradeService;
    @Mock private HkHoldService hkHoldService;
    @Mock private MarginService marginService;
    @Mock private IndexDailyFetchService indexDailyFetchService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    @InjectMocks
    private DataInitServiceImpl service;

    @Test
    void scheduledIncrementalBatch_步骤失败_立即停止后续步骤() {
        when(taskProgressCache.tryAcquireLock()).thenReturn(true);
        when(tradeCalService.fetchAndSaveTradeCal(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Tushare unavailable"));

        service.scheduledIncrementalBatch("test-batch", List.of(InitStep.TRADE_CAL, InitStep.STOCK_BASIC));

        verify(stockBasicService, never()).fetchAndSaveStockBasic();
        verify(dataPullLogMapper).updateStatus(any(DataPullLogDO.class));
        verify(taskProgressCache).releaseLock();
    }

    @Test
    void scheduledIncrementalBatch_步骤部分完成后失败_失败日志记录已成功写入行数() {
        List<TradeCalDTO> savedRows = List.of(
                TradeCalDTO.builder().exchange("SSE").calDate("20260729").build(),
                TradeCalDTO.builder().exchange("SSE").calDate("20260730").build(),
                TradeCalDTO.builder().exchange("SSE").calDate("20260731").build());
        when(taskProgressCache.tryAcquireLock()).thenReturn(true);
        when(tradeCalService.fetchAndSaveTradeCal(anyString(), anyString(), anyString()))
                .thenReturn(savedRows)
                .thenThrow(new IllegalStateException("second exchange failed"));

        service.scheduledIncrementalBatch("test-batch", List.of(InitStep.TRADE_CAL));

        ArgumentCaptor<DataPullLogDO> logCaptor = ArgumentCaptor.forClass(DataPullLogDO.class);
        verify(dataPullLogMapper).updateStatus(logCaptor.capture());
        DataPullLogDO failedLog = logCaptor.getValue();
        assertThat(failedLog.getStatus()).isEqualTo("FAILED");
        assertThat(failedLog.getTotalCount()).isEqualTo(3L);
        assertThat(failedLog.getErrorMessage()).contains("SZSE");
    }

    @Test
    void incrementalUpdate_手动成功_清理依赖缓存且成功日志保留实际写入行数() {
        when(taskProgressCache.tryAcquireLock()).thenReturn(true);
        when(indexBasicService.fetchAndSaveAll()).thenReturn(3);
        when(cacheManager.getCache("sectorRanking")).thenReturn(cache);

        service.incrementalUpdate(InitStep.INDEX_BASIC.getCode(), "tester");

        ArgumentCaptor<DataPullLogDO> logCaptor = ArgumentCaptor.forClass(DataPullLogDO.class);
        verify(dataPullLogMapper, timeout(2000)).updateStatus(logCaptor.capture());
        DataPullLogDO successLog = logCaptor.getValue();
        assertThat(successLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(successLog.getTotalCount()).isEqualTo(3L);
        verify(cache, timeout(2000)).clear();
        verify(taskProgressCache, timeout(2000)).releaseLock();
    }
}
