package com.arthur.stock.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 大宗交易 + 收盘价 JOIN 结果 VO。
 * <p>
 * block_trade LEFT JOIN daily_quote 后的查询结果，
 * closePrice 来源于 daily_quote.close，用于计算大宗交易折溢价率。
 */
@Data
public class BlockTradeWithCloseVO {

    private String tradeDate;
    private String tsCode;
    private String name;
    private BigDecimal price;
    private BigDecimal vol;
    private BigDecimal amount;
    private String buyer;
    private String seller;
    private String buyerName;
    private String sellerName;

    /** 收盘价（来自 daily_quote.close JOIN） */
    private BigDecimal closePrice;
}
