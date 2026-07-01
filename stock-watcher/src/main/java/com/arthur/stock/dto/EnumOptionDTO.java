package com.arthur.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 枚举选项，用于前端下拉框等选择组件的数据展示
 */
@Data
@AllArgsConstructor
public class EnumOptionDTO {

    /** 选项编码值 */
    private String code;

    /** 选项显示文本 */
    private String label;
}