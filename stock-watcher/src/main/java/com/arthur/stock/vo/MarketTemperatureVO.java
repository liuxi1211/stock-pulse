package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 市场温度视图对象，用于展示市场涨跌统计与涨跌停数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketTemperatureVO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 涨家数 */
    private int upCount;

    /** 跌家数 */
    private int downCount;

    /** 平家数 */
    private int flatCount;

    /** 涨停数 */
    private int limitUpCount;

    /** 跌停数 */
    private int limitDownCount;
}
