package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.service.StockBasicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tushare-股票基础信息", description = "股票基础信息查询与数据初始化")
@RestController
@RequestMapping("/tushare/stock-basic")
@RequiredArgsConstructor
public class StockBasicController {

    private final StockBasicService stockBasicService;

    @Operation(summary = "查询股票基础信息", description = "从本地数据库查询股票基础信息，支持按代码、名称、交易所、上市状态筛选")
    @GetMapping
    public ApiResponse<List<StockBasicDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ") @RequestParam(required = false) String tsCode,
            @Parameter(description = "股票名称") @RequestParam(required = false) String name,
            @Parameter(description = "交易所，SSE-上交所 SZSE-深交所 BSE-北交所") @RequestParam(required = false) String exchange,
            @Parameter(description = "上市状态，L-上市 D-退市 P-暂停上市") @RequestParam(required = false) String listStatus) {
        return ApiResponse.success(stockBasicService.queryLocal(tsCode, name, exchange, listStatus));
    }

    @Operation(summary = "初始化股票基础信息", description = "从 Tushare 拉取全量股票基础信息并存入本地数据库")
    @PostMapping("/init")
    public ApiResponse<List<StockBasicDTO>> initStockBasic() {
        List<StockBasicDTO> stocks = stockBasicService.fetchAndSaveStockBasic();
        return ApiResponse.success("Fetched " + stocks.size() + " stocks", stocks);
    }
}
