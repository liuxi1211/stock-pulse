package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorComputeResultVO {

    /** 请求唯一标识（Python 侧响应为 requestId / taskId 任一均兼容） */
    private String requestId;

    private String taskId;

    /** Python 计算耗时（毫秒） */
    private Double computeMs;

    private List<String> dates;

    /** key 为结果 key；value 为与 dates 等长的数值数组 */
    private Map<String, List<Double>> results;
}
