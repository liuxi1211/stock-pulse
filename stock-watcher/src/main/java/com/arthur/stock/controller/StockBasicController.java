package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.service.StockBasicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 股票基础信息接口
 */
@RestController
@RequestMapping("/api/tushare/stock-basic")
@RequiredArgsConstructor
public class StockBasicController {

    private final StockBasicService stockBasicService;

    /**
     * 查询本地数据库中的股票基础信息
     */
    @GetMapping
    public ApiResponse<List<StockBasicDTO>> query(
            @RequestParam(required = false) String tsCode,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String listStatus) {
        return ApiResponse.success(stockBasicService.queryLocal(tsCode, name, exchange, listStatus));
    }

    /**
     * 从 Tushare 拉取股票基础信息并存库
     */
    @PostMapping("/init")
    public ApiResponse<List<StockBasicDTO>> initStockBasic() {
        List<StockBasicDTO> stocks = stockBasicService.fetchAndSaveStockBasic();
        return ApiResponse.success("Fetched " + stocks.size() + " stocks", stocks);
    }
}
