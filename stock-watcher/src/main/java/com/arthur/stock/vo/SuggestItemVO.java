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

    /** 股票代码（6位 symbol，如 000001，向后兼容 dashboard 用法） */
    private String code;

    /** 股票名称 */
    private String name;

    /** tsCode（带交易所后缀，如 000001.SZ，选股方案 stocks 口径） */
    private String tsCode;

    /** 申万一级行业名称（无匹配时为 null） */
    private String industryName;
}
