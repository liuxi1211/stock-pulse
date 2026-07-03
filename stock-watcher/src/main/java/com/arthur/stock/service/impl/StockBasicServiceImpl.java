package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.*;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.dto.tushare.StockBasicQueryDTO;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.cache.StockCodeCache;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 股票基础信息服务实现类，负责从Tushare获取股票基础信息并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBasicServiceImpl implements StockBasicService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final StockBasicMapper stockBasicMapper;
    private final StockCodeCache stockCodeCache;

    /**
     * 从Tushare获取所有上市股票的基础信息并保存到本地数据库，已存在的记录会更新
     */
    @Override
    public List<StockBasicDTO> fetchAndSaveStockBasic() {
        log.info("Fetching stock_basic from Tushare");

        StockBasicQueryDTO param = StockBasicQueryDTO.builder()
                .listStatus(ListStatusEnum.LISTED.getCode())
                .build();
        List<StockBasicDTO> stocks = tushareClient.stockBasic(param);

        if (stocks.isEmpty()) {
            log.info("No stock_basic data returned");
            return Collections.emptyList();
        }

        List<StockBasicDO> entities = stocks.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveStocks(entities);
        stockCodeCache.refresh();
        log.info("Saved {} stock_basic records", entities.size());
        return stocks;
    }

    /**
     * 从本地数据库查询股票基础信息，支持按TS代码、名称、交易所、上市状态筛选
     */
    @Override
    public List<StockBasicDTO> queryLocal(String tsCode, String name, String exchange, String listStatus) {
        LambdaQueryWrapper<StockBasicDO> wrapper = new LambdaQueryWrapper<>();
        if (tsCode != null && !tsCode.isEmpty()) {
            wrapper.eq(StockBasicDO::getTsCode, tsCode);
        }
        if (name != null && !name.isEmpty()) {
            wrapper.like(StockBasicDO::getName, name);
        }
        if (exchange != null && !exchange.isEmpty()) {
            wrapper.eq(StockBasicDO::getExchange, ExchangeEnum.fromCode(exchange));
        }
        if (listStatus != null && !listStatus.isEmpty()) {
            wrapper.eq(StockBasicDO::getListStatus, ListStatusEnum.fromCode(listStatus));
        }

        List<StockBasicDO> stocks = stockBasicMapper.selectList(wrapper);
        return stocks.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private StockBasicDO toEntity(StockBasicDTO dto) {
        return StockBasicDO.builder()
                .tsCode(dto.getTsCode())
                .symbol(dto.getSymbol())
                .name(dto.getName())
                .area(dto.getArea())
                .industry(dto.getIndustry())
                .fullname(dto.getFullname())
                .enname(dto.getEnname())
                .cnspell(dto.getCnspell())
                .market(BoardEnum.fromCode(dto.getMarket()))
                .exchange(ExchangeEnum.fromCode(dto.getExchange()))
                .currType(dto.getCurrType())
                .listStatus(ListStatusEnum.fromCode(dto.getListStatus()))
                .listDate(dto.getListDate())
                .delistDate(dto.getDelistDate())
                .isHs(HsConnectEnum.fromCode(dto.getIsHs()))
                .actName(dto.getActName())
                .actEntType(dto.getActEntType())
                .build();
    }

    private StockBasicDTO toDTO(StockBasicDO entity) {
        return StockBasicDTO.builder()
                .tsCode(entity.getTsCode())
                .symbol(entity.getSymbol())
                .name(entity.getName())
                .area(entity.getArea())
                .industry(entity.getIndustry())
                .fullname(entity.getFullname())
                .enname(entity.getEnname())
                .cnspell(entity.getCnspell())
                .market(entity.getMarket() != null ? entity.getMarket().getCode() : null)
                .exchange(entity.getExchange() != null ? entity.getExchange().getCode() : null)
                .currType(entity.getCurrType())
                .listStatus(entity.getListStatus() != null ? entity.getListStatus().getCode() : null)
                .listDate(entity.getListDate())
                .delistDate(entity.getDelistDate())
                .isHs(entity.getIsHs() != null ? entity.getIsHs().getCode() : null)
                .actName(entity.getActName())
                .actEntType(entity.getActEntType())
                .build();
    }

    /**
     * 批量保存股票基础信息。先删除同 ts_code 已存在记录，再插入；跨方言通用。
     */
    private void saveStocks(List<StockBasicDO> stocks) {
        Lists.partition(stocks, BATCH_SIZE).forEach(batch -> {
            stockBasicMapper.deleteBatchByKeys(batch);
            stockBasicMapper.insertBatch(batch);
        });
    }
}