package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.stock.StockQueryMetaDTO;
import com.arthur.stock.dto.stock.StockQueryResultDTO;
import com.arthur.stock.service.KlineService;
import com.arthur.stock.util.StockQueryValidator;
import com.arthur.stock.vo.KlineDataVO;
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
import java.util.Locale;
import java.util.Map;

@Tag(name = "个股诊断", description = "个股诊断稳定查询契约")
@RestController
@RequestMapping("/stocks/{tsCode}")
@RequiredArgsConstructor
public class StockDiagnosisController {

    private static final Map<String, String> PERIOD_MAPPING = Map.of(
            "D", "daily",
            "W", "weekly",
            "M", "monthly",
            "60MIN", "60MIN");

    private final KlineService klineService;

    @Operation(summary = "查询个股K线", description = "返回显式数据列表及股票、数据截止日、周期和复权元信息")
    @GetMapping("/kline")
    public ApiResponse<StockQueryResultDTO<KlineDataVO>> getKline(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "周期：D/W/M")
            @RequestParam(defaultValue = "D") String period,
            @Parameter(description = "复权：QFQ/HFQ/NONE")
            @RequestParam(defaultValue = "QFQ") String adj,
            @Parameter(description = "开始日期 yyyyMMdd")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd")
            @RequestParam(required = false) String endDate,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "250") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);
        String normalizedPeriod = normalizePeriod(period);
        String adjustment = normalizeAdjustment(adj);

        String lowerBound = startDate == null ? "00000000" : startDate;
        String upperBound = endDate == null ? "99999999" : endDate;
        List<KlineDataVO> data = klineService.getKlineData(
                symbol, PERIOD_MAPPING.get(normalizedPeriod), adjustment, lowerBound, upperBound);
        List<KlineDataVO> items = data.stream()
                .skip(Math.max(0, data.size() - effectiveLimit))
                .toList();
        String dataAsOf = items.isEmpty() ? null : items.get(items.size() - 1).getDate();

        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .period(normalizedPeriod)
                .adjustment(adjustment)
                .startDate(startDate)
                .endDate(endDate)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    private String normalizePeriod(String period) {
        String normalized = period == null ? "" : period.trim().toUpperCase(Locale.ROOT);
        if (!PERIOD_MAPPING.containsKey(normalized)) {
            throw new IllegalArgumentException("period必须为D、W、M或60MIN");
        }
        return normalized;
    }

    private String normalizeAdjustment(String adj) {
        String normalized = adj == null ? "" : adj.trim().toUpperCase(Locale.ROOT);
        if (!"QFQ".equals(normalized) && !"HFQ".equals(normalized) && !"NONE".equals(normalized)) {
            throw new IllegalArgumentException("adj必须为QFQ、HFQ或NONE");
        }
        return normalized;
    }
}
