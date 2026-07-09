package com.arthur.stock.util;

import com.arthur.stock.dto.strategy.StrategyDiffDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON 配置递归 Diff 工具（spec 004 Task 6）。
 * <p>
 * 用于策略版本对比：把两份 configJson 解析成 Map/List/原始值，按结构递归比较，
 * 输出 {@link StrategyDiffDTO} 列表。规则：
 * <ul>
 *   <li><b>Map 按 key 遍历</b>：仅在 from 出现 → removed；仅在 to 出现 → added；都有 → 递归。</li>
 *   <li><b>List 按索引逐一比较（不做 LCS）</b>：同长度段逐元素递归；多出的尾部按 added/removed 处理。</li>
 *   <li><b>类型不同（如一边 Map 一边 List 或一边原始值）</b>：直接标 modified（含 oldValue/newValue）。</li>
 *   <li><b>原始值（String/Number/Boolean）</b>：不等 → modified。</li>
 *   <li><b>null</b>：null vs 非null → added/removed；都 null → 无变化。</li>
 * </ul>
 * path 点号分隔，数组索引用 {@code [n]}，例如 {@code trading_config.signals.buy.conditions[0].left.factor}。
 */
public final class JsonDiffUtil {

    private JsonDiffUtil() {
    }

    /**
     * 比较两个配置对象，输出变更列表。
     *
     * @param fromJson 旧版本配置 JSON 字符串（解析为 Map）
     * @param toJson   新版本配置 JSON 字符串（解析为 Map）
     * @return 变更项列表，可能为空
     */
    public static List<StrategyDiffDTO> diff(String fromJson, String toJson) {
        Object from = parse(fromJson);
        Object to = parse(toJson);
        List<StrategyDiffDTO> out = new ArrayList<>();
        diff(from, to, "", out);
        return out;
    }

    /**
     * 比较两个已解析的配置对象。
     */
    public static List<StrategyDiffDTO> diff(Object from, Object to) {
        List<StrategyDiffDTO> out = new ArrayList<>();
        diff(from, to, "", out);
        return out;
    }

    // ==================== 内部递归 ====================

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void diff(Object from, Object to, String path, List<StrategyDiffDTO> out) {
        // 1. null 处理
        if (from == null && to == null) {
            return;
        }
        if (from == null) {
            out.add(new StrategyDiffDTO(path(path), "added", null, to));
            return;
        }
        if (to == null) {
            out.add(new StrategyDiffDTO(path(path), "removed", from, null));
            return;
        }

        // 2. 类型不同 → modified
        if (!sameContainerType(from, to)) {
            if (!scalarEquals(from, to)) {
                out.add(new StrategyDiffDTO(path(path), "modified", from, to));
            }
            return;
        }

        // 3. 都是 Map
        if (from instanceof Map && to instanceof Map) {
            Map<String, Object> fm = (Map<String, Object>) from;
            Map<String, Object> tm = (Map<String, Object>) to;
            // from 的 key：removed 或 递归
            for (Map.Entry<String, Object> e : fm.entrySet()) {
                String k = e.getKey();
                String childPath = joinKey(path, k);
                if (!tm.containsKey(k)) {
                    out.add(new StrategyDiffDTO(childPath, "removed", e.getValue(), null));
                } else {
                    diff(e.getValue(), tm.get(k), childPath, out);
                }
            }
            // to 独有的 key：added
            for (Map.Entry<String, Object> e : tm.entrySet()) {
                if (!fm.containsKey(e.getKey())) {
                    out.add(new StrategyDiffDTO(joinKey(path, e.getKey()), "added", null, e.getValue()));
                }
            }
            return;
        }

        // 4. 都是 List
        if (from instanceof List && to instanceof List) {
            List<?> fl = (List<?>) from;
            List<?> tl = (List<?>) to;
            int min = Math.min(fl.size(), tl.size());
            for (int i = 0; i < min; i++) {
                diff(fl.get(i), tl.get(i), joinIndex(path, i), out);
            }
            // to 多出 → added
            for (int i = min; i < tl.size(); i++) {
                out.add(new StrategyDiffDTO(joinIndex(path, i), "added", null, tl.get(i)));
            }
            // from 多出 → removed
            for (int i = min; i < fl.size(); i++) {
                out.add(new StrategyDiffDTO(joinIndex(path, i), "removed", fl.get(i), null));
            }
            return;
        }

        // 5. 原始值
        if (!scalarEquals(from, to)) {
            out.add(new StrategyDiffDTO(path(path), "modified", from, to));
        }
    }

    /** 是否同为 Map 或同为 List。 */
    private static boolean sameContainerType(Object a, Object b) {
        boolean aMap = a instanceof Map;
        boolean bMap = b instanceof Map;
        if (aMap || bMap) {
            return aMap && bMap;
        }
        boolean aList = a instanceof List;
        boolean bList = b instanceof List;
        if (aList || bList) {
            return aList && bList;
        }
        return true;
    }

    /**
     * 原始值相等判定。Number 做值比较（1 == 1.0），其余走 equals。
     */
    private static boolean scalarEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    /** path 空时返回根占位 ""，否则原样返回（顶层不应出现 modified/added/removed 的路径）。 */
    private static String path(String path) {
        return path == null ? "" : path;
    }

    /** 拼接 Map 子键：path + "." + key。顶层 path 为空时直接返回 key。 */
    private static String joinKey(String path, String key) {
        return (path == null || path.isEmpty()) ? key : path + "." + key;
    }

    /** 拼接 List 索引：path + "[n]"。 */
    private static String joinIndex(String path, int idx) {
        return (path == null ? "" : path) + "[" + idx + "]";
    }

    /**
     * 解析 JSON 字符串。
     * <p>
     * fastjson2 的 JSON 对象实现 {@link Map}、JSON 数组实现 {@link List}，
     * 所以直接返回 fastjson2 解析结果即可，递归里的 {@code instanceof Map/List} 对它们成立。
     * 空串返回 null；解析失败返回原始字符串（保证 diff 不抛异常）。
     */
    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return com.alibaba.fastjson2.JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
    }
}
