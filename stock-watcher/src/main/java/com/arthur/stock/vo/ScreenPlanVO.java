package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选股方案视图对象。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenPlanVO {

    /** 主键ID */
    private Long id;

    /** 方案名 */
    private String name;

    /** 方案描述 */
    private String description;

    /** 方案配置（universe/conditions/ranking/filters/topN 等任意 JSON 树） */
    private Object screenConfig;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
