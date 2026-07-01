package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索建议项，用于输入框联想提示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestItemVO {

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;
}
