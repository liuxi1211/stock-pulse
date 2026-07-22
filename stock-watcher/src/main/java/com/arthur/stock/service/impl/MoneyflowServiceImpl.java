package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.MoneyflowDTO;
import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.MoneyflowService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 个股资金流向数据服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyflowServiceImpl implements MoneyflowService {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MoneyflowMapper moneyflowMapper;
    private final TushareClient tushareClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSave(String tradeDate) {
        log.info("拉取 moneyflow tradeDate={}", tradeDate);
        List<MoneyflowDTO> rows = tushareClient.moneyflow(tradeDate, null);
        if (rows == null || rows.isEmpty()) {
            log.info("moneyflow {} 无数据", tradeDate);
            return 0;
        }
        List<MoneyflowDO> entities = rows.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
        saveBatch(entities);
        log.info("moneyflow {} 保存 {} 条", tradeDate, entities.size());
        return entities.size();
    }

    @Override
    public List<MoneyflowDO> queryTop(String tradeDate, int limit, String sortBy, String order) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = getLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        String safeSortBy = "net_mf_vol".equals(sortBy) ? "net_mf_vol" : "net_mf_amount";
        String safeOrder = "asc".equalsIgnoreCase(order) ? "asc" : "desc";
        return moneyflowMapper.selectTopByTradeDate(effectiveDate, limit, safeSortBy, safeOrder);
    }

    @Override
    public List<MoneyflowDO> queryDetail(String tsCode, int days) {
        if (tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        return moneyflowMapper.selectByCodeAndDateRange(tsCode, startDate, endDate);
    }

    @Override
    public String getLatestTradeDate() {
        return moneyflowMapper.selectLatestTradeDate();
    }

    // ==================== 内部 ====================

    private void saveBatch(List<MoneyflowDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            moneyflowMapper.deleteBatchByKeys(batch);
            moneyflowMapper.insertBatch(batch);
        });
    }

    private MoneyflowDO toEntity(MoneyflowDTO d) {
        if (d == null || d.getTsCode() == null || d.getTradeDate() == null) {
            return null;
        }
        return MoneyflowDO.builder()
                .tsCode(d.getTsCode())
                .tradeDate(d.getTradeDate())
                .buySmAmount(d.getBuySmAmount())
                .sellSmAmount(d.getSellSmAmount())
                .buySmVol(d.getBuySmVol())
                .sellSmVol(d.getSellSmVol())
                .buyMdAmount(d.getBuyMdAmount())
                .sellMdAmount(d.getSellMdAmount())
                .buyMdVol(d.getBuyMdVol())
                .sellMdVol(d.getSellMdVol())
                .buyLgAmount(d.getBuyLgAmount())
                .sellLgAmount(d.getSellLgAmount())
                .buyLgVol(d.getBuyLgVol())
                .sellLgVol(d.getSellLgVol())
                .buyElgAmount(d.getBuyElgAmount())
                .sellElgAmount(d.getSellElgAmount())
                .buyElgVol(d.getBuyElgVol())
                .sellElgVol(d.getSellElgVol())
                .netMfAmount(d.getNetMfAmount())
                .netMfVol(d.getNetMfVol())
                .build();
    }
}
