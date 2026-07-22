package com.arthur.stock.util;

import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具类。
 * 用于在错误信息、错误堆栈写入数据库前脱敏处理。
 */
public final class SensitiveDataUtil {

    private SensitiveDataUtil() {}

    // Tushare token pattern: token=xxx, "token":"xxx", token: xxx (20+ hex chars)
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "(token[\"\\s:=]+)[\"']?([a-fA-F0-9]{20,})[\"']?", Pattern.CASE_INSENSITIVE);

    // JDBC connection string with password: jdbc:mysql://user:password@host
    private static final Pattern JDBC_PASSWORD_PATTERN = Pattern.compile(
        "(jdbc:[a-z]+://[^:]+:)([^@]+)(@)", Pattern.CASE_INSENSITIVE);

    // Password in properties: password=xxx, "password":"xxx", password: xxx
    // Group 3 captures the terminator so it is preserved (avoids merging lines in stack traces)
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
        "(password[\"\\s:=]+)[\"']?(\\S+?)[\"']?([,\\s;}\\]]|$)", Pattern.CASE_INSENSITIVE);

    // API key / secret patterns: apiKey=xxx, secret=xxx, "apiKey":"xxx"
    // Group 3 captures the terminator so it is preserved
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
        "((?:api[_-]?key|secret)[\"\\s:=]+)[\"']?(\\S+?)[\"']?([,\\s;}\\]]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 对文本进行脱敏处理，替换所有已知敏感信息模式。
     *
     * @param text 待脱敏文本
     * @return 脱敏后的文本，若输入为 null 或空则原样返回
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = TOKEN_PATTERN.matcher(result).replaceAll("$1***");
        result = JDBC_PASSWORD_PATTERN.matcher(result).replaceAll("$1***$3");
        result = PASSWORD_PATTERN.matcher(result).replaceAll("$1***$3");
        result = API_KEY_PATTERN.matcher(result).replaceAll("$1***$3");
        return result;
    }
}
