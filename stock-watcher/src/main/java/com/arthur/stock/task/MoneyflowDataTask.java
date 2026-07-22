package com.arthur.stock.task;

import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.HkHoldService;
import com.arthur.stock.service.MarginService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.TopListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 资金流向数据每日定时任务
 * <p>
 * 每个工作日 16:10 执行（在 DailyUpdateTask 16:00 行情更新之后），
 * 拉取当日 7 张资金流向相关表：
 * <ol>
 *   <li>moneyflow：个股资金流向</li>
 *   <li>hk_hold：沪深港通持股</li>
 *   <li>top_list：龙虎榜明细</li>
 *   <li>top_inst：龙虎榜机构席位</li>
 *   <li>block_trade：大宗交易</li>
 *   <li>margin：融资融券</li>
 *   <li>margin_detail：融资融券明细</li>
 * </ol>
 * 每张表独立 try-catch，单表失败不影响其余表拉取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MoneyflowDataTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MoneyflowService moneyflowService;
    private final HkHoldService hkHoldService;
    private final TopListService topListService;
    private final BlockTradeService blockTradeService;
    private final MarginService marginService;

    /**
     * 每个工作日 16:10 拉取资金流向数据
     */
    @Scheduled(cron = "0 10 16 * * MON-FRI")
    public void fetchDailyMoneyflowData() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("===== MoneyflowDataTask start, tradeDate={} =====", tradeDate);

        // 1. 个股资金流向
        try {
            int n = moneyflowService.fetchAndSave(tradeDate);
            log.info("moneyflow done, saved={}", n);
        } catch (Exception e) {
            log.error("moneyflow 拉取失败 tradeDate={}", tradeDate, e);
        }

        // 2. 沪深港通持股
        try {
            int n = hkHoldService.fetchAndSave(tradeDate);
            log.info("hk_hold done, saved={}", n);
        } catch (Exception e) {
            log.error("hk_hold 拉取失败 tradeDate={}", tradeDate, e);
        }

        // 3. 龙虎榜（明细 + 机构席位）
        try {
            int n1 = topListService.fetchAndSaveTopList(tradeDate);
            int n2 = topListService.fetchAndSaveTopInst(tradeDate);
            log.info("top_list/top_inst done, saved={}/{}", n1, n2);
        } catch (Exception e) {
            log.error("top_list/top_inst 拉取失败 tradeDate={}", tradeDate, e);
        }

        // 4. 大宗交易
        try {
            int n = blockTradeService.fetchAndSave(tradeDate);
            log.info("block_trade done, saved={}", n);
        } catch (Exception e) {
            log.error("block_trade 拉取失败 tradeDate={}", tradeDate, e);
        }

        // 5. 融资融券（汇总 + 明细）
        try {
            int n1 = marginService.fetchAndSaveMargin(tradeDate);
            int n2 = marginService.fetchAndSaveMarginDetail(tradeDate);
            log.info("margin/margin_detail done, saved={}/{}", n1, n2);
        } catch (Exception e) {
            log.error("margin/margin_detail 拉取失败 tradeDate={}", tradeDate, e);
        }

        log.info("===== MoneyflowDataTask finished =====");
    }
}
