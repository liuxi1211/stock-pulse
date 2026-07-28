package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.stock.StockQueryMetaDTO;
import com.arthur.stock.dto.stock.StockQueryResultDTO;
import com.arthur.stock.dto.tushare.StkHoldertradeDTO;
import com.arthur.stock.service.StkHoldertradeService;
import com.arthur.stock.util.StockQueryValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Tag(name = "个股诊断-股东增减持", description = "个股股东增减持查询")
@RestController
@RequestMapping("/stocks/{tsCode}/holder-trades")
@RequiredArgsConstructor
public class StkHoldertradeController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StkHoldertradeService stkHoldertradeService;

    @Operation(summary = "查询股东增减持", description = "按公告日期倒序返回，默认查询近一年")
    @GetMapping
    public ApiResponse<StockQueryResultDTO<StkHoldertradeDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "开始日期 yyyyMMdd，默认一年前")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd，默认今天")
            @RequestParam(required = false) String endDate) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        LocalDate today = LocalDate.now();
        String effectiveStartDate = startDate == null ? today.minusYears(1).format(DATE_FORMATTER) : startDate;
        String effectiveEndDate = endDate == null ? today.format(DATE_FORMATTER) : endDate;
        List<StkHoldertradeDTO> items = stkHoldertradeService.queryByDateRange(
                symbol, effectiveStartDate, effectiveEndDate);
        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStartDate)
                .endDate(effectiveEndDate)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }
}
