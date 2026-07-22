package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 自选股条目视图对象，包含行情、基本面和自选股元信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistItemVO {

    private String code;
    private String name;
    private BigDecimal currentPrice;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private Long volume;
    private String turnover;

    private String industryName;
    private BigDecimal totalMv;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal turnoverRate;

    private Long groupId;
    private String note;
    private BigDecimal targetPriceHigh;
    private BigDecimal targetPriceLow;
    private Integer sortOrder;
    private LocalDateTime createdAt;

    private List<BigDecimal> closeSeries;
}
