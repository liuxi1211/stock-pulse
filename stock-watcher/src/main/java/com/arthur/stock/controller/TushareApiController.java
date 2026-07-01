package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.service.DailyQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tushare 日线行情接口
 */
@RestController
@RequestMapping("/api/tushare/daily")
@RequiredArgsConstructor
public class TushareApiController {

    private final DailyQuoteService dailyQuoteService;

    /**
     * 查询日线行情。
     * 传 tradeDate 查某日全市场，传 tsCode+startDate+endDate 查单只股票
     */
    @GetMapping
    public ApiResponse<List<DailyQuoteDTO>> query(
            @RequestParam(required = false) String tsCode,
            @RequestParam(required = false) String tradeDate,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        if (tradeDate != null && !tradeDate.isEmpty()) {
            return ApiResponse.success(dailyQuoteService.queryByTradeDate(tradeDate));
        }
        return ApiResponse.success(dailyQuoteService.queryByCodeAndDateRange(tsCode, startDate, endDate));
    }

    /**
     * 初始化某只股票的日线数据，全量拉取并存库
     */
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<DailyQuoteDTO>> initStockData(@PathVariable String tsCode) {
        List<DailyQuoteDTO> quotes = dailyQuoteService.fetchAndSaveDailyQuotes(tsCode);
        return ApiResponse.success("Fetched " + quotes.size() + " records for " + tsCode, quotes);
    }
}
