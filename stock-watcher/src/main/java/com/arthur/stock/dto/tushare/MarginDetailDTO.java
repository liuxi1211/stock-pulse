package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare margin_detail 接口返回的融资融券个股明细数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=58">Tushare margin_detail 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarginDetailDTO {

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "ts_code")
    private String tsCode;

    private String name;

    private BigDecimal rzye;

    private BigDecimal rqye;

    private BigDecimal rzmre;

    private BigDecimal rzche;

    private BigDecimal rqmcl;

    private BigDecimal rzrqye;
}
