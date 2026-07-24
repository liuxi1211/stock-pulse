package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare suspend_d 接口返回（股票停复牌信息，doc_id=161）
 * <p>
 * 事件模型：每条记录代表某日某股票的停复牌事件。
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

    /** 交易日期（YYYYMMDD） */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 停牌时段，空/NULL 表示全天，如 "10:09-10:19" 表示盘中临时停牌 */
    @JSONField(name = "suspend_timing")
    private String suspendTiming;

    /** 类型：S=停牌，R=复牌 */
    @JSONField(name = "suspend_type")
    private String suspendType;
}
