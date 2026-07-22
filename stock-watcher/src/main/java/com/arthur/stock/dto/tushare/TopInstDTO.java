package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare top_inst 接口返回的龙虎榜营业部席位明细数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=107">Tushare top_inst 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopInstDTO {

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "ts_code")
    private String tsCode;

    private String exalter;

    private String side;

    private BigDecimal buy;

    @JSONField(name = "buy_rate")
    private BigDecimal buyRate;

    private BigDecimal sell;

    @JSONField(name = "sell_rate")
    private BigDecimal sellRate;

    @JSONField(name = "net_buy")
    private BigDecimal netBuy;
}
