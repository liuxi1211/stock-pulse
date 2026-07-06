package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare fina_indicator 接口返回的财务指标数据（ROE/ROA/毛利率/同比/资产负债率等）。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=79">Tushare fina_indicator 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinaIndicatorDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "ann_date")
    private String annDate;

    @JSONField(name = "end_date")
    private String endDate;

    private BigDecimal roe;

    private BigDecimal roa;

    @JSONField(name = "grossprofit_margin")
    private BigDecimal grossprofitMargin;

    @JSONField(name = "netprofit_margin")
    private BigDecimal netprofitMargin;

    @JSONField(name = "dt_netprofit_yoy")
    private BigDecimal dtNetprofitYoy;

    @JSONField(name = "revenue_yoy")
    private BigDecimal revenueYoy;

    @JSONField(name = "debt_to_assets")
    private BigDecimal debtToAssets;

    @JSONField(name = "eps_yoy")
    private BigDecimal epsYoy;
}
