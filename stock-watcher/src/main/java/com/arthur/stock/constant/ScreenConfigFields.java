package com.arthur.stock.constant;

/**
 * screen_config JSON 字段名常量类。
 * <p>
 * 对齐 engine models/schemas/condition.py / screener.py 的字段名，
 * 避免 watcher 侧多处硬编码同一 key 导致拼写漂移。
 */
public final class ScreenConfigFields {

    private ScreenConfigFields() {
    }

    public static final String UNIVERSE = "universe";
    public static final String DATE = "date";
    public static final String CONDITIONS = "conditions";
    public static final String RANKING = "ranking";
    public static final String FILTERS = "filters";
    public static final String TOP_N = "topN";
    public static final String STOCKS = "stocks";
    public static final String OVERRIDES = "overrides";

    /** 条件树内部节点字段。 */
    public static final String OPERATOR = "operator";

    /** 条件树叶子节点字段。 */
    public static final String COMPARATOR = "comparator";

    /** 叶子节点类型标识（值见 {@link ConditionNodeTypeEnum}）。 */
    public static final String TYPE = "type";

    /** 表达式节点 kind（值见 {@link ExpressionKindEnum}）。 */
    public static final String KIND = "kind";

    public static final String FACTOR = "factor";
    public static final String VALUE = "value";

    /** ranking.method（值见 {@link RankingMethodEnum}）。 */
    public static final String METHOD = "method";

    /** ranking.order（值见 {@link RankingOrderEnum}）。 */
    public static final String ORDER = "order";

    /** ranking.weights（composite 模式：factor → 权重）。 */
    public static final String WEIGHTS = "weights";

    public static final String OUTPUT_INDEX = "outputIndex";
}
