package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 因子视图对象，与 engine ``FactorDef`` 字段完全对齐（camelCase）。
 * <p>
 * watcher 仅缓存与透传元数据，不参与计算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorVO {

    /** 唯一标识，大写蛇形 */
    private String factorKey;

    /** 展示名 */
    private String displayName;

    /** 分类 key */
    private String category;

    /** 来源：AKQUANT / TUSHARE / RAW / DERIVED */
    private String source;

    /** akquant.talib 函数名（AKQUANT/DERIVED） */
    private String akquantFunc;

    /** Tushare 字段名（TUSHARE） */
    private String tushareField;

    /** 数据来源标记 */
    private String dataSource;

    /** 一句话语义说明 */
    private String description;

    /** 参数定义列表 */
    private List<FactorParamVO> params;

    /** 所需 OHLCV 列 */
    private List<String> inputs;

    /** 是否多输出 */
    private Boolean multiOutput;

    /** 多输出标签 */
    private List<String> outputLabels;

    /** 默认输出序列下标 */
    private Integer defaultOutputIndex;

    /** 回看长度表达式（基于参数） */
    private String lookbackHint;

    /** 默认参数下的回看长度（bars） */
    private Integer lookbackDefault;
}
