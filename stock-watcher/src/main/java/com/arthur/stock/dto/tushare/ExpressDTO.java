package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare express 接口返回的业绩快报数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=46">Tushare express 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpressDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "ann_date")
    private String annDate;

    @JSONField(name = "end_date")
    private String endDate;

    /** 营业收入 */
    private BigDecimal revenue;

    @JSONField(name = "operate_profit")
    private BigDecimal operateProfit;

    @JSONField(name = "total_profit")
    private BigDecimal totalProfit;

    @JSONField(name = "n_income")
    private BigDecimal nIncome;

    @JSONField(name = "total_assets")
    private BigDecimal totalAssets;

    @JSONField(name = "total_hldr_eqy_exc_min_int")
    private BigDecimal totalHldrEqyExcMinInt;

    @JSONField(name = "basic_eps")
    private BigDecimal basicEps;

    @JSONField(name = "diluted_eps")
    private BigDecimal dilutedEps;

    @JSONField(name = "growth_yield")
    private BigDecimal growthYield;

    @JSONField(name = "or_growth_yield")
    private BigDecimal orGrowthYield;

    @JSONField(name = "yst_net_profit")
    private BigDecimal ystNetProfit;

    @JSONField(name = "bm_net_profit")
    private BigDecimal bmNetProfit;

    @JSONField(name = "bm_growth_sales")
    private BigDecimal bmGrowthSales;

    @JSONField(name = "update_flag")
    private String updateFlag;
}
