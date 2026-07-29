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
 * 复权因子数据对象，对应 adj_factor 表
 * <p>
 * 数据库复合主键：(ts_code, trade_date)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法（按 tsCode+tradeDate）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("adj_factor")
public class AdjFactorDO {

    /** TS股票代码，如 000001.SZ（复合主键首字段） */
    @TableId(type = IdType.INPUT)
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    private String tradeDate;

    /** 复权因子 */
    private BigDecimal adjFactor;
}
