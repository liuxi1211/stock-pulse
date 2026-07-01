package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 发送给 Python 计算服务的请求体 VO（主要用于类型自查/文档说明）。
 * 实际调用时仍使用 Map&lt;String,Object&gt; 以便 fastjson 灵活序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorComputeRequestVO {

    private String requestId;

    private String stockCode;

    private List<Map<String, Object>> ohlcv;

    private List<FactorComputeParamVO> factors;
}
