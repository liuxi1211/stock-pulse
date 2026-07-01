package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.StockBasicDTO;

import java.util.List;

/**
 * 股票基础信息服务
 */
public interface StockBasicService {

    /**
     * 从 Tushare 拉取股票基础信息并保存到数据库
     *
     * @return 拉取到的股票列表
     */
    List<StockBasicDTO> fetchAndSaveStockBasic();

    /**
     * 查询本地数据库中的股票基础信息
     *
     * @param tsCode    股票代码（可选）
     * @param name      名称（可选，模糊匹配）
     * @param exchange  交易所（可选）
     * @param listStatus 上市状态（可选）
     */
    List<StockBasicDTO> queryLocal(String tsCode, String name, String exchange, String listStatus);
}
