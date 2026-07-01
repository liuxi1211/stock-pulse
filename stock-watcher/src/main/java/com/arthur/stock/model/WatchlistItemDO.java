package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注列表数据对象，对应 sys_watchlist 表
 */
@Data
@TableName("sys_watchlist")
public class WatchlistItemDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 股票代码，如 000001.SZ */
    private String stockCode;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}