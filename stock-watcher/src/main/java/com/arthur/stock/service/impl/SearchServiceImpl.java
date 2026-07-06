package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.service.SearchService;
import com.arthur.stock.util.StockDataHelper;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.vo.StockVO;
import com.arthur.stock.vo.SuggestItemVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 股票搜索服务实现
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final StockBasicMapper stockBasicMapper;
    private final StockDataHelper stockDataHelper;

    private static final int SUGGEST_LIMIT = 10;

    @Override
    public PageResult<StockVO> searchStocks(String keyword, int page, int size) {
        LambdaQueryWrapper<StockBasicDO> wrapper = new LambdaQueryWrapper<StockBasicDO>()
                .eq(StockBasicDO::getListStatus, "L");
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(StockBasicDO::getSymbol, keyword)
                    .or().like(StockBasicDO::getName, keyword)
                    .or().like(StockBasicDO::getCnspell, keyword));
        }
        wrapper.orderByAsc(StockBasicDO::getSymbol);

        Page<StockBasicDO> pageResult = stockBasicMapper.selectPage(new Page<>(page, size), wrapper);

        List<StockVO> voList = stockDataHelper.enrichWithDailyQuote(pageResult.getRecords());
        return PageResult.of(voList, pageResult.getTotal(), page, size);
    }

    @Override
    public List<SuggestItemVO> suggestStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<StockBasicDO> wrapper = new LambdaQueryWrapper<StockBasicDO>()
                .eq(StockBasicDO::getListStatus, "L")
                .and(w -> w.like(StockBasicDO::getSymbol, keyword)
                        .or().like(StockBasicDO::getName, keyword)
                        .or().like(StockBasicDO::getCnspell, keyword))
                .orderByAsc(StockBasicDO::getSymbol)
                .last("LIMIT " + SUGGEST_LIMIT);

        return stockBasicMapper.selectList(wrapper).stream()
                .map(b -> SuggestItemVO.builder()
                        .code(b.getSymbol())
                        .tsCode(b.getTsCode())
                        .name(b.getName()).build())
                .toList();
    }

    @Override
    public List<SuggestItemVO> batchByTsCodes(List<String> tsCodes) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = tsCodes.stream()
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return stockBasicMapper.selectList(new LambdaQueryWrapper<StockBasicDO>()
                        .in(StockBasicDO::getTsCode, normalized))
                .stream()
                .map(b -> SuggestItemVO.builder()
                        .code(b.getSymbol())
                        .tsCode(b.getTsCode())
                        .name(b.getName()).build())
                .toList();
    }
}