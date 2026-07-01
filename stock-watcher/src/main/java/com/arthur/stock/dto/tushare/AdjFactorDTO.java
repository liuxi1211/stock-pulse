package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare adj_factor 接口返回的复权因子数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=28">Tushare 复权因子接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdjFactorDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 复权因子 */
    @JSONField(name = "adj_factor")
    private BigDecimal adjFactor;
}
