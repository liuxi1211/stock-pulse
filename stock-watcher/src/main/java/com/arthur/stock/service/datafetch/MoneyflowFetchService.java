package com.arthur.stock.service.datafetch;

import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.HkHoldService;
import com.arthur.stock.service.MarginService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.TopListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 资金流向数据拉取服务（每日 16:30 批次）：
 * moneyflow / hk_hold / top_list / top_inst / block_trade / margin / margin_detail。
 * <p>
 * 每张表独立 try-catch，单表失败不影响其余表。返回是否出现错误（用于批次聚合上报）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyflowFetchService {

    private final MoneyflowService moneyflowService;
    private final HkHoldService hkHoldService;
    private final TopListService topListService;
    private final BlockTradeService blockTradeService;
    private final MarginService marginService;

    public boolean fetchAll(String tradeDate) {
        log.info("===== MoneyflowFetchService start, tradeDate={} =====", tradeDate);
        boolean hasError = false;

        try {
            int n = moneyflowService.fetchAndSave(tradeDate);
            log.info("moneyflow done, saved={}", n);
        } catch (Exception e) {
            hasError = true;
            log.error("moneyflow 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            int n = hkHoldService.fetchAndSave(tradeDate);
            log.info("hk_hold done, saved={}", n);
        } catch (Exception e) {
            hasError = true;
            log.error("hk_hold 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            int n1 = topListService.fetchAndSaveTopList(tradeDate);
            int n2 = topListService.fetchAndSaveTopInst(tradeDate);
            log.info("top_list/top_inst done, saved={}/{}", n1, n2);
        } catch (Exception e) {
            hasError = true;
            log.error("top_list/top_inst 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            int n = blockTradeService.fetchAndSave(tradeDate);
            log.info("block_trade done, saved={}", n);
        } catch (Exception e) {
            hasError = true;
            log.error("block_trade 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            int n1 = marginService.fetchAndSaveMargin(tradeDate);
            int n2 = marginService.fetchAndSaveMarginDetail(tradeDate);
            log.info("margin/margin_detail done, saved={}/{}", n1, n2);
        } catch (Exception e) {
            hasError = true;
            log.error("margin/margin_detail 拉取失败 tradeDate={}", tradeDate, e);
        }

        log.info("===== MoneyflowFetchService finished =====");
        return hasError;
    }
}
