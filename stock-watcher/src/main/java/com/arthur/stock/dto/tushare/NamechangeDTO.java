package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare namechange 接口返回（股票更名历史，doc_id=160）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=160">Tushare 股票更名接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NamechangeDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 股票名称 */
    @JSONField(name = "name")
    private String name;

    /** 起始日期（YYYYMMDD） */
    @JSONField(name = "start_date")
    private String startDate;

    /** 结束日期（YYYYMMDD，为空表示当前生效） */
    @JSONField(name = "end_date")
    private String endDate;

    /** 更名原因 */
    @JSONField(name = "change_reason")
    private String changeReason;
}
