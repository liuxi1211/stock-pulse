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
 * 涨跌停价数据对象，对应 stock_stk_limit 表（tushare stk_limit，doc_id=183）
 * <p>
 * 用于精确判定某日某标的是否涨停/跌停（close &gt;= up_limit / close &lt;= down_limit）。
 * <p>
 * 数据库复合主键：(ts_code, trade_date)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_stk_limit")
public class StockStkLimitDO {

    /** 股票代码，如 000001.SZ（复合主键首字段） */
    @TableId(value = "ts_code", type = IdType.INPUT)
    private String tsCode;

    /** 交易日（YYYYMMDD） */
    @TableField("trade_date")
    private String tradeDate;

    /** 前收盘价 */
    @TableField("pre_close")
    private Double preClose;

    /** 涨停价 */
    @TableField("up_limit")
    private Double upLimit;

    /** 跌停价 */
    @TableField("down_limit")
    private Double downLimit;
}
