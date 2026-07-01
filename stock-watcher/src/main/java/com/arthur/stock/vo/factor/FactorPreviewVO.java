package com.arthur.stock.vo.factor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 前端因子预览响应，额外携带 tsCode / stockName / ohlcv，便于 ECharts 绘图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorPreviewVO {

    private String tsCode;

    private String stockName;

    private Double computeMs;

    private List<String> dates;

    /** key 为结果 key（如 MA_5 / MACD_DIF / close 等）；value 为等长数值数组。 */
    private java.util.Map<String, List<Double>> results;

    private java.util.Map<String, List<Double>> ohlcv;
}
