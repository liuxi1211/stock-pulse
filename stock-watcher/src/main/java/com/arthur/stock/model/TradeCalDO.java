package com.arthur.stock.model;

import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.TradeDayStatusEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交易日历数据对象，对应 trade_cal 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("trade_cal")
public class TradeCalDO {

    /** 主键ID */
    private Long id;

    /** 交易所（SSE上交所/SZSE深交所） */
    private ExchangeEnum exchange;

    /** 日历日期，格式 yyyyMMdd */
    private String calDate;

    /** 是否交易日（0休市/1交易） */
    private TradeDayStatusEnum isOpen;

    /** 上一个交易日，格式 yyyyMMdd */
    private String pretradeDate;

    /** 是否本周首个交易日（1=是，0=否） */
    private String isFirstOfWeek;

    /** 是否本周末个交易日（1=是，0=否） */
    private String isLastOfWeek;

    /** 是否本月首个交易日（1=是，0=否） */
    private String isFirstOfMonth;

    /** 是否本月末个交易日（1=是，0=否） */
    private String isLastOfMonth;

    /** 是否本季首个交易日（1=是，0=否） */
    private String isFirstOfQuarter;

    /** 是否本季末个交易日（1=是，0=否） */
    private String isLastOfQuarter;
}