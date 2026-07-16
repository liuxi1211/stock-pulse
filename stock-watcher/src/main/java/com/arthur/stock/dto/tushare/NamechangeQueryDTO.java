package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare namechange 接口请求参数（股票更名历史，doc_id=160）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=160">Tushare 股票更名接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NamechangeQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 起始日期（可选） */
    private String startDate;

    /** 结束日期（可选） */
    private String endDate;
}
