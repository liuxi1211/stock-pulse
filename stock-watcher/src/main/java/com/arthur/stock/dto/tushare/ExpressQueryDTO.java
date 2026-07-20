package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare express 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=46">Tushare express 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpressQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 公告日期，格式 yyyyMMdd（可选） */
    private String annDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 报告期，格式 yyyyMMdd（可选，如 20231231） */
    private String period;
}
