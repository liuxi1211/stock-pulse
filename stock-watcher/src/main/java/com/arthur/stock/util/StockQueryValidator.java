package com.arthur.stock.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class StockQueryValidator {

    public static final int MAX_LIMIT = 500;
    public static final int MAX_PAGE = 100_000;
    public static final int MAX_SIZE = 100;

    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("^\\d{6}\\.(SH|SZ|BJ)$");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private StockQueryValidator() {
    }

    public static String requireStockCode(String stockCode) {
        if (stockCode == null || !STOCK_CODE_PATTERN.matcher(stockCode).matches()) {
            throw new IllegalArgumentException("股票代码格式必须为6位数字.SH、.SZ或.BJ");
        }
        return stockCode;
    }

    public static LocalDate parseDate(String value, String parameterName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameterName + "不能为空");
        }
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(parameterName + "格式必须为yyyyMMdd");
        }
    }

    public static void validateDateRange(String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("startDate不能晚于endDate");
        }
    }

    public static int validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit必须在1到" + MAX_LIMIT + "之间");
        }
        return limit;
    }

    public static int validatePage(int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw new IllegalArgumentException("page必须在1到" + MAX_PAGE + "之间");
        }
        return page;
    }

    public static int validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size必须在1到" + MAX_SIZE + "之间");
        }
        return size;
    }
}
