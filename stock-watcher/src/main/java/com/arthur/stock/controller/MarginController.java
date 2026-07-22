package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.MarginDO;
import com.arthur.stock.model.MarginDetailDO;
import com.arthur.stock.service.MarginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 融资融券数据查询接口
 */
@Tag(name = "融资融券", description = "融资融券汇总趋势与个股明细查询")
@RestController
@RequestMapping("/api/margin")
@RequiredArgsConstructor
public class MarginController {

    private static final int MAX_LIMIT = 200;

    private final MarginService marginService;

    @Operation(summary = "融资融券汇总趋势", description = "查询最近N天的融资融券汇总数据，按trade_date升序返回")
    @GetMapping("/trend")
    public ApiResponse<List<MarginDO>> trend(
            @Parameter(description = "天数，默认30")
            @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "交易所代码（SSE/SZSE/ALL），默认ALL")
            @RequestParam(defaultValue = "ALL") String exchangeId) {

        List<MarginDO> list = marginService.queryTrend(days, exchangeId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "融资融券个股明细Top", description = "查询某交易日融资融券个股明细Top-N")
    @GetMapping("/detail/top")
    public ApiResponse<List<MarginDetailDO>> detailTop(
            @Parameter(description = "交易日期 yyyyMMdd，为空时取最新")
            @RequestParam(required = false) String tradeDate,
            @Parameter(description = "返回条数，默认50，上限200")
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "排序字段（rzrqye/rzye/rqye/rzmre），默认rzrqye")
            @RequestParam(defaultValue = "rzrqye") String sortBy,
            @Parameter(description = "排序方向（asc/desc），默认desc")
            @RequestParam(defaultValue = "desc") String order) {

        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<MarginDetailDO> list = marginService.queryDetailTop(tradeDate, effectiveLimit, sortBy, order);
        return ApiResponse.success(list);
    }
}
