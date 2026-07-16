package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare stk_limit 接口请求参数（涨跌停价，doc_id=183）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=183">Tushare 涨跌停价接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StkLimitQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 起始日期（可选） */
    private String startDate;

    /** 结束日期（可选） */
    private String endDate;
}
