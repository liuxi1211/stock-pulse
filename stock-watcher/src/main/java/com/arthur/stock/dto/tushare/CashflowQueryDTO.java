package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare cashflow 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=44">Tushare cashflow 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashflowQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 公告日期，格式 yyyyMMdd（可选） */
    private String annDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 报告类型：1=合并报表 / 2=单季合并 / 3=调整单季 / 4=调整合并 / 5=调整前 / 6=调整后（可选） */
    private String reportType;

    /** 公司类型：1=一般工商企业 / 2=证券 / 3=保险 / 4=银行（可选） */
    private String compType;
}
