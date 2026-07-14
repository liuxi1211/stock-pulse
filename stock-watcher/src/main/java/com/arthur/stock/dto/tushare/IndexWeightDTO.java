package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare index_weight 接口返回的指数成分股权重数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=97">Tushare 指数成分和权重接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexWeightDTO {

    /** 指数代码，如 000300.SH */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 成分股代码，如 600000.SH */
    @JSONField(name = "con_code")
    private String conCode;

    /** 权重（百分比） */
    private BigDecimal weight;
}
