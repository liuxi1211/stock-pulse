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
 * 申万行业成分股数据对象，对应 sw_industry_member 表（tushare index_member_all）
 * <p>
 * 数据库复合主键：(ts_code, index_code, update_date)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sw_industry_member")
public class SwIndustryMemberDO {

    /** 股票代码，如 000001.SZ（复合主键首字段） */
    @TableId(value = "ts_code", type = IdType.INPUT)
    private String tsCode;

    /** 所属行业代码（对应 sw_industry.index_code） */
    @TableField("index_code")
    private String indexCode;

    /** 行业名称 */
    @TableField("index_name")
    private String indexName;

    /** 纳入日期（YYYYMMDD） */
    @TableField("in_date")
    private String inDate;

    /** 剔除日期（YYYYMMDD，为空表示当前在册） */
    @TableField("out_date")
    private String outDate;

    /** 是否最新（true=是，false=否） */
    @TableField("is_new")
    private Boolean isNew;

    /** 分类版本（SW2021） */
    @TableField("src")
    private String src;

    /** 更新日期（YYYYMMDD） */
    @TableField("update_date")
    private String updateDate;
}
