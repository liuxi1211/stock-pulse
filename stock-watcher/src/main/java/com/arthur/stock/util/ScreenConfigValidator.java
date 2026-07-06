package com.arthur.stock.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.constant.ComparatorEnum;
import com.arthur.stock.constant.ConditionNodeTypeEnum;
import com.arthur.stock.constant.DisplayableEnum;
import com.arthur.stock.constant.ExpressionKindEnum;
import com.arthur.stock.constant.LogicalOperatorEnum;
import com.arthur.stock.constant.RankingMethodEnum;
import com.arthur.stock.constant.RankingOrderEnum;
import com.arthur.stock.constant.ScreenerConstants;
import com.arthur.stock.constant.ScreenConfigFields;
import com.arthur.stock.constant.UniverseEnum;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 选股方案配置（screen_config）标准 Schema 校验器。
 * <p>
 * 按 engine {@code models/schemas/condition.py} / {@code screener.py} 的 Pydantic Schema 逐字段校验，
 * 确保任意来源（HTTP 建方案 / 修改方案 / 运行时 overrides）传入的配置都能被 engine 接收，
 * 把"格式错误"挡在落库 / 调 engine 之前。
 * <p>
 * 校验依据：spec 003 §3.2.1 / §3.2.2 / FR-1 / FR-2 / FR-6 / FR-7。
 * <p>
 * 用法：{@code ScreenConfigValidator.validate(req.getScreenConfig());}
 * 校验失败统一抛 {@link BusinessException}({@link ErrorCode#BAD_REQUEST})，
 * 错误信息带 JSON path，便于定位。
 *
 * @see ErrorCode#BAD_REQUEST
 */
public final class ScreenConfigValidator {

    private ScreenConfigValidator() {
    }

    private static String allowedCodes(Class<? extends Enum<? extends DisplayableEnum>> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .map(e -> ((DisplayableEnum) e).getCode())
                .collect(Collectors.joining(", "));
    }

    /**
     * 校验 screenConfig 全量 Schema。
     * <p>
     * 支持的输入形态（经 Jackson 反序列化后）：
     * <ul>
     *   <li>{@code Map} / {@code JSONObject}（最常见）</li>
     *   <li>JSON 文本字符串</li>
     *   <li>{@code null} / 空白字符串 → 直接报错</li>
     * </ul>
     *
     * @param screenConfig 待校验的配置（任意 JSON 树形态）
     * @throws BusinessException 配置非法时抛出，code = {@link ErrorCode#BAD_REQUEST}
     */
    public static void validate(Object screenConfig) {
        JSONObject obj = toJSONObject(screenConfig);
        if (obj == null || obj.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "方案配置必须是非空 JSON 对象（含 universe/conditions/ranking/filters 等）");
        }

        validateUniverse(obj);
        validateDate(obj);
        validateConditions(obj);
        validateRanking(obj);
        validateTopN(obj);
    }

    // ==================== 字段级校验 ====================

    private static void validateUniverse(JSONObject obj) {
        String universe = obj.getString(ScreenConfigFields.UNIVERSE);
        if (universe == null || universe.isBlank()) {
            return;
        }
        if (UniverseEnum.fromCode(universe) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "universe 非法: " + universe + "，允许值: " + allowedCodes(UniverseEnum.class));
        }
        if (UniverseEnum.MANUAL.getCode().equalsIgnoreCase(universe)) {
            var stocks = obj.getJSONArray(ScreenConfigFields.STOCKS);
            if (stocks == null || stocks.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "universe=manual 时必须配置非空 stocks 列表");
            }
            if (stocks.size() > ScreenerConstants.SCREEN_MANUAL_MAX_CANDIDATES) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "universe=manual 时 stocks 数量超过上限 "
                                + ScreenerConstants.SCREEN_MANUAL_MAX_CANDIDATES
                                + "（当前 " + stocks.size() + "）");
            }
        }
    }

    private static void validateDate(JSONObject obj) {
        String date = obj.getString(ScreenConfigFields.DATE);
        if (date == null || date.isBlank()) {
            return;
        }
        try {
            LocalDate.parse(date, ScreenerConstants.ISO_DATE);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "date 格式非法，必须 YYYY-MM-DD: " + date);
        }
    }

    private static void validateConditions(JSONObject obj) {
        Object conditions = obj.get(ScreenConfigFields.CONDITIONS);
        if (conditions != null) {
            validateConditionNode(conditions, ScreenConfigFields.CONDITIONS, 0);
        }
    }

    private static void validateRanking(JSONObject obj) {
        Object ranking = obj.get(ScreenConfigFields.RANKING);
        if (ranking != null) {
            validateRankingNode(ranking);
        }
    }

    private static void validateTopN(JSONObject obj) {
        Integer topN = obj.getInteger(ScreenConfigFields.TOP_N);
        if (topN != null && topN < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "topN 必须为非负整数（0 表示不截断）: " + topN);
        }
    }

    // ==================== 条件树递归校验 ====================

    /**
     * 递归校验条件节点。
     * <ul>
     *   <li>内部节点：{@code {operator: AND|OR, conditions: [非空数组]}}</li>
     *   <li>叶子节点：{@code {type: "compare", left: ExpressionNode, comparator, right: ExpressionNode}}</li>
     * </ul>
     * 字段对齐 engine condition.py（前端 screener.js 注释同源）。
     */
    private static void validateConditionNode(Object node, String path, int depth) {
        if (depth > ScreenerConstants.MAX_CONDITION_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "conditions 嵌套深度超过上限 " + ScreenerConstants.MAX_CONDITION_DEPTH + "，路径: " + path);
        }
        if (!(node instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "conditions 节点必须是 JSON 对象，路径: " + path);
        }
        JSONObject n = new JSONObject(rawMap);

        // 叶子节点：type=compare
        if (ConditionNodeTypeEnum.COMPARE.getCode().equals(n.getString(ScreenConfigFields.TYPE))) {
            validateCompareLeaf(n, path);
            return;
        }

        // 内部节点：operator + conditions[]
        String operator = n.getString(ScreenConfigFields.OPERATOR);
        if (operator == null || LogicalOperatorEnum.fromCode(operator) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "conditions.operator 非法: " + operator + "，允许值: " + allowedCodes(LogicalOperatorEnum.class)
                            + "，路径: " + path);
        }
        var children = n.getJSONArray(ScreenConfigFields.CONDITIONS);
        if (children == null || children.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "conditions[" + operator + "] 子节点不能为空，路径: " + path);
        }
        for (int i = 0; i < children.size(); i++) {
            validateConditionNode(children.get(i), path + "[" + operator + "][" + i + "]", depth + 1);
        }
    }

    /** 校验叶子节点 {type:'compare', left, comparator, right}。 */
    private static void validateCompareLeaf(JSONObject leaf, String path) {
        String comparator = leaf.getString(ScreenConfigFields.COMPARATOR);
        if (comparator == null || ComparatorEnum.fromCode(comparator) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "comparator 非法: " + comparator + "，选股仅允许: " + allowedCodes(ComparatorEnum.class)
                            + "（cross_up/cross_down 禁用），路径: " + path);
        }
        if (leaf.get("left") == null || leaf.get("right") == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "compare 叶子节点的 left/right 不能为空，路径: " + path);
        }
        validateExprNode(leaf.get("left"), path + ".left");
        validateExprNode(leaf.get("right"), path + ".right");
    }

    /**
     * 校验表达式节点 ExpressionNode。
     * <ul>
     *   <li>{@code {kind:"factor", factor:"MA", params?:{...}, outputIndex?:int}}</li>
     *   <li>{@code {kind:"value", value:number}}</li>
     *   <li>纯数字字面量（前端兼容写法）</li>
     * </ul>
     */
    private static void validateExprNode(Object expr, String path) {
        if (expr instanceof Number) {
            return;
        }
        if (!(expr instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "表达式节点必须是 JSON 对象或数值，路径: " + path);
        }
        JSONObject e = new JSONObject(rawMap);
        String kind = e.getString(ScreenConfigFields.KIND);
        if (kind == null || ExpressionKindEnum.fromCode(kind) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "表达式 kind 非法: " + kind + "，允许值: " + allowedCodes(ExpressionKindEnum.class) + "，路径: " + path);
        }
        if (ExpressionKindEnum.FACTOR.getCode().equals(kind)) {
            String factor = e.getString(ScreenConfigFields.FACTOR);
            if (factor == null || factor.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "factor 表达式缺少 factor 字段，路径: " + path);
            }
            Integer outputIndex = e.getInteger(ScreenConfigFields.OUTPUT_INDEX);
            if (outputIndex != null && outputIndex < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "outputIndex 必须为非负整数，路径: " + path);
            }
        } else {
            if (e.get(ScreenConfigFields.VALUE) == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "value 表达式缺少 value 字段，路径: " + path);
            }
        }
    }

    // ==================== ranking 校验（避免与字段级方法重名） ====================

    /**
     * 校验 ranking 配置（spec §3.2.1 / FR-6）。
     * <ul>
     *   <li>{@code method="single"}：需 factor + order(可空，默认 desc)</li>
     *   <li>{@code method="composite"}：需 weights（非空 factor→权重映射）</li>
     * </ul>
     */
    private static void validateRankingNode(Object ranking) {
        if (!(ranking instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ranking 必须是 JSON 对象");
        }
        JSONObject r = new JSONObject(rawMap);
        String method = r.getString(ScreenConfigFields.METHOD);
        if (method == null || RankingMethodEnum.fromCode(method) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ranking.method 非法: " + method + "，允许值: " + allowedCodes(RankingMethodEnum.class));
        }
        String order = r.getString(ScreenConfigFields.ORDER);
        if (order != null && RankingOrderEnum.fromCode(order) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ranking.order 非法: " + order + "，允许值: " + allowedCodes(RankingOrderEnum.class));
        }
        if (RankingMethodEnum.SINGLE.getCode().equals(method)) {
            String factor = r.getString(ScreenConfigFields.FACTOR);
            if (factor == null || factor.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "ranking.method=single 时必须配置非空 factor");
            }
        } else {
            Object weights = r.get(ScreenConfigFields.WEIGHTS);
            if (!(weights instanceof Map<?, ?> wMap) || wMap.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "ranking.method=composite 时必须配置非空 weights（factor→权重）");
            }
            for (var entry : wMap.entrySet()) {
                if (!(entry.getValue() instanceof Number)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "ranking.weights 权重值必须为数值: " + entry.getKey());
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 任意输入（Object/String/JSON 文本）→ JSONObject；非对象或解析失败返回 null。 */
    private static JSONObject toJSONObject(Object screenConfig) {
        if (screenConfig == null) {
            return null;
        }
        if (screenConfig instanceof CharSequence cs && cs.toString().trim().isEmpty()) {
            return null;
        }
        Object parsed;
        try {
            parsed = JSON.parse(JSON.toJSONString(screenConfig));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "方案配置 JSON 解析失败: " + e.getMessage());
        }
        return parsed instanceof JSONObject jo ? jo : null;
    }
}
