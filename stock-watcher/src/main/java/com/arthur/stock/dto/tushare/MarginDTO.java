package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare margin 接口返回的融资融券汇总数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=56">Tushare margin 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarginDTO {

    @JSONField(name = "exchange_id")
    private String exchangeId;

    @JSONField(name = "trade_date")
    private String tradeDate;

    private BigDecimal rzye;

    private BigDecimal rzmre;

    private BigDecimal rzche;

    private BigDecimal rqye;

    private BigDecimal rqmcl;

    private BigDecimal rzrqye;
}
