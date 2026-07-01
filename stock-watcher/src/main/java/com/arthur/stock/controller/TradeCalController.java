package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.service.TradeCalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 交易日历接口
 */
@RestController
@RequestMapping("/api/tushare/trade-cal")
@RequiredArgsConstructor
public class TradeCalController {

    private final TradeCalService tradeCalService;

    /**
     * 查询本地数据库中的交易日历
     */
    @GetMapping
    public ApiResponse<List<TradeCalDTO>> query(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String isOpen) {
        return ApiResponse.success(tradeCalService.queryLocal(exchange, startDate, endDate, isOpen));
    }

    /**
     * 从 Tushare 拉取交易日历并存库
     */
    @PostMapping("/init")
    public ApiResponse<List<TradeCalDTO>> initTradeCal(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        List<TradeCalDTO> calendars = tradeCalService.fetchAndSaveTradeCal(exchange, startDate, endDate);
        return ApiResponse.success("Fetched " + calendars.size() + " calendar records", calendars);
    }
}
