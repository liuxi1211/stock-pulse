package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.KlineService;
import com.arthur.stock.vo.KlineDataVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "K线数据", description = "股票K线行情数据查询")
@RestController
@RequestMapping("/kline")
@RequiredArgsConstructor
public class KlineController {

    private final KlineService klineService;

    @Operation(summary = "获取K线数据", description = "根据股票代码和周期获取K线数据；可指定日期范围，默认返回全部")
    @GetMapping("/{stockCode}")
    public ApiResponse<List<KlineDataVO>> getKlineData(
            @Parameter(description = "股票代码，如 000001.SZ", required = true) @PathVariable String stockCode,
            @Parameter(description = "K线周期，支持 daily/weekly/monthly") @RequestParam(defaultValue = "daily") String period,
            @Parameter(description = "开始日期，格式 yyyy-MM-dd") @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期，格式 yyyy-MM-dd") @RequestParam(required = false) String endDate) {
        if (startDate != null && endDate != null) {
            return ApiResponse.success(klineService.getKlineData(stockCode, period, startDate, endDate));
        }
        return ApiResponse.success(klineService.getKlineData(stockCode, period));
    }
}