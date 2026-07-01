package com.arthur.stock.util;

import java.util.Map;

/**
 * 因子 key 命名工具。
 *
 * <p>负责 Java 端 ↔ Python 端 结果列名的统一规则。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>单输出 + 单参数（如 MA(timeperiod=5)）→ key = MA_5</li>
 *   <li>单输出 + 多参数（如 KDJ(fastk=9,slowk=3,slowd=3)）→ key = KDJ_9_3_3</li>
 *   <li>单输出 + 无参数（如 CLOSE）→ key = CLOSE</li>
 *   <li>多输出（如 MACD(fastperiod=12,slowperiod=26,signalperiod=9)）→ key = MACD_12_26_9，
 *       Python 端会进一步追加 _DIF/_DEA/_HIST 等标签</li>
 *   <li>无 params 或 params 为空 → 直接返回 factorKey</li>
 * </ul>
 */
public final class FactorKeyUtil {

    /**
     * 根据 factorKey + params 生成请求侧 key。
     * 该 key 会被 Python 端用作结果列名（或多输出时的列名前缀）。
     *
     * @param factorKey 因子 key，如 "MA" / "MACD"
     * @param params    参数，如 {"timeperiod": 5}
     * @return 生成的请求侧 key
     */
    public static String buildRequestKey(String factorKey, Map<String, Object> params) {
        if (factorKey == null || factorKey.isBlank()) {
            return factorKey;
        }
        if (params == null || params.isEmpty()) {
            return factorKey;
        }
        StringBuilder sb = new StringBuilder(factorKey);
        // 按参数名排序，保证同一组参数的 key 稳定
        params.keySet().stream().sorted().forEach(name -> {
            Object v = params.get(name);
            if (v != null) {
                sb.append('_').append(formatValue(v));
            }
        });
        // 没有任何合法参数值时，返回原始 factorKey
        return sb.length() == factorKey.length() ? factorKey : sb.toString();
    }

    private static String formatValue(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return v.toString();
    }

    private FactorKeyUtil() {
    }
}
