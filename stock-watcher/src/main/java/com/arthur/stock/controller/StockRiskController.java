package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.stock.StockQueryMetaDTO;
import com.arthur.stock.dto.stock.StockQueryResultDTO;
import com.arthur.stock.dto.tushare.NamechangeDTO;
import com.arthur.stock.dto.tushare.StkLimitDTO;
import com.arthur.stock.dto.tushare.SuspendDDTO;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.model.StockStkLimitDO;
import com.arthur.stock.model.StockSuspendDDO;
import com.arthur.stock.service.StockNamechangeService;
import com.arthur.stock.service.StockStkLimitService;
import com.arthur.stock.service.StockSuspendDService;
import com.arthur.stock.util.DtoConverter;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Tag(name = "个股诊断-风险面", description = "个股风险面数据查询")
@RestController
@RequestMapping("/stocks/{tsCode}")
@RequiredArgsConstructor
public class StockRiskController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockStkLimitService stockStkLimitService;
    private final StockSuspendDService stockSuspendDService;
    private final StockNamechangeService stockNamechangeService;

    @Operation(summary = "查询涨跌停价", description = "按交易日期倒序返回，默认近30天")
    @GetMapping("/limits")
    public ApiResponse<StockQueryResultDTO<StkLimitDTO>> getLimits(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "开始日期 yyyyMMdd，默认30天前")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd，默认今天")
            @RequestParam(required = false) String endDate,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "30") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        LocalDate today = LocalDate.now();
        String effectiveStart = startDate == null ? today.minusDays(30).format(DATE_FORMATTER) : startDate;
        String effectiveEnd = endDate == null ? today.format(DATE_FORMATTER) : endDate;

        Map<String, Map<String, StockStkLimitDO>> resultMap =
                stockStkLimitService.listByRange(List.of(symbol), effectiveStart, effectiveEnd);
        Map<String, StockStkLimitDO> codeMap = resultMap.get(symbol);
        List<StkLimitDTO> items = (codeMap == null ? List.<StockStkLimitDO>of() : codeMap.values().stream().toList())
                .stream()
                .sorted(Comparator.comparing(StockStkLimitDO::getTradeDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, StkLimitDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStart)
                .endDate(effectiveEnd)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询停牌事件", description = "按交易日期倒序返回，默认近一年")
    @GetMapping("/suspends")
    public ApiResponse<StockQueryResultDTO<SuspendDDTO>> getSuspends(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "开始日期 yyyyMMdd，默认一年前")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd，默认今天")
            @RequestParam(required = false) String endDate,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "100") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        LocalDate today = LocalDate.now();
        String effectiveStart = startDate == null ? today.minusYears(1).format(DATE_FORMATTER) : startDate;
        String effectiveEnd = endDate == null ? today.format(DATE_FORMATTER) : endDate;

        List<StockSuspendDDO> records = stockSuspendDService.queryEventsByTsCode(symbol, effectiveStart, effectiveEnd);
        List<SuspendDDTO> items = records.stream()
                .sorted(Comparator.comparing(StockSuspendDDO::getTradeDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, SuspendDDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStart)
                .endDate(effectiveEnd)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询名称变更", description = "按起始日期倒序返回")
    @GetMapping("/namechanges")
    public ApiResponse<StockQueryResultDTO<NamechangeDTO>> getNamechanges(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "50") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        Map<String, List<StockNamechangeDO>> resultMap = stockNamechangeService.listByTsCodes(List.of(symbol));
        List<StockNamechangeDO> records = resultMap.get(symbol);
        List<NamechangeDTO> items = (records == null ? List.<StockNamechangeDO>of() : records).stream()
                .sorted(Comparator.comparing(StockNamechangeDO::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, NamechangeDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getStartDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }
}
