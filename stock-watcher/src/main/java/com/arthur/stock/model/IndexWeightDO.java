package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 指数成分股权重数据对象，对应 index_weight 表（tushare index_weight）
 * <p>
 * 数据库复合主键：(ts_code, trade_date, con_code)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("index_weight")
public class IndexWeightDO {

    /** 指数代码，如 000300.SH（复合主键首字段） */
    @TableId(value = "ts_code", type = IdType.INPUT)
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    @TableField("trade_date")
    private String tradeDate;

    /** 成分股代码，如 000001.SZ */
    @TableField("con_code")
    private String conCode;

    /** 成分股权重（%） */
    @TableField("weight")
    private BigDecimal weight;
}
