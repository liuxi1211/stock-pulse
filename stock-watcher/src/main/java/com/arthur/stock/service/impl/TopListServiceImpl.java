package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.TopInstDTO;
import com.arthur.stock.dto.tushare.TopListDTO;
import com.arthur.stock.mapper.TopInstMapper;
import com.arthur.stock.mapper.TopListMapper;
import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;
import com.arthur.stock.service.TopListService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 龙虎榜数据服务实现：管理 top_list 和 top_inst 两张表。
 * <p>
 * 数据源：Tushare top_list / top_inst 接口。
 * 落库策略：按主键批量先删后插，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopListServiceImpl implements TopListService {

    private static final int BATCH_SIZE = 500;

    /** 知名游资/机构关键词列表 */
    private static final List<String> NOTABLE_KEYWORDS = List.of(
            "东方财富拉萨", "机构专用", "华鑫证券", "国泰君安",
            "中信证券", "中国国际金融", "招商证券", "华泰证券"
    );

    private final TushareClient tushareClient;
    private final TopListMapper topListMapper;
    private final TopInstMapper topInstMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveTopList(String tradeDate) {
        log.info("拉取 top_list tradeDate={}", tradeDate);
        List<TopListDTO> rows = tushareClient.topList(tradeDate, null);
        if (rows == null || rows.isEmpty()) {
            log.info("top_list {} 无数据", tradeDate);
            return 0;
        }
        List<TopListDO> entities = rows.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
        saveBatchTopList(entities);
        log.info("top_list {} 保存 {} 条", tradeDate, entities.size());
        return entities.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveTopInst(String tradeDate) {
        log.info("拉取 top_inst tradeDate={}", tradeDate);
        List<TopInstDTO> rows = tushareClient.topInst(tradeDate, null);
        if (rows == null || rows.isEmpty()) {
            log.info("top_inst {} 无数据", tradeDate);
            return 0;
        }
        List<TopInstDO> entities = rows.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
        saveBatchTopInst(entities);
        log.info("top_inst {} 保存 {} 条", tradeDate, entities.size());
        return entities.size();
    }

    @Override
    public List<TopListDO> queryList(String tradeDate) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = topListMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        return topListMapper.selectByTradeDate(effectiveDate);
    }

    @Override
    public List<TopInstDO> queryInst(String tradeDate, String tsCode) {
        if (tradeDate == null || tradeDate.isBlank() || tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        return topInstMapper.selectByTradeDateAndCode(tradeDate, tsCode);
    }

    @Override
    public List<TopInstDO> queryNotable(String tradeDate) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = topListMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        return topInstMapper.selectNotableByTradeDate(effectiveDate, NOTABLE_KEYWORDS);
    }

    @Override
    public String getLatestTradeDate() {
        return topListMapper.selectLatestTradeDate();
    }

    // ==================== 内部方法 ====================

    private void saveBatchTopList(List<TopListDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            topListMapper.deleteBatchByKeys(batch);
            topListMapper.insertBatch(batch);
        });
    }

    private void saveBatchTopInst(List<TopInstDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            topInstMapper.deleteBatchByKeys(batch);
            topInstMapper.insertBatch(batch);
        });
    }

    private TopListDO toEntity(TopListDTO d) {
        if (d == null || d.getTradeDate() == null || d.getTsCode() == null || d.getReason() == null) {
            return null;
        }
        return TopListDO.builder()
                .tradeDate(d.getTradeDate())
                .tsCode(d.getTsCode())
                .name(d.getName())
                .close(d.getClose())
                .pctChange(d.getPctChange())
                .turnoverRate(d.getTurnoverRate())
                .amount(d.getAmount())
                .lBuy(d.getLBuy())
                .lSell(d.getLSell())
                .lBuyAmount(d.getLBuyAmount())
                .lSellAmount(d.getLSellAmount())
                .netAmount(d.getNetAmount())
                .bAmount(d.getBAmount())
                .sAmount(d.getSAmount())
                .reason(d.getReason())
                .build();
    }

    private TopInstDO toEntity(TopInstDTO d) {
        if (d == null || d.getTradeDate() == null || d.getTsCode() == null
                || d.getExalter() == null || d.getSide() == null) {
            return null;
        }
        return TopInstDO.builder()
                .tradeDate(d.getTradeDate())
                .tsCode(d.getTsCode())
                .exalter(d.getExalter())
                .side(d.getSide())
                .buy(d.getBuy())
                .buyRate(d.getBuyRate())
                .sell(d.getSell())
                .sellRate(d.getSellRate())
                .netBuy(d.getNetBuy())
                .build();
    }
}
