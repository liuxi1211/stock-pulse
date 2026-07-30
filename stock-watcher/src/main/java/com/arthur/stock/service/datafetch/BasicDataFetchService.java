package com.arthur.stock.service.datafetch;

import com.arthur.stock.service.BasicDataService;
import com.arthur.stock.service.BalancesheetService;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.ExpressService;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.IncomeService;
import com.arthur.stock.service.StkHoldernumberService;
import com.arthur.stock.service.StkHoldertradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 基本面/估值数据拉取服务。
 * <ul>
 *   <li>{@link #fetchDailyBasic(String)}：每日 16:30 拉取当日 daily_basic（属于 16:30 批次）。</li>
 *   <li>{@link #fetchFinaIndicator()} / {@link #fetchIncome()} / ... ：每周日批量拉取财务三表等（独立定时触发）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasicDataFetchService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BasicDataService basicDataService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;
    private final StkHoldertradeService stkHoldertradeService;
    private final StkHoldernumberService stkHoldernumberService;

    /** 每日 16:30 拉取当日 daily_basic，返回是否出错 */
    public boolean fetchDailyBasic(String tradeDate) {
        log.info("===== BasicDataFetchService daily_basic start, tradeDate={} =====", tradeDate);
        try {
            int n = basicDataService.fetchAndSaveDailyBasic(tradeDate);
            log.info("===== BasicDataFetchService daily_basic done, saved={} =====", n);
            return false;
        } catch (Exception e) {
            log.error("BasicDataFetchService daily_basic 失败 tradeDate={}", tradeDate, e);
            return true;
        }
    }

    /** 每周日拉取最近 2 年 fina_indicator */
    public void fetchFinaIndicator() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService fina_indicator start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = basicDataService.fetchAndSaveFinaIndicator(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService fina_indicator done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService fina_indicator 失败", e);
        }
    }

    /** 每周日拉取最近 2 年利润表 income */
    public void fetchIncome() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService income start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = incomeService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService income done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService income 失败", e);
        }
    }

    /** 每周日拉取最近 2 年资产负债表 balancesheet */
    public void fetchBalancesheet() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService balancesheet start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = balancesheetService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService balancesheet done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService balancesheet 失败", e);
        }
    }

    /** 每周日拉取最近 2 年现金流量表 cashflow */
    public void fetchCashflow() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService cashflow start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = cashflowService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService cashflow done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService cashflow 失败", e);
        }
    }

    /** 每周日拉取最近 2 年业绩预告 forecast */
    public void fetchForecast() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService forecast start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = forecastService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService forecast done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService forecast 失败", e);
        }
    }

    /** 每周日拉取最近 2 年业绩快报 express */
    public void fetchExpress() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataFetchService express start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = expressService.fetchAndSaveAllByRange(startPeriod, endPeriod);
            log.info("===== BasicDataFetchService express done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService express 失败", e);
        }
    }

    /** 每周日拉取最近 1 年股东增减持 stk_holdertrade */
    public void fetchStkHoldertrade() {
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusYears(1).format(DATE_FMT);
        log.info("===== BasicDataFetchService stk_holdertrade start, [{}~{}] =====", startDate, endDate);
        try {
            int n = stkHoldertradeService.fetchAndSaveAll(startDate, endDate);
            log.info("===== BasicDataFetchService stk_holdertrade done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService stk_holdertrade 失败", e);
        }
    }

    /** 每周日拉取股东人数 stk_holdernumber */
    public void fetchStkHoldernumber() {
        log.info("===== BasicDataFetchService stk_holdernumber start =====");
        try {
            int n = stkHoldernumberService.fetchAndSaveAll();
            log.info("===== BasicDataFetchService stk_holdernumber done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataFetchService stk_holdernumber 失败", e);
        }
    }
}
