package com.arthur.stock.service;

import com.arthur.stock.vo.WatchlistItemVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自选股服务接口
 */
public interface WatchlistService {

    /**
     * 获取用户的自选股列表
     */
    List<WatchlistItemVO> getWatchlist(Long userId);

    /**
     * 获取用户的自选股列表（支持分组过滤和排序）
     *
     * @param userId  用户ID
     * @param groupId 分组ID，null 表示全部
     * @param sortBy  排序字段：pct_chg / created_at / name / sort_order
     * @param order   排序方向：asc / desc
     */
    List<WatchlistItemVO> getWatchlist(Long userId, Long groupId, String sortBy, String order);

    /**
     * 将股票添加到自选股列表，股票不存在或已关注时抛出异常
     */
    void addToWatchlist(Long userId, String stockCode);

    /**
     * 添加自选股（可选指定分组）
     */
    void addToWatchlist(Long userId, String stockCode, Long groupId);

    /**
     * 从自选股列表移除股票，不在自选中时抛出异常
     */
    void removeFromWatchlist(Long userId, String stockCode);

    /**
     * 设置价格提醒
     */
    void setReminder(Long userId, String stockCode, BigDecimal targetPriceHigh, BigDecimal targetPriceLow);

    /**
     * 清除价格提醒
     */
    void clearReminder(Long userId, String stockCode);

    /**
     * 更新排序序号
     */
    void updateSortOrder(Long userId, String stockCode, Integer sortOrder);

    /**
     * 更新所属分组
     */
    void updateGroup(Long userId, String stockCode, Long groupId);

    /**
     * 批量移除自选股
     */
    void batchRemove(Long userId, List<String> stockCodes);

    /**
     * 批量移动到指定分组
     */
    void batchMoveGroup(Long userId, List<String> stockCodes, Long groupId);
}