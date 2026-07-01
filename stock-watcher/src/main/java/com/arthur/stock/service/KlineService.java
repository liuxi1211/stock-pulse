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
}