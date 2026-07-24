package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.CashflowDTO;
import com.arthur.stock.dto.tushare.CashflowQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.CashflowMapper;
import com.arthur.stock.model.CashflowDO;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 现金流量表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 cashflow 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashflowServiceImpl implements CashflowService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final CashflowMapper cashflowMapper;
    private final StockBasicService stockBasicService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveCashflow(String tsCode, String startDate, String endDate) {
        log.info("拉取 cashflow {} [{}~{}]", tsCode, startDate, endDate);
        CashflowQueryDTO param = CashflowQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<CashflowDTO> rows = tushareClient.cashflow(param);
        if (rows == null || rows.isEmpty()) {
            log.info("cashflow {} 无数据", tsCode);
            return 0;
        }
        List<CashflowDO> entities = rows.stream()
                .map(this::toEntity)
                // 按唯一键 (ts_code, end_date, report_type) 去重，保留 ann_date 最新的一条
                .collect(Collectors.toMap(
                        e -> e.getTsCode() + "|" + e.getEndDate() + "|" + e.getReportType(),
                        e -> e,
                        (a, b) -> compareAnnDate(a.getAnnDate(), b.getAnnDate()) >= 0 ? a : b,
                        java.util.LinkedHashMap::new
                ))
                .values()
                .stream()
                .collect(Collectors.toList());
        saveBatch(entities);
        log.info("cashflow {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("cashflow: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 cashflow [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int batch = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveCashflow(s.getTsCode(), startDate, endDate);
                batch++;
            } catch (Exception e) {
                log.warn("cashflow {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("cashflow 拉取完成，{} 只股票成功，共 {} 条记录", batch, total);
        return total;
    }

    @Override
    public List<CashflowDO> queryLocalByTsCode(String tsCode) {
        return cashflowMapper.selectList(
                new LambdaQueryWrapper<CashflowDO>()
                        .eq(CashflowDO::getTsCode, tsCode)
                        .orderByAsc(CashflowDO::getEndDate));
    }

    @Override
    public CashflowDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return cashflowMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    /**
     * 比较两个公告日期字符串（yyyyMMdd 格式），返回值同 Comparator.compare。
     * null 视为最小（即非 null 的那条更新）。
     */
    private int compareAnnDate(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private void saveBatch(List<CashflowDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            cashflowMapper.deleteBatchByKeys(batch);
            cashflowMapper.insertBatch(batch);
        });
    }

    private CashflowDO toEntity(CashflowDTO d) {
        return CashflowDO.builder()
                .tsCode(d.getTsCode())
                .annDate(d.getAnnDate())
                .fAnnDate(d.getFAnnDate())
                .endDate(d.getEndDate())
                .reportType(d.getReportType())
                .compType(d.getCompType())
                .nCashflowAct(d.getNCashflowAct())
                .nCashflowInvAct(d.getNCashflowInvAct())
                .nCashFlowsFncAct(d.getNCashFlowsFncAct())
                .freeCashflow(d.getFreeCashflow())
                .cFrSaleSg(d.getCFrSaleSg())
                .cFrOthSg(d.getCFrOthSg())
                .cPaidGoodsS(d.getCPaidGoodsS())
                .cPaidToForEmpl(d.getCPaidToForEmpl())
                .cPaidForTaxes(d.getCPaidForTaxes())
                .cPaidOthOpF(d.getCPaidOthOpF())
                .cPaidInvest(d.getCPaidInvest())
                .cPaidInvestF(d.getCPaidInvestF())
                .cPayAcqConstFiolta(d.getCPayAcqConstFiolta())
                .cPayAcqIntLongLoan(d.getCPayAcqIntLongLoan())
                .dispFixAssetsOth(d.getDispFixAssetsOth())
                .nInvestLoss(d.getNInvestLoss())
                .cFrFncLoan(d.getCFrFncLoan())
                .cFrFncOth(d.getCFrFncOth())
                .proceedsLongLoan(d.getProceedsLongLoan())
                .cPaidFinFees(d.getCPaidFinFees())
                .cPayDistDpcpIntExp(d.getCPayDistDpcpIntExp())
                .endBalCash(d.getEndBalCash())
                .begBalCash(d.getBegBalCash())
                .nCashEqu(d.getNCashEqu())
                .nIncreaseInclChild(d.getNIncreaseInclChild())
                .provDeprAssets(d.getProvDeprAssets())
                .deprFaCogaDpba(d.getDeprFaCogaDpba())
                .amortIntang(d.getAmortIntang())
                .amortLtDeferredExp(d.getAmortLtDeferredExp())
                .lossDispFa(d.getLossDispFa())
                .lossScrFa(d.getLossScrFa())
                .lossFairValu(d.getLossFairValu())
                .finExp(d.getFinExp())
                .lossInv(d.getLossInv())
                .decDefIncTaxAssets(d.getDecDefIncTaxAssets())
                .incDefIncTaxLiab(d.getIncDefIncTaxLiab())
                .decInv(d.getDecInv())
                .decOperRece(d.getDecOperRece())
                .incOperPayable(d.getIncOperPayable())
                .netProfit(d.getNetProfit())
                .minorityInterest(d.getMinorityInterest())
                .undistributedProfitIn(d.getUndistributedProfitIn())
                .updateFlag(d.getUpdateFlag())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.CASHFLOW.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = cashflowMapper.selectCount(null);
            String maxAnnDate = cashflowMapper.selectMaxAnnDate();
            LocalDate today = LocalDate.now();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("freshness")
                        .displayName("公告新鲜度检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("operating_cashflow_validity")
                        .displayName("经营现金流有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("cash_increase_consistency")
                        .displayName("现金净增加额一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                return DataCheckResult.builder()
                        .tableCode(getTableCode())
                        .tableName(InitStep.CASHFLOW.getLabel())
                        .totalRows(0)
                        .latestDate(null)
                        .items(items)
                        .build();
            }

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

            // Check 2: Operating cashflow validity in latest period (ERROR)
            String maxEndDate = cashflowMapper.selectMaxEndDate();
            String ocfMsg;
            boolean ocfPassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                ocfPassed = false;
                ocfMsg = "无任何报告期数据";
            } else {
                int badCount = cashflowMapper.countNullOperatingCashflow(maxEndDate);
                ocfPassed = badCount == 0;
                ocfMsg = ocfPassed ? "通过，最新报告期 " + maxEndDate + " 经营现金流数据正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条经营现金流为空";
            }
            items.add(DataCheckItem.builder()
                    .name("operating_cashflow_validity")
                    .displayName("经营现金流有效性检测")
                    .passed(ocfPassed)
                    .level(CheckLevel.ERROR)
                    .message(ocfMsg)
                    .build());

            // Check 3: Cash increase consistency (WARN) - 最近3个季度
            boolean consistencyPassed;
            String consistencyMsg;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                consistencyPassed = true;
                consistencyMsg = "无数据，跳过检测";
            } else {
                String nineMonthsAgo = today.minusMonths(9).format(DATE_FMT);
                int errorCount = cashflowMapper.countCashIncreaseInconsistency(nineMonthsAgo);
                consistencyPassed = errorCount == 0;
                consistencyMsg = consistencyPassed ? "通过，近 3 季度无现金净增加额异常"
                        : "近 3 季度有 " + errorCount + " 条现金净增加额偏差超 10%";
            }
            items.add(DataCheckItem.builder()
                    .name("cash_increase_consistency")
                    .displayName("现金净增加额一致性检测")
                    .passed(consistencyPassed)
                    .level(CheckLevel.WARN)
                    .message(consistencyMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.CASHFLOW.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for cashflow", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.CASHFLOW.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
