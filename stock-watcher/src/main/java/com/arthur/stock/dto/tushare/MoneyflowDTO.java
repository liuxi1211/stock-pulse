package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare moneyflow 接口返回的个股资金流向数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=170">Tushare moneyflow 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyflowDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "buy_sm_amount")
    private BigDecimal buySmAmount;

    @JSONField(name = "sell_sm_amount")
    private BigDecimal sellSmAmount;

    @JSONField(name = "buy_sm_vol")
    private BigDecimal buySmVol;

    @JSONField(name = "sell_sm_vol")
    private BigDecimal sellSmVol;

    @JSONField(name = "buy_md_amount")
    private BigDecimal buyMdAmount;

    @JSONField(name = "sell_md_amount")
    private BigDecimal sellMdAmount;

    @JSONField(name = "buy_md_vol")
    private BigDecimal buyMdVol;

    @JSONField(name = "sell_md_vol")
    private BigDecimal sellMdVol;

    @JSONField(name = "buy_lg_amount")
    private BigDecimal buyLgAmount;

    @JSONField(name = "sell_lg_amount")
    private BigDecimal sellLgAmount;

    @JSONField(name = "buy_lg_vol")
    private BigDecimal buyLgVol;

    @JSONField(name = "sell_lg_vol")
    private BigDecimal sellLgVol;

    @JSONField(name = "buy_elg_amount")
    private BigDecimal buyElgAmount;

    @JSONField(name = "sell_elg_amount")
    private BigDecimal sellElgAmount;

    @JSONField(name = "buy_elg_vol")
    private BigDecimal buyElgVol;

    @JSONField(name = "sell_elg_vol")
    private BigDecimal sellElgVol;

    @JSONField(name = "net_mf_amount")
    private BigDecimal netMfAmount;

    @JSONField(name = "net_mf_vol")
    private BigDecimal netMfVol;
}
