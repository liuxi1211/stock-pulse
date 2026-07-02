package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 因子分类视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorCategoryVO {

    /** 分类 key，如 OVERLAP */
    private String key;

    /** 分类展示名，如 趋势指标 */
    private String name;

    /** 该分类默认来源 */
    private String source;
}
