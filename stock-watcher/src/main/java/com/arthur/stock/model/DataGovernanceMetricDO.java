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
 * 数据质量检测历史数据对象，对应 data_governance_metric 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("data_governance_metric")
public class DataGovernanceMetricDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 检测批次ID（同一次检测的25条共享同一个batch_id） */
    private String checkBatchId;

    /** 表代码（对应 InitStep.code） */
    private String tableCode;

    /** 表中文名 */
    private String tableName;

    /** 表分组：BASIC/MARKET/FINANCE/EVENT/INDEX */
    private String tableGroup;

    /** 检测时总记录数 */
    private Long totalRows;

    /** 较上次检测的记录数变动百分比（正数=增加，负数=减少） */
    private BigDecimal rowDeltaPct;

    /** 最新数据日期（YYYYMMDD） */
    private String latestDate;

    /** 最早数据日期（YYYYMMDD） */
    private String earliestDate;

    /** 状态：NORMAL/DELAYED/ERROR */
    private String status;

    /** 所有检测项结果（JSON 字符串） */
    private String checkItems;

    /** 检测执行时间（yyyy-MM-dd HH:mm:ss） */
    private String checkTime;

    /** 检测类型：SCHEDULED（定时）/ MANUAL（手动） */
    private String checkType;
}
