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
 * 申万行业分类数据对象，对应 sw_industry 表（tushare index_classify，SW2021 版本）
 * <p>
 * 数据库复合主键：(index_code, src)。index_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sw_industry")
public class SwIndustryDO {

    /** 行业代码（复合主键首字段） */
    @TableId(value = "index_code", type = IdType.INPUT)
    private String indexCode;

    /** 行业名称 */
    @TableField("index_name")
    private String indexName;

    /** 行业层级（1/2/3） */
    @TableField("level")
    private Integer level;

    /** 父级行业代码（一级行业为空） */
    @TableField("parent_code")
    private String parentCode;

    /** 分类版本（SW2021） */
    @TableField("src")
    private String src;
}
