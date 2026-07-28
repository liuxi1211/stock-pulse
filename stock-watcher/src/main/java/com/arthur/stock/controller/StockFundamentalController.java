package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.stock.StockQueryMetaDTO;
import com.arthur.stock.dto.stock.StockQueryResultDTO;
import com.arthur.stock.dto.tushare.BalancesheetDTO;
import com.arthur.stock.dto.tushare.CashflowDTO;
import com.arthur.stock.dto.tushare.DailyBasicDTO;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.dto.tushare.ExpressDTO;
import com.arthur.stock.dto.tushare.FinaIndicatorDTO;
import com.arthur.stock.dto.tushare.ForecastDTO;
import com.arthur.stock.dto.tushare.IncomeDTO;
import com.arthur.stock.model.BalancesheetDO;
import com.arthur.stock.model.CashflowDO;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.ExpressDO;
import com.arthur.stock.model.FinaIndicatorDO;
import com.arthur.stock.model.ForecastDO;
import com.arthur.stock.model.IncomeDO;
import com.arthur.stock.service.BalancesheetService;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.DailyBasicService;
import com.arthur.stock.service.DividendService;
import com.arthur.stock.service.ExpressService;
import com.arthur.stock.service.FinaIndicatorService;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.IncomeService;
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

@Tag(name = "个股诊断-基本面", description = "个股基本面数据查询")
@RestController
@RequestMapping("/stocks/{tsCode}")
@RequiredArgsConstructor
public class StockFundamentalController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final DailyBasicService dailyBasicService;
    private final FinaIndicatorService finaIndicatorService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;
    private final DividendService dividendService;

    @Operation(summary = "查询每日基本面", description = "按交易日期升序返回，默认查询近五年，limit控制返回条数")
    @GetMapping("/daily-basics")
    public ApiResponse<StockQueryResultDTO<DailyBasicDTO>> getDailyBasics(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "开始日期 yyyyMMdd，默认五年前")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd，默认今天")
            @RequestParam(required = false) String endDate,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "500") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        LocalDate today = LocalDate.now();
        String effectiveStart = startDate == null ? today.minusYears(5).format(DATE_FORMATTER) : startDate;
        String effectiveEnd = endDate == null ? today.format(DATE_FORMATTER) : endDate;

        List<DailyBasicDO> records = dailyBasicService.listByCodeAndDateRange(symbol, effectiveStart, effectiveEnd);
        List<DailyBasicDTO> items = records.stream()
                .skip(Math.max(0, records.size() - effectiveLimit))
                .map(do_ -> DtoConverter.convert(do_, DailyBasicDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getLast().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStart)
                .endDate(effectiveEnd)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询财务指标", description = "按报告期倒序返回，取最近limit条")
    @GetMapping("/fina-indicators")
    public ApiResponse<StockQueryResultDTO<FinaIndicatorDTO>> getFinaIndicators(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "20") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<FinaIndicatorDO> records = finaIndicatorService.queryLocalByTsCode(symbol);
        List<FinaIndicatorDTO> items = records.stream()
                .sorted(Comparator.comparing(FinaIndicatorDO::getEndDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, FinaIndicatorDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询利润表", description = "按报告期倒序返回，取最近limit条")
    @GetMapping("/incomes")
    public ApiResponse<StockQueryResultDTO<IncomeDTO>> getIncomes(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "20") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<IncomeDO> records = incomeService.queryLocalByTsCode(symbol);
        List<IncomeDTO> items = records.stream()
                .sorted(Comparator.comparing(IncomeDO::getEndDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, IncomeDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询资产负债表", description = "按报告期倒序返回，取最近limit条")
    @GetMapping("/balancesheets")
    public ApiResponse<StockQueryResultDTO<BalancesheetDTO>> getBalancesheets(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "20") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<BalancesheetDO> records = balancesheetService.queryLocalByTsCode(symbol);
        List<BalancesheetDTO> items = records.stream()
                .sorted(Comparator.comparing(BalancesheetDO::getEndDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, BalancesheetDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询现金流量表", description = "按报告期倒序返回，取最近limit条")
    @GetMapping("/cashflows")
    public ApiResponse<StockQueryResultDTO<CashflowDTO>> getCashflows(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "20") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<CashflowDO> records = cashflowService.queryLocalByTsCode(symbol);
        List<CashflowDTO> items = records.stream()
                .sorted(Comparator.comparing(CashflowDO::getEndDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, CashflowDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询业绩预告", description = "按公告日期倒序返回，取最近limit条")
    @GetMapping("/forecasts")
    public ApiResponse<StockQueryResultDTO<ForecastDTO>> getForecasts(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "50") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<ForecastDO> records = forecastService.queryLocalByTsCode(symbol);
        List<ForecastDTO> items = records.stream()
                .sorted(Comparator.comparing(ForecastDO::getAnnDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, ForecastDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询业绩快报", description = "按公告日期倒序返回，取最近limit条")
    @GetMapping("/expresses")
    public ApiResponse<StockQueryResultDTO<ExpressDTO>> getExpresses(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "50") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<ExpressDO> records = expressService.queryLocalByTsCode(symbol);
        List<ExpressDTO> items = records.stream()
                .sorted(Comparator.comparing(ExpressDO::getAnnDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(do_ -> DtoConverter.convert(do_, ExpressDTO.class))
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    @Operation(summary = "查询分红送股", description = "按公告日期倒序返回，取最近limit条")
    @GetMapping("/dividends")
    public ApiResponse<StockQueryResultDTO<DividendDTO>> getDividends(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最多返回条数，1-500")
            @RequestParam(defaultValue = "50") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);

        List<DividendDTO> all = dividendService.queryByTsCode(symbol);
        List<DividendDTO> items = all.stream()
                .sorted(Comparator.comparing(DividendDTO::getAnnDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .toList();

        String dataAsOf = items.isEmpty() ? null : items.getFirst().getAnnDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveLimit)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }
}
