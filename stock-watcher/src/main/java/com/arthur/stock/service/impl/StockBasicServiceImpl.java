package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.*;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.dto.tushare.StockBasicQueryDTO;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.cache.StockCodeCache;
import com.arthur.stock.util.SensitiveDataUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
public class StockBasicServiceImpl implements StockBasicService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.STOCK_BASIC.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stockBasicMapper.selectCount(null);

            // Check 1: Stocks in daily_quote but not in stock_basic (ERROR)
            String thirtyDaysAgo = LocalDate.now().minusDays(30).format(DATE_FMT);
            int orphanCount = stockBasicMapper.countStocksInDailyNotInBasic(thirtyDaysAgo);
            items.add(DataCheckItem.builder()
                    .name("orphan_in_daily")
                    .displayName("行情数据孤儿检测")
                    .passed(orphanCount == 0)
                    .level(CheckLevel.ERROR)
                    .message(orphanCount == 0 ? "通过，最近 30 天行情数据均有对应基础信息"
                            : "最近 30 天有 " + orphanCount + " 只股票在行情中但不在基础信息表中")
                    .build());

            // Check 2: Listed count vs daily_quote distinct count mismatch (WARN)
            long listedCount = stockBasicMapper.selectCount(
                    new LambdaQueryWrapper<StockBasicDO>().eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED));
            String sevenDaysAgo = LocalDate.now().minusDays(7).format(DATE_FMT);
            int dailyDistinct = stockBasicMapper.countDistinctTsCodeInDaily(sevenDaysAgo);
            double diffPct = listedCount > 0
                    ? Math.abs(listedCount - dailyDistinct) * 100.0 / listedCount : 100.0;
            boolean countPassed = diffPct <= 5.0;
            items.add(DataCheckItem.builder()
                    .name("count_match")
                    .displayName("上市数量与行情数量匹配检测")
                    .passed(countPassed)
                    .level(CheckLevel.WARN)
                    .message(countPassed
                            ? "通过，在市股票 " + listedCount + " 只，近 7 天行情 " + dailyDistinct + " 只"
                            : "上市股票数量与行情数据差异过大（" + String.format("%.1f", diffPct) + "%）")
                    .build());

            // Check 3: Key fields null (WARN)
            int nullCount = stockBasicMapper.countNullKeyFields();
            items.add(DataCheckItem.builder()
                    .name("null_key_fields")
                    .displayName("关键字段空值检测")
                    .passed(nullCount == 0)
                    .level(CheckLevel.WARN)
                    .message(nullCount == 0 ? "通过，无关键字段为空" : "存在 " + nullCount + " 条关键字段为空的记录")
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STOCK_BASIC.getLabel())
                    .totalRows(totalRows)
                    .latestDate(null)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for stock_basic", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + SensitiveDataUtil.mask(e.getMessage()))
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STOCK_BASIC.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}