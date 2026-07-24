package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare dividend 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=103">Tushare 分红送股接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DividendQueryDTO {

    /** TS股票代码，如 600848.SH（可选） */
    private String tsCode;

    /** 公告日，格式 yyyyMMdd（可选） */
    private String annDate;

    /** 起始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 股权登记日期，格式 yyyyMMdd（可选） */
    private String recordDate;

    /** 除权除息日，格式 yyyyMMdd（可选） */
    private String exDate;

    /** 实施公告日，格式 yyyyMMdd（可选） */
    private String impAnnDate;
}
