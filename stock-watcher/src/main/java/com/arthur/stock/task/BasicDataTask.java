package com.arthur.stock.task;

import com.arthur.stock.service.BalancesheetService;
import com.arthur.stock.service.BasicDataService;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.ExpressService;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.IncomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 基本面数据定时任务：
 * <ul>
 *   <li>每日 16:10 拉取当日全市场 daily_basic（估值/换手率/市值）；</li>
 *   <li>每周日 17:00 拉取最近 2 年 fina_indicator（财务指标，按股票逐只拉）；</li>
 *   <li>每周日 17:30 拉取最近 2 年 income（利润表，错峰避免限流）；</li>
 *   <li>每周日 18:00 拉取最近 2 年 balancesheet（资产负债表）；</li>
 *   <li>每周日 18:30 拉取最近 2 年 cashflow（现金流量表）。</li>
 *   <li>每周日 19:00 拉取最近 2 年 forecast（业绩预告）；</li>
 *   <li>每周日 19:30 拉取最近 2 年 express（业绩快报）。</li>
 * </ul>
 * <p>
 * daily_basic 在 DailyUpdateTask（16:00）之后执行，确保 trade_date 当日数据已就绪。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BasicDataTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BasicDataService basicDataService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;

    /** 每日 16:10 拉取当日 daily_basic */
    @Scheduled(cron = "0 10 16 * * MON-FRI")
    public void fetchDailyBasic() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("===== BasicDataTask daily_basic start, tradeDate={} =====", tradeDate);
        try {
            int n = basicDataService.fetchAndSaveDailyBasic(tradeDate);
            log.info("===== BasicDataTask daily_basic done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask daily_basic 失败 tradeDate={}", tradeDate, e);
        }
    }

    /** 每周日 17:00 拉取最近 2 年 fina_indicator */
    @Scheduled(cron = "0 0 17 * * SUN")
    public void fetchFinaIndicator() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask fina_indicator start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = basicDataService.fetchAndSaveFinaIndicator(startPeriod, endPeriod);
            log.info("===== BasicDataTask fina_indicator done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask fina_indicator 失败", e);
        }
    }

    /** 每周日 17:30 拉取最近 2 年利润表 income（doc_id=33） */
    @Scheduled(cron = "0 30 17 * * SUN")
    public void fetchIncome() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask income start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = incomeService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataTask income done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask income 失败", e);
        }
    }

    /** 每周日 18:00 拉取最近 2 年资产负债表 balancesheet（doc_id=36） */
    @Scheduled(cron = "0 0 18 * * SUN")
    public void fetchBalancesheet() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask balancesheet start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = balancesheetService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataTask balancesheet done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask balancesheet 失败", e);
        }
    }

    /** 每周日 18:30 拉取最近 2 年现金流量表 cashflow（doc_id=44） */
    @Scheduled(cron = "0 30 18 * * SUN")
    public void fetchCashflow() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask cashflow start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = cashflowService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataTask cashflow done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask cashflow 失败", e);
        }
    }

    /** 每周日 19:00 拉取最近 2 年业绩预告 forecast（doc_id=45） */
    @Scheduled(cron = "0 0 19 * * SUN")
    public void fetchForecast() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask forecast start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = forecastService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataTask forecast done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask forecast 失败", e);
        }
    }

    /** 每周日 19:30 拉取最近 2 年业绩快报 express（doc_id=46） */
    @Scheduled(cron = "0 30 19 * * SUN")
    public void fetchExpress() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask express start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = expressService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataTask express done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask express 失败", e);
        }
    }
}
