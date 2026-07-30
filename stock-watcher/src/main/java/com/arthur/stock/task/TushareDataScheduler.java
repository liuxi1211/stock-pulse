package com.arthur.stock.task;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.service.DataInitService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tushare 数据采集唯一调度入口。 */
@Component
@RequiredArgsConstructor
public class TushareDataScheduler {

    private final DataInitService dataInitService;

    @Scheduled(cron = "0 0 16 * * ?")
    public void dailyCore() {
        dataInitService.scheduledIncrementalBatch("daily-core", List.of(
                InitStep.TRADE_CAL, InitStep.STOCK_BASIC, InitStep.DAILY,
                InitStep.ADJ_FACTOR, InitStep.DIVIDEND));
    }

    @Scheduled(cron = "0 10 16 * * MON-FRI")
    public void weekdayMarket() {
        dataInitService.scheduledIncrementalBatch("weekday-market", List.of(
                InitStep.DAILY_BASIC, InitStep.MONEYFLOW, InitStep.HK_HOLD,
                InitStep.TOP_LIST, InitStep.TOP_INST, InitStep.BLOCK_TRADE,
                InitStep.MARGIN, InitStep.MARGIN_DETAIL));
    }

    @Scheduled(cron = "0 25 16 * * MON-FRI")
    public void weekdayIndexBasic() {
        incremental("weekday-index-basic", InitStep.INDEX_BASIC);
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI")
    public void weekdayIndexDaily() {
        incremental("weekday-index-daily", InitStep.INDEX_DAILY);
    }

    @Scheduled(cron = "0 30 16 * * ?")
    public void dailyNamechange() {
        incremental("daily-namechange", InitStep.NAMECHANGE);
    }

    @Scheduled(cron = "0 35 16 * * ?")
    public void dailySuspend() {
        incremental("daily-suspend", InitStep.SUSPEND_D);
    }

    @Scheduled(cron = "0 40 16 * * ?")
    public void dailyStkLimit() {
        incremental("daily-stk-limit", InitStep.STK_LIMIT);
    }

    @Scheduled(cron = "0 0 20 * * MON-FRI")
    public void weekdayIndexWeight() {
        incremental("weekday-index-weight", InitStep.INDEX_WEIGHT);
    }

    @Scheduled(cron = "0 0 17 * * SUN")
    public void weeklyFinaIndicator() { incremental("weekly-fina-indicator", InitStep.FINA_INDICATOR); }

    @Scheduled(cron = "0 30 17 * * SUN")
    public void weeklyIncome() { incremental("weekly-income", InitStep.INCOME); }

    @Scheduled(cron = "0 0 18 * * SUN")
    public void weeklyBalancesheet() { incremental("weekly-balancesheet", InitStep.BALANCESHEET); }

    @Scheduled(cron = "0 30 18 * * SUN")
    public void weeklyCashflow() { incremental("weekly-cashflow", InitStep.CASHFLOW); }

    @Scheduled(cron = "0 0 19 * * SUN")
    public void weeklyForecast() { incremental("weekly-forecast", InitStep.FORECAST); }

    @Scheduled(cron = "0 30 19 * * SUN")
    public void weeklyExpress() { incremental("weekly-express", InitStep.EXPRESS); }

    @Scheduled(cron = "0 0 20 * * SUN")
    public void weeklyHoldernumber() { incremental("weekly-holdernumber", InitStep.STK_HOLDERNUMBER); }

    @Scheduled(cron = "0 30 20 * * SUN")
    public void weeklyHoldertrade() { incremental("weekly-holdertrade", InitStep.STK_HOLDERTRADE); }

    @Scheduled(cron = "0 0 22 1 * *")
    public void monthlySuspendFull() { full("monthly-suspend-full", InitStep.SUSPEND_D); }

    @Scheduled(cron = "0 30 22 1 * *")
    public void monthlyStkLimitFull() { full("monthly-stk-limit-full", InitStep.STK_LIMIT); }

    @Scheduled(cron = "0 0 22 1 1,4,7,10 *")
    public void quarterlyNamechangeFull() { full("quarterly-namechange-full", InitStep.NAMECHANGE); }

    @Scheduled(cron = "0 0 22 1 1,7 *")
    public void halfYearlySwIndustryFull() { full("half-yearly-sw-industry-full", InitStep.SW_INDUSTRY); }

    private void incremental(String batchName, InitStep step) {
        dataInitService.scheduledIncrementalBatch(batchName, List.of(step));
    }

    private void full(String batchName, InitStep step) {
        dataInitService.scheduledFullUpdate(batchName, step);
    }

}
