package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare dividend 接口返回的分红送股数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=103">Tushare 分红送股接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DividendDTO {

    /** TS股票代码，如 600848.SH */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 分红年度 */
    @JSONField(name = "end_date")
    private String endDate;

    /** 预案公告日 */
    @JSONField(name = "ann_date")
    private String annDate;

    /** 实施进度 */
    @JSONField(name = "div_proc")
    private String divProc;

    /** 每股送转 */
    @JSONField(name = "stk_div")
    private BigDecimal stkDiv;

    /** 每股送股比例 */
    @JSONField(name = "stk_bo_rate")
    private BigDecimal stkBoRate;

    /** 每股转增比例 */
    @JSONField(name = "stk_co_rate")
    private BigDecimal stkCoRate;

    /** 每股分红（税后） */
    @JSONField(name = "cash_div")
    private BigDecimal cashDiv;

    /** 每股分红（税前） */
    @JSONField(name = "cash_div_tax")
    private BigDecimal cashDivTax;

    /** 股权登记日 */
    @JSONField(name = "record_date")
    private String recordDate;

    /** 除权除息日 */
    @JSONField(name = "ex_date")
    private String exDate;

    /** 派息日 */
    @JSONField(name = "pay_date")
    private String payDate;

    /** 红股上市日 */
    @JSONField(name = "div_listdate")
    private String divListdate;

    /** 实施公告日 */
    @JSONField(name = "imp_ann_date")
    private String impAnnDate;

    /** 基准日 */
    @JSONField(name = "base_date")
    private String baseDate;

    /** 基准股本（万） */
    @JSONField(name = "base_share")
    private BigDecimal baseShare;
}
