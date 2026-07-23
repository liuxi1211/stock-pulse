package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.ForecastDTO;
import com.arthur.stock.dto.tushare.ForecastQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.ForecastMapper;
import com.arthur.stock.model.ForecastDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业绩预告服务实现：从 Tushare 按 ts_code + 日期范围拉取，批量 upsert 到 forecast 表。
 * forecast 表保留同一报告期的多次预告（首次+修正），按 (ts_code, end_date, ann_date) 唯一键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastServiceImpl implements ForecastService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final ForecastMapper forecastMapper;
    private final StockBasicService stockBasicService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSaveForecast(String tsCode, String startDate, String endDate) {
        log.info("拉取 forecast {} [{}~{}]", tsCode, startDate, endDate);
        ForecastQueryDTO param = ForecastQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        // ⚠️ API 调用在事务外执行，避免限流等待时长时间占用数据库连接
        List<ForecastDTO> rows = tushareClient.forecast(param);
        if (rows == null || rows.isEmpty()) {
            log.info("forecast {} 无数据", tsCode);
            return 0;
        }
        List<ForecastDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        // 数据库写入才开启事务，尽量缩短连接持有时间
        transactionTemplate.execute(status -> {
            saveBatch(entities);
            return null;
        });
        log.info("forecast {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("forecast: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 forecast [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int successCount = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveForecast(s.getTsCode(), startDate, endDate);
                successCount++;
            } catch (Exception e) {
                log.warn("forecast {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("forecast 拉取完成，{} 只股票成功，共 {} 条记录", successCount, total);
        return total;
    }

    @Override
    public List<ForecastDO> queryLocalByTsCode(String tsCode) {
        return forecastMapper.selectList(
                new LambdaQueryWrapper<ForecastDO>()
                        .eq(ForecastDO::getTsCode, tsCode)
                        .orderByAsc(ForecastDO::getEndDate));
    }

    @Override
    public ForecastDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return forecastMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    private void saveBatch(List<ForecastDO> list) {
        // 按 (ts_code, end_date, ann_date) 去重，保留最后一条（后出现的覆盖先出现的）
        // 防止 Tushare 返回同一批次内的重复数据导致唯一键冲突
        List<ForecastDO> deduplicated = new ArrayList<>(deduplicateByKey(list).values());
        Lists.partition(deduplicated, BATCH_SIZE).forEach(batch -> {
            forecastMapper.deleteBatchByKeys(batch);
            forecastMapper.insertBatch(batch);
        });
    }

    /**
     * 按唯一键 (ts_code, end_date, ann_date) 去重，保留最后出现的一条。
     * ann_date 为 null 或空字符串视为等价（与 deleteBatchByKeys 的 NULL 处理保持一致）。
     */
    private Map<String, ForecastDO> deduplicateByKey(List<ForecastDO> list) {
        Map<String, ForecastDO> map = new LinkedHashMap<>();
        for (ForecastDO item : list) {
            String annKey = (item.getAnnDate() == null || item.getAnnDate().isEmpty())
                    ? "__NULL__" : item.getAnnDate();
            String key = item.getTsCode() + "|" + item.getEndDate() + "|" + annKey;
            map.put(key, item);
        }
        return map;
    }

    private ForecastDO toEntity(ForecastDTO d) {
        // ann_date 归一化：null / 空白 -> 空字符串
        // 确保唯一键 (ts_code, end_date, ann_date) 中无可空值，UNIQUE 约束真正生效
        String annDate = d.getAnnDate();
        if (annDate == null || annDate.isBlank()) {
            annDate = "";
        }
        return ForecastDO.builder()
                .tsCode(d.getTsCode())
                .annDate(annDate)
                .endDate(d.getEndDate())
                .type(d.getType())
                .pChangeMin(d.getPChangeMin())
                .pChangeMax(d.getPChangeMax())
                .netProfitMin(d.getNetProfitMin())
                .netProfitMax(d.getNetProfitMax())
                .lastParentNet(d.getLastParentNet())
                .summary(d.getSummary())
                .changeReason(d.getChangeReason())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.FORECAST.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = forecastMapper.selectCount(null);
            String maxAnnDate = forecastMapper.selectMaxAnnDate();
            LocalDate today = LocalDate.now();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("season_coverage")
                        .displayName("财报季预告覆盖检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("range_logic")
                        .displayName("范围逻辑检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("type_consistency")
                        .displayName("类型一致性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                return DataCheckResult.builder()
                        .tableCode(getTableCode())
                        .tableName(InitStep.FORECAST.getLabel())
                        .totalRows(0)
                        .latestDate(null)
                        .items(items)
                        .build();
            }

            // Check 1: Season coverage (WARN) - 上一个财报季首月
            int currentMonth = today.getMonthValue();
            int lastSeasonStartMonth;
            if (currentMonth >= 10) {
                lastSeasonStartMonth = 10;
            } else if (currentMonth >= 7) {
                lastSeasonStartMonth = 7;
            } else if (currentMonth >= 4) {
                lastSeasonStartMonth = 4;
            } else {
                lastSeasonStartMonth = 1;
            }
            if (currentMonth == lastSeasonStartMonth) {
                if (lastSeasonStartMonth == 1) {
                    lastSeasonStartMonth = 10;
                } else if (lastSeasonStartMonth == 4) {
                    lastSeasonStartMonth = 1;
                } else if (lastSeasonStartMonth == 7) {
                    lastSeasonStartMonth = 4;
                } else {
                    lastSeasonStartMonth = 7;
                }
            }
            int year = today.getYear();
            if (lastSeasonStartMonth == 10 && currentMonth < 10) {
                year--;
            }
            String lastSeasonMonth = String.format("%04d%02d", year, lastSeasonStartMonth);
            int seasonCount = forecastMapper.countByAnnMonth(lastSeasonMonth);
            boolean seasonPassed = seasonCount > 0;
            String seasonMsg = seasonPassed
                    ? "通过，" + lastSeasonMonth + " 月有 " + seasonCount + " 条预告数据"
                    : "上一财报季首月（" + lastSeasonMonth + "）无任何预告数据";
            items.add(DataCheckItem.builder()
                    .name("season_coverage")
                    .displayName("财报季预告覆盖检测")
                    .passed(seasonPassed)
                    .level(CheckLevel.WARN)
                    .message(seasonMsg)
                    .build());

            // Check 2: Range logic (ERROR)
            int rangeErrorCount = forecastMapper.countRangeLogicErrors();
            boolean rangePassed = rangeErrorCount == 0;
            String rangeMsg = rangePassed
                    ? "通过，无范围逻辑错误"
                    : "有 " + rangeErrorCount + " 条范围逻辑错误（最小值 > 最大值）";
            items.add(DataCheckItem.builder()
                    .name("range_logic")
                    .displayName("范围逻辑检测")
                    .passed(rangePassed)
                    .level(CheckLevel.ERROR)
                    .message(rangeMsg)
                    .build());

            // Check 3: Type consistency (ERROR)
            int typeErrorCount = forecastMapper.countTypeConsistencyErrors();
            boolean typePassed = typeErrorCount == 0;
            String typeMsg = typePassed
                    ? "通过，无类型一致性错误"
                    : "有 " + typeErrorCount + " 条预告类型与变动方向矛盾";
            items.add(DataCheckItem.builder()
                    .name("type_consistency")
                    .displayName("类型一致性检测")
                    .passed(typePassed)
                    .level(CheckLevel.ERROR)
                    .message(typeMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.FORECAST.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for forecast", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.FORECAST.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
