package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 大宗交易数据对象，对应 block_trade 表（Tushare block_trade：大宗交易）。
 * <p>
 * 数据库复合主键：(trade_date, ts_code, buyer, seller)。trade_date 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("block_trade")
public class BlockTradeDO {

    /** 交易日期 yyyyMMdd（复合主键首字段） */
    @TableId(type = IdType.INPUT)
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 股票名称 */
    private String name;

    /** 成交价 */
    private BigDecimal price;

    /** 成交量（万股） */
    private BigDecimal vol;

    /** 成交额（万元） */
    private BigDecimal amount;

    /** 买方营业部代码 */
    private String buyer;

    /** 卖方营业部代码 */
    private String seller;

    /** 买方营业部名称 */
    private String buyerName;

    /** 卖方营业部名称 */
    private String sellerName;
}
