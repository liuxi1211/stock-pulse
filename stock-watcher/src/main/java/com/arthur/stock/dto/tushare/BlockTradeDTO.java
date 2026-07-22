package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare block_trade 接口返回的大宗交易数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=160">Tushare block_trade 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockTradeDTO {

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "ts_code")
    private String tsCode;

    private String name;

    private BigDecimal price;

    private BigDecimal vol;

    private BigDecimal amount;

    private String buyer;

    private String seller;

    @JSONField(name = "buyer_name")
    private String buyerName;

    @JSONField(name = "seller_name")
    private String sellerName;
}
