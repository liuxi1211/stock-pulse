package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自选股分组数据对象，对应 sys_watchlist_group 表
 */
@Data
@TableName("sys_watchlist_group")
public class WatchlistGroupDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 分组名称 */
    private String groupName;

    /** 排序序号 */
    private Integer sortOrder;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
