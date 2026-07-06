package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多因子选股方案数据对象，对应 screen_plan 表
 */
@Data
@TableName("screen_plan")
public class ScreenPlanDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 方案名 */
    private String name;

    /** 方案描述/备注 */
    private String description;

    /** 方案配置 JSON 文本（screen_config 完整快照） */
    private String screenConfig;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
