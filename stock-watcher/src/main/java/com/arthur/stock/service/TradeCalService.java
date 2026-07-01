package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.TradeCalDTO;

import java.util.List;

/**
 * 交易日历服务
 */
public interface TradeCalService {

    /**
     * 从 Tushare 拉取交易日历并保存到数据库
     *
     * @param exchange  交易所（可选）
     * @param startDate 开始日期（可选）
     * @param endDate   结束日期（可选）
     * @return 拉取到的交易日历列表
     */
    List<TradeCalDTO> fetchAndSaveTradeCal(String exchange, String startDate, String endDate);

    /**
     * 查询本地数据库中的交易日历
     *
     * @param exchange  交易所（可选）
     * @param startDate 开始日期（可选）
     * @param endDate   结束日期（可选）
     * @param isOpen    是否交易（可选）
     */
    List<TradeCalDTO> queryLocal(String exchange, String startDate, String endDate, String isOpen);
}
