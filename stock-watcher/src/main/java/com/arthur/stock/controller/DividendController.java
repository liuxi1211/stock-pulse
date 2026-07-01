package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.service.DividendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tushare 分红送股接口
 */
@RestController
@RequestMapping("/api/tushare/dividend")
@RequiredArgsConstructor
public class DividendController {

    private final DividendService dividendService;

    /**
     * 查询分红送股数据。
     * 传 tsCode 查某只股票的分红记录
     */
    @GetMapping
    public ApiResponse<List<DividendDTO>> query(
            @RequestParam(required = false) String tsCode) {
        return ApiResponse.success(dividendService.queryByTsCode(tsCode));
    }

    /**
     * 初始化某只股票的分红送股数据，全量拉取并存库
     */
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<DividendDTO>> initDividend(@PathVariable String tsCode) {
        List<DividendDTO> dividends = dividendService.fetchAndSaveDividend(tsCode);
        return ApiResponse.success("Fetched " + dividends.size() + " records for " + tsCode, dividends);
    }
}
