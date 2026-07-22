package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据拉取日志数据对象，对应 data_pull_log 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("data_pull_log")
public class DataPullLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一ID（UUID） */
    private String taskId;

    /** 表代码 */
    private String tableCode;

    /** 表中文名 */
    private String tableName;

    /** 操作类型：SCHEDULED/MANUAL_INCREMENTAL/MANUAL_FULL */
    private String operationType;

    /** 状态：RUNNING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 开始时间（yyyy-MM-dd HH:mm:ss） */
    private String startTime;

    /** 结束时间（yyyy-MM-dd HH:mm:ss） */
    private String endTime;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 处理总数（条） */
    private Long totalCount;

    /** 成功数（条） */
    private Long successCount;

    /** 失败数（条） */
    private Long failCount;

    /** 错误信息摘要（脱敏后） */
    private String errorMessage;

    /** 错误堆栈详情（脱敏后，仅管理员可见） */
    private String errorStack;

    /** 操作人：用户名 / SYSTEM（定时任务） */
    private String operator;
}
