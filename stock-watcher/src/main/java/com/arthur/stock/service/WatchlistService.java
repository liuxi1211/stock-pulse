package com.arthur.stock.service;

import com.arthur.stock.vo.StockVO;

import java.util.List;

/**
 * 自选股服务接口
 */
public interface WatchlistService {

    /**
     * 获取用户的自选股列表
     */
    List<StockVO> getWatchlist(Long userId);

    /**
     * 将股票添加到自选股列表，股票不存在或已关注时抛出异常
     */
    void addToWatchlist(Long userId, String stockCode);

    /**
     * 从自选股列表移除股票，不在自选中时抛出异常
     */
    void removeFromWatchlist(Long userId, String stockCode);
}