package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 大盘指数视图对象，用于前端展示大盘行情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketIndexVO {

    /** 指数代码 */
    private String code;

    /** 指数名称 */
    private String name;

    /** 当前点位 */
    private BigDecimal currentPoint;

    /** 涨跌点数 */
    private BigDecimal changeAmount;

    /** 涨跌幅（%） */
    private BigDecimal changePercent;

    /** 成交量（手） */
    private Long volume;

    /** 成交额 */
    private String turnover;
}