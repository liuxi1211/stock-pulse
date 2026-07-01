package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分红送股数据对象，对应 dividend 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("dividend")
public class DividendDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** TS股票代码，如 600848.SH */
    private String tsCode;

    /** 分红年度 */
    private String endDate;

    /** 预案公告日 */
    private String annDate;

    /** 实施进度 */
    private String divProc;

    /** 每股送转 */
    private BigDecimal stkDiv;

    /** 每股送股比例 */
    private BigDecimal stkBoRate;

    /** 每股转增比例 */
    private BigDecimal stkCoRate;

    /** 每股分红（税后） */
    private BigDecimal cashDiv;

    /** 每股分红（税前） */
    private BigDecimal cashDivTax;

    /** 股权登记日 */
    private String recordDate;

    /** 除权除息日 */
    private String exDate;

    /** 派息日 */
    private String payDate;

    /** 红股上市日 */
    private String divListdate;

    /** 实施公告日 */
    private String impAnnDate;

    /** 基准日 */
    private String baseDate;

    /** 基准股本（万） */
    private BigDecimal baseShare;
}
