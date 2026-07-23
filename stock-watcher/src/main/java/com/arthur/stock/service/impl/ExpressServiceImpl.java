package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.ExpressDTO;
import com.arthur.stock.dto.tushare.ExpressQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.ExpressMapper;
import com.arthur.stock.model.ExpressDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.ExpressService;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 业绩快报服务实现：从 Tushare 按 ts_code + 日期范围拉取，批量 upsert 到 express 表。
 * 一个报告期一条快报，按 (ts_code, end_date) 唯一键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpressServiceImpl implements ExpressService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final ExpressMapper expressMapper;
    private final StockBasicService stockBasicService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSaveExpress(String tsCode, String startDate, String endDate) {
        log.info("拉取 express {} [{}~{}]", tsCode, startDate, endDate);
        ExpressQueryDTO param = ExpressQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        // ⚠️ API 调用在事务外执行，避免限流等待时长时间占用数据库连接
        List<ExpressDTO> rows = tushareClient.express(param);
        if (rows == null || rows.isEmpty()) {
            log.info("express {} 无数据", tsCode);
            return 0;
        }
        List<ExpressDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        // 数据库写入才开启事务，尽量缩短连接持有时间
        transactionTemplate.execute(status -> {
            saveBatch(entities);
            return null;
        });
        log.info("express {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("express: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 express [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int successCount = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveExpress(s.getTsCode(), startDate, endDate);
                successCount++;
            } catch (Exception e) {
                log.warn("express {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("express 拉取完成，{} 只股票成功，共 {} 条记录", successCount, total);
        return total;
    }

    @Override
    public List<ExpressDO> queryLocalByTsCode(String tsCode) {
        return expressMapper.selectList(
                new LambdaQueryWrapper<ExpressDO>()
                        .eq(ExpressDO::getTsCode, tsCode)
                        .orderByAsc(ExpressDO::getEndDate));
    }

    @Override
    public ExpressDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return expressMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    private void saveBatch(List<ExpressDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            expressMapper.deleteBatchByKeys(batch);
            expressMapper.insertBatch(batch);
        });
    }

    private ExpressDO toEntity(ExpressDTO d) {
        return ExpressDO.builder()
                .tsCode(d.getTsCode())
                .annDate(d.getAnnDate())
                .endDate(d.getEndDate())
                .revenue(d.getRevenue())
                .operateProfit(d.getOperateProfit())
                .totalProfit(d.getTotalProfit())
                .nIncome(d.getNIncome())
                .totalAssets(d.getTotalAssets())
                .totalHldrEqyExcMinInt(d.getTotalHldrEqyExcMinInt())
                .basicEps(d.getBasicEps())
                .dilutedEps(d.getDilutedEps())
                .growthYield(d.getGrowthYield())
                .orGrowthYield(d.getOrGrowthYield())
                .ystNetProfit(d.getYstNetProfit())
                .bmNetProfit(d.getBmNetProfit())
                .bmGrowthSales(d.getBmGrowthSales())
                .updateFlag(d.getUpdateFlag())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.EXPRESS.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = expressMapper.selectCount(null);
            String maxAnnDate = expressMapper.selectMaxAnnDate();
            LocalDate today = LocalDate.now();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("season_coverage")
                        .displayName("快报季覆盖检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("revenue_assets_validity")
                        .displayName("营收与总资产有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("growth_consistency")
                        .displayName("增长一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                return DataCheckResult.builder()
                        .tableCode(getTableCode())
                        .tableName(InitStep.EXPRESS.getLabel())
                        .totalRows(0)
                        .latestDate(null)
                        .items(items)
                        .build();
            }

            // Check 1: Season coverage (WARN) - 上一个财报季
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
            long seasonCount = expressMapper.selectCount(
                    new LambdaQueryWrapper<ExpressDO>()
                            .likeRight(ExpressDO::getAnnDate, lastSeasonMonth)
            );
            boolean seasonPassed = seasonCount > 0;
            String seasonMsg = seasonPassed
                    ? "通过，" + lastSeasonMonth + " 月有 " + seasonCount + " 条快报数据"
                    : "上一快报季首月（" + lastSeasonMonth + "）无任何快报数据";
            items.add(DataCheckItem.builder()
                    .name("season_coverage")
                    .displayName("快报季覆盖检测")
                    .passed(seasonPassed)
                    .level(CheckLevel.WARN)
                    .message(seasonMsg)
                    .build());

            // Check 2: Revenue & assets validity (ERROR) - 最近报告期
            String maxEndDate = expressMapper.selectMaxEndDate();
            String raMsg;
            boolean raPassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                raPassed = false;
                raMsg = "无任何报告期数据";
            } else {
                int badCount = expressMapper.countInvalidRevenueAssets(maxEndDate);
                raPassed = badCount == 0;
                raMsg = raPassed ? "通过，最新报告期 " + maxEndDate + " 营收与总资产数据正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条营收或总资产为负";
            }
            items.add(DataCheckItem.builder()
                    .name("revenue_assets_validity")
                    .displayName("营收与总资产有效性检测")
                    .passed(raPassed)
                    .level(CheckLevel.ERROR)
                    .message(raMsg)
                    .build());

            // Check 3: Growth consistency (WARN)
            int growthErrorCount = expressMapper.countGrowthConsistencyErrors();
            boolean growthPassed = growthErrorCount == 0;
            String growthMsg = growthPassed
                    ? "通过，无增长一致性错误"
                    : "有 " + growthErrorCount + " 条正增长但净利润更少的异常";
            items.add(DataCheckItem.builder()
                    .name("growth_consistency")
                    .displayName("增长一致性检测")
                    .passed(growthPassed)
                    .level(CheckLevel.WARN)
                    .message(growthMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.EXPRESS.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for express", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.EXPRESS.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
