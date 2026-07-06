package com.arthur.stock.service.impl;

import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.mapper.WatchlistMapper;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.model.WatchlistItemDO;
import com.arthur.stock.service.WatchlistService;
import com.arthur.stock.util.StockDataHelper;
import com.arthur.stock.vo.StockVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 自选股服务实现
 */
@Service
@RequiredArgsConstructor
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistMapper watchlistMapper;
    private final StockBasicMapper stockBasicMapper;
    private final StockDataHelper stockDataHelper;

    @Override
    public List<StockVO> getWatchlist(Long userId) {
        List<WatchlistItemDO> items = watchlistMapper.selectList(
                new LambdaQueryWrapper<WatchlistItemDO>().eq(WatchlistItemDO::getUserId, userId));
        if (items.isEmpty()) return Collections.emptyList();

        List<String> codes = items.stream().map(WatchlistItemDO::getStockCode).toList();
        return stockDataHelper.buildStockVOListBySymbols(codes);
    }

    @Override
    public void addToWatchlist(Long userId, String stockCode) {
        Long count = stockBasicMapper.selectCount(
                new LambdaQueryWrapper<StockBasicDO>()
                        .eq(StockBasicDO::getSymbol, stockCode)
                        .eq(StockBasicDO::getListStatus, "L"));
        if (count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "股票不存在: " + stockCode);
        }

        Long exists = watchlistMapper.selectCount(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已在自选股中");
        }

        WatchlistItemDO item = new WatchlistItemDO();
        item.setUserId(userId);
        item.setStockCode(stockCode);
        watchlistMapper.insert(item);
    }

    @Override
    public void removeFromWatchlist(Long userId, String stockCode) {
        int deleted = watchlistMapper.delete(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }
    }
}