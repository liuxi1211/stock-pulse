package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.service.AdjFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tushare 复权因子接口
 */
@RestController
@RequestMapping("/api/tushare/adj-factor")
@RequiredArgsConstructor
public class AdjFactorController {

    private final AdjFactorService adjFactorService;

    /**
     * 查询复权因子。
     * 传 tradeDate 查某日全市场，传 tsCode+startDate+endDate 查单只股票
     */
    @GetMapping
    public ApiResponse<List<AdjFactorDTO>> query(
            @RequestParam(required = false) String tsCode,
            @RequestParam(required = false) String tradeDate,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        if (tradeDate != null && !tradeDate.isEmpty()) {
            return ApiResponse.success(adjFactorService.queryByTradeDate(tradeDate));
        }
        return ApiResponse.success(adjFactorService.queryByCodeAndDateRange(tsCode, startDate, endDate));
    }

    /**
     * 初始化某只股票的复权因子数据，全量拉取并存库
     */
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<AdjFactorDTO>> initAdjFactor(@PathVariable String tsCode) {
        List<AdjFactorDTO> factors = adjFactorService.fetchAndSaveAdjFactor(tsCode);
        return ApiResponse.success("Fetched " + factors.size() + " records for " + tsCode, factors);
    }
}
