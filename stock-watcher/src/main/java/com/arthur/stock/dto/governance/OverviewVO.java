package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewVO {
    private int totalTables;
    private int updatedToday;
    private int errorTables;
    private String lastCheckTime;
}
