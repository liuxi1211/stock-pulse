package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票停复牌数据对象，对应 stock_suspend_d 表（tushare suspend_d，doc_id=161）
 * <p>
 * 事件模型：每条记录代表某日某股票的停复牌事件（S=停牌，R=复牌）。
 * 用于判定某日某标的是否处于停牌状态。
 * <p>
 * 数据库复合主键：(ts_code, trade_date, suspend_type)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_suspend_d")
public class StockSuspendDDO {

    /** 股票代码，如 000001.SZ（复合主键首字段） */
    @TableId(value = "ts_code", type = IdType.INPUT)
    private String tsCode;

    /** 交易日期（YYYYMMDD） */
    @TableField("trade_date")
    private String tradeDate;

    /** 停牌时段，空/NULL 表示全天，如 "10:09-10:19" 表示盘中临时停牌 */
    @TableField("suspend_timing")
    private String suspendTiming;

    /** 类型：S=停牌，R=复牌 */
    @TableField("suspend_type")
    private String suspendType;
}
