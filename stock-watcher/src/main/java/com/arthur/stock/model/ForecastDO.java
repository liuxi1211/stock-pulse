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
 * 业绩预告数据对象，对应 forecast 表（Tushare forecast，doc_id=45）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("forecast")
public class ForecastDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;

    /** 公告日期 yyyyMMdd */
    private String annDate;

    /** 报告期 yyyyMMdd */
    private String endDate;

    /** 业绩预告类型：预增/预减/扭亏/续盈/续亏/略增/略减/不确定 */
    private String type;

    /** 预告净利润变动幅度下限（%） */
    private BigDecimal pChangeMin;

    /** 预告净利润变动幅度上限（%） */
    private BigDecimal pChangeMax;

    /** 预告净利润下限（万元） */
    private BigDecimal netProfitMin;

    /** 预告净利润上限（万元） */
    private BigDecimal netProfitMax;

    /** 上年同期归属母公司净利润 */
    private BigDecimal lastParentNet;

    /** 业绩预告内容 */
    private String summary;

    /** 业绩变动原因 */
    private String changeReason;
}
