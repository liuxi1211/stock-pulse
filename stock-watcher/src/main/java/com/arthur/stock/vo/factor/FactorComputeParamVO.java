package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 计算请求中单个因子：factorKey + 自定义 params。
 * 同时携带用于结果 key 命名的可选字段：
 * - requestKey：传入 Python 后作为单输出结果 key；多输出时作为前缀
 * - outputLabels：传入 Python 后，多输出因子的列名将使用语义标签而非数字索引
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorComputeParamVO {

    /** 因子 key，例如 MA / MACD */
    private String factorKey;

    /** 因子参数，key 为参数名，value 为具体数值 */
    private Map<String, Object> params;

    /** 结果 key 的名称；如 MA_5、MACD 等。未提供时 Python 端回退为 factorKey。 */
    private String requestKey;

    /** 多输出时各条线的语义标签；如 ["DIF","DEA","HIST"]。未提供时 Python 端用数字索引。 */
    private List<String> outputLabels;
}
