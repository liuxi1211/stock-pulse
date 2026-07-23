package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.TopInstDTO;
import com.arthur.stock.dto.tushare.TopListDTO;
import com.arthur.stock.mapper.TopInstMapper;
import com.arthur.stock.mapper.TopListMapper;
import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.TopListService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
public class TopListServiceImpl implements TopListService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.TOP_LIST.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = topListMapper.selectCount(null);
            String latestDate = topListMapper.selectLatestTradeDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("amount_validity")
                        .displayName("成交额有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("pct_change_validity")
                        .displayName("涨跌幅合理性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("net_amount_consistency")
                        .displayName("净额一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                String thirtyDaysAgo = LocalDate.now().minusDays(30).format(DATE_FMT);

                int invalidAmount = topListMapper.countInvalidAmount(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("amount_validity")
                        .displayName("成交额有效性检测")
                        .passed(invalidAmount == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidAmount == 0 ? "通过，最近30天成交额数据正常"
                                : "最近30天成交额小于等于0的记录 " + invalidAmount + " 条")
                        .build());

                int invalidPctChange = topListMapper.countInvalidPctChange(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("pct_change_validity")
                        .displayName("涨跌幅合理性检测")
                        .passed(invalidPctChange == 0)
                        .level(CheckLevel.WARN)
                        .message(invalidPctChange == 0 ? "通过，最近30天涨跌幅数据正常"
                                : "最近30天涨跌幅超出±21%的记录 " + invalidPctChange + " 条")
                        .build());

                int netAmountInconsistency = topListMapper.countNetAmountInconsistency(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("net_amount_consistency")
                        .displayName("净额一致性检测")
                        .passed(netAmountInconsistency == 0)
                        .level(CheckLevel.WARN)
                        .message(netAmountInconsistency == 0 ? "通过，最近30天净额数据一致"
                                : "最近30天净额与买卖额偏差超过10%的记录 " + netAmountInconsistency + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TOP_LIST.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for top_list", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TOP_LIST.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
