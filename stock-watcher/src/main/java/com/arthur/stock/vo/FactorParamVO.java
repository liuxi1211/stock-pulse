package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 因子参数视图对象（与 engine FactorParam 对齐，camelCase）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorParamVO {

    /** 参数键名，透传给 akquant.talib */
    private String name;

    /** 展示名 */
    private String displayName;

    /** 参数类型：INT / FLOAT */
    private String type;

    /** 默认值 */
    private Double defaultValue;

    /** 最小值（含） */
    private Double min;

    /** 最大值（含） */
    private Double max;

    /** 步长 */
    private Double step;
}
