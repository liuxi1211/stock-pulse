package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare suspend_d 接口返回（股票停复牌信息，doc_id=161）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=161">Tushare 停复牌接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspendDDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 停牌日期（YYYYMMDD） */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 停牌原因 */
    @JSONField(name = "susp_reason")
    private String suspReason;

    /** 复牌日期（YYYYMMDD，为空表示尚未复牌） */
    @JSONField(name = "resump_date")
    private String resumpDate;
}
