package com.arthur.stock.task;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.service.DataInitService;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TushareDataSchedulerTest {

    private final DataInitService dataInitService = mock(DataInitService.class);
    private final TushareDataScheduler scheduler = new TushareDataScheduler(dataInitService);

    @Test
    void shouldDelegateBatchesInDefinedOrder() {
        scheduler.dailyCore();
        scheduler.weekdayMarket();
        scheduler.monthlyStkLimitFull();

        verify(dataInitService).scheduledIncrementalBatch("daily-core", List.of(
                InitStep.TRADE_CAL, InitStep.STOCK_BASIC, InitStep.DAILY,
                InitStep.ADJ_FACTOR, InitStep.DIVIDEND));
        verify(dataInitService).scheduledIncrementalBatch("weekday-market", List.of(
                InitStep.DAILY_BASIC, InitStep.MONEYFLOW, InitStep.HK_HOLD,
                InitStep.TOP_LIST, InitStep.TOP_INST, InitStep.BLOCK_TRADE,
                InitStep.MARGIN, InitStep.MARGIN_DETAIL));
        verify(dataInitService).scheduledFullUpdate("monthly-stk-limit-full", InitStep.STK_LIMIT);
    }

    @Test
    void shouldCoverEveryTushareStep() {
        Set<InitStep> covered = new HashSet<>();
        covered.addAll(List.of(InitStep.TRADE_CAL, InitStep.STOCK_BASIC, InitStep.DAILY,
                InitStep.ADJ_FACTOR, InitStep.DIVIDEND, InitStep.DAILY_BASIC,
                InitStep.MONEYFLOW, InitStep.HK_HOLD, InitStep.TOP_LIST, InitStep.TOP_INST,
                InitStep.BLOCK_TRADE, InitStep.MARGIN, InitStep.MARGIN_DETAIL,
                InitStep.INDEX_BASIC, InitStep.INDEX_DAILY, InitStep.NAMECHANGE,
                InitStep.SUSPEND_D, InitStep.STK_LIMIT, InitStep.INDEX_WEIGHT,
                InitStep.FINA_INDICATOR, InitStep.INCOME, InitStep.BALANCESHEET,
                InitStep.CASHFLOW, InitStep.FORECAST, InitStep.EXPRESS,
                InitStep.STK_HOLDERNUMBER, InitStep.STK_HOLDERTRADE, InitStep.SW_INDUSTRY));

        assertEquals(EnumSet.allOf(InitStep.class), covered);
    }
}
