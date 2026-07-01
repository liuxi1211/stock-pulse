package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个因子的元数据描述。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorMetaVO {

    /** 因子唯一标识，全大写，例如 MA / MACD / CLOSE */
    private String factorKey;

    /** 中文展示名 */
    private String displayName;

    /** 分类，例如 TREND / MOMENTUM / VOLATILITY / VOLUME / PRICE */
    private String category;

    /** 描述说明 */
    private String description;

    /** 参数定义列表 */
    private List<FactorParamVO> params;

    /** 所需输入字段，例如 ["close"] 或 ["high","low","close"] */
    private List<String> inputs;

    /** 是否为多输出因子 */
    private Boolean multiOutput;

    /** 多输出时各条线的标签名，例如 ["DIF","DEA","HIST"] */
    private List<String> outputLabels;

    /** 默认输出列索引 */
    private Integer defaultOutputIndex;

    /** 预热 bar 数估算公式（字符串） */
    private String lookbackHint;

    /** 默认参数下所需预热 bar 数 */
    private Integer lookbackDefault;
}
