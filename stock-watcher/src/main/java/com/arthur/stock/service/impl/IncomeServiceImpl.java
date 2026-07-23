package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.IncomeDTO;
import com.arthur.stock.dto.tushare.IncomeQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.IncomeMapper;
import com.arthur.stock.model.IncomeDO;
import com.arthur.stock.service.IncomeService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 利润表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 income 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncomeServiceImpl implements IncomeService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final IncomeMapper incomeMapper;
    private final StockBasicService stockBasicService;

    @Override
    public int fetchAndSaveIncome(String tsCode, String startDate, String endDate) {
        log.info("拉取 income {} [{}~{}]", tsCode, startDate, endDate);
        IncomeQueryDTO param = IncomeQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<IncomeDTO> rows = tushareClient.income(param);
        if (rows == null || rows.isEmpty()) {
            log.info("income {} 无数据", tsCode);
            return 0;
        }
        List<IncomeDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        saveBatch(entities);
        log.info("income {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("income: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 income [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int batch = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveIncome(s.getTsCode(), startDate, endDate);
                batch++;
            } catch (Exception e) {
                log.warn("income {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("income 拉取完成，{} 只股票成功，共 {} 条记录", batch, total);
        return total;
    }

    @Override
    public List<IncomeDO> queryLocalByTsCode(String tsCode) {
        return incomeMapper.selectList(
                new LambdaQueryWrapper<IncomeDO>()
                        .eq(IncomeDO::getTsCode, tsCode)
                        .orderByAsc(IncomeDO::getEndDate));
    }

    @Override
    public IncomeDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return incomeMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    private void saveBatch(List<IncomeDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            incomeMapper.deleteBatchByKeys(batch);
            incomeMapper.insertBatch(batch);
        });
    }

    private IncomeDO toEntity(IncomeDTO d) {
        return IncomeDO.builder()
                .tsCode(d.getTsCode())
                .annDate(d.getAnnDate())
                .fAnnDate(d.getFAnnDate())
                .endDate(d.getEndDate())
                .reportType(d.getReportType())
                .compType(d.getCompType())
                .basicEps(d.getBasicEps())
                .dilutedEps(d.getDilutedEps())
                .totalRevenue(d.getTotalRevenue())
                .revenue(d.getRevenue())
                .totalCogs(d.getTotalCogs())
                .operateCost(d.getOperateCost())
                .operateProfit(d.getOperateProfit())
                .nonOperIncome(d.getNonOperIncome())
                .nonOperExp(d.getNonOperExp())
                .totalProfit(d.getTotalProfit())
                .nIncome(d.getNIncome())
                .nIncomeAttrP(d.getNIncomeAttrP())
                .minorityInterest(d.getMinorityInterest())
                .adjustProfit(d.getAdjustProfit())
                .incomeTax(d.getIncomeTax())
                .nIncomeYoy(d.getNIncomeYoy())
                .dtProfitYoy(d.getDtProfitYoy())
                .sellExp(d.getSellExp())
                .adminExp(d.getAdminExp())
                .financialExp(d.getFinancialExp())
                .rdExp(d.getRdExp())
                .impairEndInvest(d.getImpairEndInvest())
                .impairEndOper(d.getImpairEndOper())
                .investIncome(d.getInvestIncome())
                .investIncomeInc(d.getInvestIncomeInc())
                .investIncomeDec(d.getInvestIncomeDec())
                .fairvalueChangeIncome(d.getFairvalueChangeIncome())
                .exchangeGain(d.getExchangeGain())
                .assetDisposeIncome(d.getAssetDisposeIncome())
                .otherIncome(d.getOtherIncome())
                .operateNIncome(d.getOperateNIncome())
                .creditImpairLoss(d.getCreditImpairLoss())
                .assetImpairLoss(d.getAssetImpairLoss())
                .bbit(d.getBbit())
                .bbitYoy(d.getBbitYoy())
                .operateProfitIncomeYoy(d.getOperateProfitIncomeYoy())
                .updateFlag(d.getUpdateFlag())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.INCOME.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = incomeMapper.selectCount(null);
            String maxAnnDate = incomeMapper.selectMaxAnnDate();
            LocalDate today = LocalDate.now();

            // Check 1: Announcement freshness (WARN)
            boolean freshnessPassed = true;
            String freshnessMsg;
            if (maxAnnDate == null || maxAnnDate.isEmpty()) {
                freshnessPassed = false;
                freshnessMsg = "无任何公告日期数据";
            } else {
                LocalDate annDate = LocalDate.parse(maxAnnDate, DATE_FMT);
                long daysSince = ChronoUnit.DAYS.between(annDate, today);
                int month = today.getMonthValue();
                boolean inEarningsSeason = (month >= 1 && month <= 4) || (month >= 7 && month <= 8) || month == 10;
                if (daysSince > 90 && !inEarningsSeason) {
                    freshnessPassed = false;
                    freshnessMsg = "距最近一次财报公告已超过 90 天（" + maxAnnDate + "）";
                } else {
                    freshnessMsg = "通过，最新公告日期 " + maxAnnDate;
                }
            }
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("公告新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.WARN)
                    .message(freshnessMsg)
                    .build());

            // Check 2: Revenue null/negative in latest period (ERROR)
            String maxEndDate = incomeMapper.selectMaxEndDate();
            String revenueMsg;
            boolean revenuePassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                revenuePassed = false;
                revenueMsg = "无任何报告期数据";
            } else {
                int badCount = incomeMapper.countRevenueNullOrNegative(maxEndDate);
                revenuePassed = badCount == 0;
                revenueMsg = revenuePassed ? "通过，最新报告期 " + maxEndDate + " 营收数据正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条营收为空或非正";
            }
            items.add(DataCheckItem.builder()
                    .name("revenue_validity")
                    .displayName("营收有效性检测")
                    .passed(revenuePassed)
                    .level(CheckLevel.ERROR)
                    .message(revenueMsg)
                    .build());

            // Check 3: Net income > revenue * 10 (WARN)
            boolean niPassed;
            String niMsg;
            if (totalRows == 0 || maxEndDate == null || maxEndDate.isEmpty()) {
                niPassed = true;
                niMsg = "无数据，跳过检测";
            } else {
                String nineMonthsAgo = today.minusMonths(9).format(DATE_FMT);
                int exceedsCount = incomeMapper.countNetIncomeExceedsRevenue(nineMonthsAgo);
                niPassed = exceedsCount == 0;
                niMsg = niPassed ? "通过，近 3 季度无净利润超营收 10 倍异常"
                        : "近 3 季度有 " + exceedsCount + " 条净利润超过营收 10 倍";
            }
            items.add(DataCheckItem.builder()
                    .name("net_income_anomaly")
                    .displayName("净利润异常检测")
                    .passed(niPassed)
                    .level(CheckLevel.WARN)
                    .message(niMsg)
                    .build());

            // Check 4: Listed > 1 year but no income data (WARN)
            boolean missingPassed;
            String missingMsg;
            if (totalRows == 0) {
                missingPassed = true;
                missingMsg = "无数据，跳过检测";
            } else {
                String oneYearAgo = today.minusYears(1).format(DATE_FMT);
                int noIncomeCount = incomeMapper.countListedOverYearNoIncome(oneYearAgo);
                missingPassed = noIncomeCount == 0;
                missingMsg = missingPassed ? "通过，上市超 1 年股票均有利润表数据"
                        : "上市超 1 年但无利润表数据的股票 " + noIncomeCount + " 只";
            }
            items.add(DataCheckItem.builder()
                    .name("missing_income_data")
                    .displayName("缺失利润表检测")
                    .passed(missingPassed)
                    .level(CheckLevel.WARN)
                    .message(missingMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INCOME.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for income", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INCOME.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
