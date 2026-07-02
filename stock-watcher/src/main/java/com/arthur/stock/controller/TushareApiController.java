package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.service.DailyQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tushare-日线行情", description = "日线行情数据查询与数据初始化")
@RestController
@RequestMapping("/tushare/daily")
@RequiredArgsConstructor
public class TushareApiController {

    private final DailyQuoteService dailyQuoteService;

    @Operation(summary = "查询日线行情", description = "传 tradeDate 查某日全市场行情，传 tsCode+startDate+endDate 查单只股票")
    @GetMapping
    public ApiResponse<List<DailyQuoteDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ") @RequestParam(required = false) String tsCode,
            @Parameter(description = "交易日期，格式 yyyyMMdd") @RequestParam(required = false) String tradeDate,
            @Parameter(description = "开始日期，格式 yyyyMMdd") @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期，格式 yyyyMMdd") @RequestParam(required = false) String endDate) {
        if (tradeDate != null && !tradeDate.isEmpty()) {
            return ApiResponse.success(dailyQuoteService.queryByTradeDate(tradeDate));
        }
        return ApiResponse.success(dailyQuoteService.queryByCodeAndDateRange(tsCode, startDate, endDate));
    }

    @Operation(summary = "初始化日线行情数据", description = "从 Tushare 全量拉取指定股票的日线行情并存入本地数据库")
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<DailyQuoteDTO>> initStockData(
            @Parameter(description = "股票代码，如 000001.SZ", required = true) @PathVariable String tsCode) {
        List<DailyQuoteDTO> quotes = dailyQuoteService.fetchAndSaveDailyQuotes(tsCode);
        return ApiResponse.success("Fetched " + quotes.size() + " records for " + tsCode, quotes);
    }
}
