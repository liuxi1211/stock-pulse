package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票更名历史数据对象，对应 stock_namechange 表（tushare namechange，doc_id=160）
 * <p>
 * 用于判定某日某标的是否 ST（该日生效的 name 含 "ST"）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_namechange")
public class StockNamechangeDO {

    /** 股票代码，如 000001.SZ */
    @TableField("ts_code")
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
