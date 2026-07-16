package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare suspend_d 接口请求参数（股票停复牌信息，doc_id=161）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=161">Tushare 停复牌接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspendDQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 起始日期（可选） */
    private String startDate;

    /** 结束日期（可选） */
    private String endDate;
}
