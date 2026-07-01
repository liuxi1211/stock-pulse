package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 市场排行视图对象，包含涨幅、跌幅、成交额排行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRankingVO {

    /** 交易日期 */
    private String tradeDate;

    /** 涨幅排行 TOP 10 */
    private List<StockRankVO> topGainers;

    /** 跌幅排行 TOP 10 */
    private List<StockRankVO> topLosers;

    /** 成交额排行 TOP 10 */
    private List<StockRankVO> topAmount;
}
