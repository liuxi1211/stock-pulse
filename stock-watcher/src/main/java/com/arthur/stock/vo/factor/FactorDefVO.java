package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个因子的完整元数据。与 resources/factor-definitions/*.json schema 一一对应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorDefVO {

    /** 因子唯一标识，全大写，例如 MA / MACD / CLOSE */
    private String factorKey;

    /** 中文展示名 */
    private String displayName;

    /** 分类，例如 TREND / MOMENTUM / VOLATILITY / VOLUME / PRICE */
    private String category;

    /** 描述说明 */
    private String description;

    /** 所需输入字段，例如 ["close"] 或 ["high","low","close"] */
    private List<String> inputs;

    /** 是否为多输出因子 */
    private Boolean multiOutput;

    /** 多输出时各条线的标签名，例如 ["DIF","DEA","HIST"] */
    private List<String> outputLabels;

    /** 参数定义列表 */
    private List<FactorParamVO> params;
}
