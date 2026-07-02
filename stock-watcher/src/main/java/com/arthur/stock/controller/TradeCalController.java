package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.service.TradeCalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tushare-交易日历", description = "交易日历查询与数据初始化")
@RestController
@RequestMapping("/tushare/trade-cal")
@RequiredArgsConstructor
public class TradeCalController {

    private final TradeCalService tradeCalService;

    @Operation(summary = "查询交易日历", description = "从本地数据库查询交易日历，支持按交易所、日期范围、是否开盘筛选")
    @GetMapping
    public ApiResponse<List<TradeCalDTO>> query(
            @Parameter(description = "交易所，SSE/SZSE/BSE") @RequestParam(required = false) String exchange,
            @Parameter(description = "开始日期，格式 yyyyMMdd") @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期，格式 yyyyMMdd") @RequestParam(required = false) String endDate,
            @Parameter(description = "是否开盘，1-开盘 0-休市") @RequestParam(required = false) String isOpen) {
        return ApiResponse.success(tradeCalService.queryLocal(exchange, startDate, endDate, isOpen));
    }

    @Operation(summary = "初始化交易日历", description = "从 Tushare 拉取交易日历数据并存入本地数据库")
    @PostMapping("/init")
    public ApiResponse<List<TradeCalDTO>> initTradeCal(
            @Parameter(description = "交易所，SSE/SZSE/BSE") @RequestParam(required = false) String exchange,
            @Parameter(description = "开始日期，格式 yyyyMMdd") @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期，格式 yyyyMMdd") @RequestParam(required = false) String endDate) {
        List<TradeCalDTO> calendars = tradeCalService.fetchAndSaveTradeCal(exchange, startDate, endDate);
        return ApiResponse.success("Fetched " + calendars.size() + " calendar records", calendars);
    }
}
