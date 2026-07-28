package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.dto.stock.StockQueryMetaDTO;
import com.arthur.stock.dto.stock.StockQueryResultDTO;
import com.arthur.stock.model.HkHoldDO;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;
import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.HkHoldService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.TopListService;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Tag(name = "个股诊断-资金面", description = "个股资金面数据查询：资金流向、北向持股、龙虎榜、大宗交易")
@RestController
@RequestMapping("/stocks/{tsCode}")
@RequiredArgsConstructor
public class StockMoneyflowController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_DAYS = 250;

    private final MoneyflowService moneyflowService;
    private final HkHoldService hkHoldService;
    private final TopListService topListService;
    private final BlockTradeService blockTradeService;

    // ==================== moneyflows ====================

    @Operation(summary = "个股资金流向", description = "按交易日升序返回，默认最近30天，days最大250")
    @GetMapping("/moneyflows")
    public ApiResponse<StockQueryResultDTO<MoneyflowDO>> getMoneyflows(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "最近天数，1-250，默认30")
            @RequestParam(defaultValue = "30") int days) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveDays = validateDays(days);

        List<MoneyflowDO> items = moneyflowService.queryDetail(symbol, effectiveDays);
        String dataAsOf = items.isEmpty() ? null : items.getLast().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .limit(effectiveDays)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    // ==================== hk-holds ====================

    @Operation(summary = "北向持股", description = "按交易日升序返回，支持 range(3M/1Y/ALL) 或自定义日期范围")
    @GetMapping("/hk-holds")
    public ApiResponse<StockQueryResultDTO<HkHoldDO>> getHkHolds(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "范围：3M/1Y/ALL，默认3M；指定 startDate/endDate 时忽略 range")
            @RequestParam(defaultValue = "3M") String range,
            @Parameter(description = "开始日期 yyyyMMdd")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd")
            @RequestParam(required = false) String endDate) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);

        String effectiveStart;
        String effectiveEnd;
        if (startDate != null || endDate != null) {
            LocalDate today = LocalDate.now();
            effectiveStart = startDate == null ? today.minusMonths(3).format(DATE_FORMATTER) : startDate;
            effectiveEnd = endDate == null ? today.format(DATE_FORMATTER) : endDate;
        } else {
            String[] dateRange = resolveRange(range);
            effectiveStart = dateRange[0];
            effectiveEnd = dateRange[1];
        }

        List<HkHoldDO> items = hkHoldService.queryByCodeAndDateRange(symbol, effectiveStart, effectiveEnd);
        String dataAsOf = items.isEmpty() ? null : items.getLast().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStart)
                .endDate(effectiveEnd)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    // ==================== top-lists ====================

    @Operation(summary = "龙虎榜", description = "按交易日倒序返回，默认查询近一年，limit控制返回条数")
    @GetMapping("/top-lists")
    public ApiResponse<StockQueryResultDTO<TopListDO>> getTopLists(
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

        List<TopListDO> records = topListService.queryByCodeAndDateRange(symbol, effectiveStart, effectiveEnd);
        List<TopListDO> items = records.stream()
                .limit(effectiveLimit)
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

    // ==================== top-lists seats ====================

    @Operation(summary = "龙虎榜席位明细", description = "查询单日单股的龙虎榜营业部席位明细")
    @GetMapping("/top-lists/{tradeDate}/seats")
    public ApiResponse<StockQueryResultDTO<TopInstDO>> getTopListSeats(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "交易日 yyyyMMdd", required = true)
            @PathVariable String tradeDate) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.parseDate(tradeDate, "tradeDate");

        List<TopInstDO> items = topListService.queryInst(tradeDate, symbol);
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(tradeDate)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(items, meta));
    }

    // ==================== block-trades ====================

    @Operation(summary = "大宗交易", description = "按交易日倒序返回，默认近三个月，内存分页，含折溢价率")
    @GetMapping("/block-trades")
    public ApiResponse<StockQueryResultDTO<BlockTradeWithCloseVO>> getBlockTrades(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @PathVariable String tsCode,
            @Parameter(description = "开始日期 yyyyMMdd，默认三个月前")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期 yyyyMMdd，默认今天")
            @RequestParam(required = false) String endDate,
            @Parameter(description = "页码，从1开始")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，1-100")
            @RequestParam(defaultValue = "20") int size) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        StockQueryValidator.validateDateRange(startDate, endDate);
        int effectivePage = StockQueryValidator.validatePage(page);
        int effectiveSize = StockQueryValidator.validateSize(size);

        LocalDate today = LocalDate.now();
        String effectiveStart = startDate == null ? today.minusMonths(3).format(DATE_FORMATTER) : startDate;
        String effectiveEnd = endDate == null ? today.format(DATE_FORMATTER) : endDate;

        List<BlockTradeWithCloseVO> all = blockTradeService.queryByCodeAndDateRange(symbol, effectiveStart, effectiveEnd);
        fillPremiumRate(all);

        int total = all.size();
        int fromIndex = (effectivePage - 1) * effectiveSize;
        List<BlockTradeWithCloseVO> pageItems = fromIndex >= total
                ? Collections.emptyList()
                : all.subList(fromIndex, Math.min(fromIndex + effectiveSize, total));

        String dataAsOf = pageItems.isEmpty() ? null : pageItems.getFirst().getTradeDate();
        StockQueryMetaDTO meta = StockQueryMetaDTO.builder()
                .symbol(symbol)
                .dataAsOf(dataAsOf)
                .startDate(effectiveStart)
                .endDate(effectiveEnd)
                .page(effectivePage)
                .size(effectiveSize)
                .total(total)
                .build();
        return ApiResponse.success(new StockQueryResultDTO<>(pageItems, meta));
    }

    // ==================== 内部方法 ====================

    private int validateDays(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days必须在1到" + MAX_DAYS + "之间");
        }
        return days;
    }

    private String[] resolveRange(String range) {
        LocalDate today = LocalDate.now();
        String end = today.format(DATE_FORMATTER);
        return switch (range == null ? "3M" : range.toUpperCase()) {
            case "3M" -> new String[]{today.minusMonths(3).format(DATE_FORMATTER), end};
            case "1Y" -> new String[]{today.minusYears(1).format(DATE_FORMATTER), end};
            case "ALL" -> new String[]{"00000000", "99999999"};
            default -> throw new IllegalArgumentException("range必须为3M、1Y或ALL");
        };
    }

    private void fillPremiumRate(List<BlockTradeWithCloseVO> items) {
        for (BlockTradeWithCloseVO item : items) {
            BigDecimal closePrice = item.getClosePrice();
            BigDecimal price = item.getPrice();
            if (closePrice != null && closePrice.compareTo(BigDecimal.ZERO) != 0 && price != null) {
                BigDecimal premiumRate = price.subtract(closePrice)
                        .divide(closePrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
                item.setPremiumRate(premiumRate);
            }
        }
    }
}
