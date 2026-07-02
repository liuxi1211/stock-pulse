package com.arthur.stock.service;

import com.arthur.stock.vo.KlineDataVO;

import java.util.List;

/**
 * K线服务接口
 */
public interface KlineService {

    /**
     * 获取指定股票的K线数据
     *
     * @param stockCode 股票代码
     * @param period    K线周期：daily/weekly/monthly
     */
    List<KlineDataVO> getKlineData(String stockCode, String period);

    /**
     * 获取指定股票在日期范围内的K线数据
     *
     * @param stockCode 股票代码
     * @param period    K线周期：daily/weekly/monthly
     * @param startDate 开始日期，格式 yyyy-MM-dd
     * @param endDate   结束日期，格式 yyyy-MM-dd
     */
    List<KlineDataVO> getKlineData(String stockCode, String period, String startDate, String endDate);
}