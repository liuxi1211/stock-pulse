package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare daily 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=27">Tushare daily 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd（可选） */
    private String tradeDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 偏移量（可选） */
    private Integer offset;

    /** 每页条数，最大 5000（可选） */
    private Integer limit;
}