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
 * 股票更名历史数据对象，对应 stock_namechange 表（tushare namechange，doc_id=160）
 * <p>
 * 用于判定某日某标的是否 ST（该日生效的 name 含 "ST"）。
 * <p>
 * 数据库复合主键：(ts_code, start_date)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_namechange")
public class StockNamechangeDO {

    /** 股票代码，如 000001.SZ（复合主键首字段） */
    @TableId(value = "ts_code", type = IdType.INPUT)
    private String tsCode;

    /** 股票名称 */
    @TableField("name")
    private String name;

    /** 起始日期（YYYYMMDD） */
    @TableField("start_date")
    private String startDate;

    /** 结束日期（YYYYMMDD，为空表示当前生效） */
    @TableField("end_date")
    private String endDate;

    /** 更名原因 */
    @TableField("change_reason")
    private String changeReason;
}
