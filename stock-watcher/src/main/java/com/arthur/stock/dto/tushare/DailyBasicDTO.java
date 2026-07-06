package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare daily_basic 接口返回的每日基本面数据（估值/换手率/市值）。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=32">Tushare daily_basic 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBasicDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "trade_date")
    private String tradeDate;

    private BigDecimal close;

    @JSONField(name = "turnover_rate")
    private BigDecimal turnoverRate;

    @JSONField(name = "turnover_rate_f")
    private BigDecimal turnoverRateF;

    @JSONField(name = "volume_ratio")
    private BigDecimal volumeRatio;

    private BigDecimal pe;

    @JSONField(name = "pe_ttm")
    private BigDecimal peTtm;

    private BigDecimal pb;

    private BigDecimal ps;

    @JSONField(name = "ps_ttm")
    private BigDecimal psTtm;

    @JSONField(name = "dv_ratio")
    private BigDecimal dvRatio;

    @JSONField(name = "dv_ttm")
    private BigDecimal dvTtm;

    @JSONField(name = "total_share")
    private BigDecimal totalShare;

    @JSONField(name = "float_share")
    private BigDecimal floatShare;

    @JSONField(name = "free_share")
    private BigDecimal freeShare;

    @JSONField(name = "total_mv")
    private BigDecimal totalMv;

    @JSONField(name = "circ_mv")
    private BigDecimal circMv;
}
