package com.arthur.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资金流向数据拉取结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyflowFetchResultVO {
    private int moneyflow;
    private int hkHold;
    private int topList;
    private int topInst;
    private int blockTrade;
    private int margin;
    private int marginDetail;
}
