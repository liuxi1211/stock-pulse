package com.arthur.stock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 因子预计算白名单配置（技术面因子常用参数组合）。
 * <p>
 * 每日收盘后 {@code FactorSnapshotTask} 对白名单内的 (factorKey, params) 组合批量预计算并入库。
 * 选股时白名单内因子走表查，未命中（自定义参数）透明回退到 engine 实时算。
 * <p>
 * 可通过 {@code application.yml} 的 {@code stock.screener.precompute.whitelist} 覆盖。
 */
@Configuration
@ConfigurationProperties(prefix = "stock.screener.precompute")
@Data
public class FactorPrecomputeConfig {

    /** 启用预计算的因子参数白名单：factorKey -> 参数组合列表（每个 Map 是一组 params） */
    private Map<String, List<Map<String, Object>>> whitelist = defaultWhitelist();

    private static Map<String, List<Map<String, Object>>> defaultWhitelist() {
        Map<String, List<Map<String, Object>>> m = new LinkedHashMap<>();
        // 趋势均线
        m.put("MA", List.of(p("timeperiod", 5), p("timeperiod", 10), p("timeperiod", 20),
                p("timeperiod", 60), p("timeperiod", 120), p("timeperiod", 250)));
        m.put("EMA", List.of(p("timeperiod", 5), p("timeperiod", 10), p("timeperiod", 12),
                p("timeperiod", 20), p("timeperiod", 26)));
        // 动量
        m.put("RSI", List.of(p("timeperiod", 6), p("timeperiod", 12), p("timeperiod", 14), p("timeperiod", 24)));
        m.put("MACD", List.of(p3("fastperiod", 12, "slowperiod", 26, "signalperiod", 9)));
        m.put("KDJ", List.of(p3("fastk_period", 9, "slowk_period", 3, "slowd_period", 3)));
        m.put("CCI", List.of(p("timeperiod", 14)));
        m.put("WILLR", List.of(p("timeperiod", 14)));
        m.put("ADX", List.of(p("timeperiod", 14)));
        m.put("ROC", List.of(p("timeperiod", 12)));
        // 波动率
        m.put("BOLL", List.of(p3("timeperiod", 20, "nbdevup", 2, "nbdevdn", 2)));
        m.put("ATR", List.of(p("timeperiod", 14)));
        // 成交量
        m.put("VOL_MA", List.of(p("timeperiod", 5), p("timeperiod", 10)));
        m.put("VOL_EMA", List.of(p("timeperiod", 5), p("timeperiod", 10)));
        // 统计
        m.put("STDDEV", List.of(p("timeperiod", 20)));
        // 无参因子（空 params = 全量预计算）
        m.put("SAR", List.of(Map.of()));
        m.put("OBV", List.of(Map.of()));
        return m;
    }

    private static Map<String, Object> p(String k, Object v) {
        return Map.of(k, v);
    }

    private static Map<String, Object> p3(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }
}
