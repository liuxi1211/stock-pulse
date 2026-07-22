package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申万行业视图对象，用于前端行业筛选下拉展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwIndustryVO {

    /** 行业代码（对应 sw_industry.index_code） */
    private String industryCode;

    /** 行业名称（对应 sw_industry.index_name） */
    private String industryName;
}
