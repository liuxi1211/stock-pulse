package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 因子参数定义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorParamVO {

    private String name;

    private String displayName;

    /** "INT" / "FLOAT" / "ENUM" */
    private String type;

    private Object defaultValue;

    private Double min;

    private Double max;

    private Double step;

    /** enumValues 元素通常为 {"value":..., "label": "..."}；按 PRD 类型为 List&lt;Map&lt;String,Object&gt;&gt;。 */
    private List<Map<String, Object>> enumValues;
}
