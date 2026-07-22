package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataCheckItem {
    private String name;          // 检测项标识（英文，如 "freshness", "price_logic"）
    private String displayName;   // 展示名称（中文，如 "新鲜度检测", "价格逻辑检测"）
    private boolean passed;       // 是否通过
    private CheckLevel level;     // 严重级别：ERROR / WARN（仅 passed=false 时有意义）
    private String message;       // 详细说明
}
