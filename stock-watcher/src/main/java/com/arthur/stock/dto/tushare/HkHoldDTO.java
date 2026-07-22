package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare hk_hold 接口返回的沪深港通持股明细数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=31">Tushare hk_hold 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HkHoldDTO {

    @JSONField(name = "trade_date")
    private String tradeDate;

    private String code;

    private String name;

    private BigDecimal vol;

    private BigDecimal ratio;

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "exchange_id")
    private String exchangeId;
}
