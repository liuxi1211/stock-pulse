package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.MoneyflowFetchResultVO;
import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.HkHoldService;
import com.arthur.stock.service.MarginService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.TopListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 资金流向数据手动补拉接口。
 * 支持按指定交易日手动触发 7 张表的数据拉取，用于历史日期补拉或重试。
 */
@Tag(name = "资金流向-数据补拉", description = "手动触发资金流向数据拉取")
@RestController
@RequestMapping("/api/moneyflow-data")
@RequiredArgsConstructor
@Slf4j
public class MoneyflowDataInitController {

    private final MoneyflowService moneyflowService;
    private final HkHoldService hkHoldService;
    private final TopListService topListService;
    private final BlockTradeService blockTradeService;
    private final MarginService marginService;

    @Operation(summary = "手动拉取指定日期数据", description = "拉取指定交易日的资金流向数据（7 张表），每张表独立执行互不影响。-1 表示该表拉取失败")
    @PostMapping("/fetch")
    public ApiResponse<MoneyflowFetchResultVO> fetchByDate(
            @Parameter(description = "交易日期 yyyyMMdd", required = true) @RequestParam String tradeDate) {

        int moneyflow = -1, hkHold = -1, topList = -1, topInst = -1;
        int blockTrade = -1, margin = -1, marginDetail = -1;

        try {
            moneyflow = moneyflowService.fetchAndSave(tradeDate);
        } catch (Exception e) {
            log.warn("moneyflow 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            hkHold = hkHoldService.fetchAndSave(tradeDate);
        } catch (Exception e) {
            log.warn("hk_hold 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            topList = topListService.fetchAndSaveTopList(tradeDate);
        } catch (Exception e) {
            log.warn("top_list 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            topInst = topListService.fetchAndSaveTopInst(tradeDate);
        } catch (Exception e) {
            log.warn("top_inst 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            blockTrade = blockTradeService.fetchAndSave(tradeDate);
        } catch (Exception e) {
            log.warn("block_trade 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            margin = marginService.fetchAndSaveMargin(tradeDate);
        } catch (Exception e) {
            log.warn("margin 拉取失败 tradeDate={}", tradeDate, e);
        }

        try {
            marginDetail = marginService.fetchAndSaveMarginDetail(tradeDate);
        } catch (Exception e) {
            log.warn("margin_detail 拉取失败 tradeDate={}", tradeDate, e);
        }

        MoneyflowFetchResultVO result = MoneyflowFetchResultVO.builder()
                .moneyflow(moneyflow)
                .hkHold(hkHold)
                .topList(topList)
                .topInst(topInst)
                .blockTrade(blockTrade)
                .margin(margin)
                .marginDetail(marginDetail)
                .build();

        return ApiResponse.success("数据拉取完成（-1 表示失败）", result);
    }
}
