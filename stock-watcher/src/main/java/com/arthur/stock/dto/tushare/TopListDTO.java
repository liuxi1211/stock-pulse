package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare top_list 接口返回的龙虎榜个股明细数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=106">Tushare top_list 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopListDTO {

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "ts_code")
    private String tsCode;

    private String name;

    private BigDecimal close;

    @JSONField(name = "pct_change")
    private BigDecimal pctChange;

    @JSONField(name = "turnover_rate")
    private BigDecimal turnoverRate;

    private BigDecimal amount;

    @JSONField(name = "l_sell")
    private BigDecimal lSell;

    @JSONField(name = "l_buy")
    private BigDecimal lBuy;

    @JSONField(name = "l_amount")
    private BigDecimal lAmount;

    @JSONField(name = "net_amount")
    private BigDecimal netAmount;

    @JSONField(name = "net_rate")
    private BigDecimal netRate;

    @JSONField(name = "amount_rate")
    private BigDecimal amountRate;

    @JSONField(name = "float_values")
    private BigDecimal floatValues;

    private String reason;
}
