package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.BalancesheetDTO;
import com.arthur.stock.dto.tushare.BalancesheetQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.BalancesheetMapper;
import com.arthur.stock.model.BalancesheetDO;
import com.arthur.stock.service.BalancesheetService;
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
 * 资产负债表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 balancesheet 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalancesheetServiceImpl implements BalancesheetService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final BalancesheetMapper balancesheetMapper;
    private final StockBasicService stockBasicService;

    @Override
    public int fetchAndSaveBalancesheet(String tsCode, String startDate, String endDate) {
        log.info("拉取 balancesheet {} [{}~{}]", tsCode, startDate, endDate);
        BalancesheetQueryDTO param = BalancesheetQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<BalancesheetDTO> rows = tushareClient.balancesheet(param);
        if (rows == null || rows.isEmpty()) {
            log.info("balancesheet {} 无数据", tsCode);
            return 0;
        }
        List<BalancesheetDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        saveBatch(entities);
        log.info("balancesheet {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("balancesheet: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 balancesheet [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int batch = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveBalancesheet(s.getTsCode(), startDate, endDate);
                batch++;
            } catch (Exception e) {
                log.warn("balancesheet {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("balancesheet 拉取完成，{} 只股票成功，共 {} 条记录", batch, total);
        return total;
    }

    @Override
    public List<BalancesheetDO> queryLocalByTsCode(String tsCode) {
        return balancesheetMapper.selectList(
                new LambdaQueryWrapper<BalancesheetDO>()
                        .eq(BalancesheetDO::getTsCode, tsCode)
                        .orderByAsc(BalancesheetDO::getEndDate));
    }

    @Override
    public BalancesheetDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return balancesheetMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    private void saveBatch(List<BalancesheetDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            balancesheetMapper.deleteBatchByKeys(batch);
            balancesheetMapper.insertBatch(batch);
        });
    }

    private BalancesheetDO toEntity(BalancesheetDTO d) {
        return BalancesheetDO.builder()
                .tsCode(d.getTsCode())
                .annDate(d.getAnnDate())
                .fAnnDate(d.getFAnnDate())
                .endDate(d.getEndDate())
                .reportType(d.getReportType())
                .compType(d.getCompType())
                .monetaryFunds(d.getMonetaryFunds())
                .accountsRece(d.getAccountsRece())
                .notesRece(d.getNotesRece())
                .accountsReceFin(d.getAccountsReceFin())
                .otherRece(d.getOtherRece())
                .prepayment(d.getPrepayment())
                .dividendsRece(d.getDividendsRece())
                .intRece(d.getIntRece())
                .inventories(d.getInventories())
                .nonCurrentAssetsIn1Yr(d.getNonCurrentAssetsIn1Yr())
                .otherCurrentAssets(d.getOtherCurrentAssets())
                .totalCurrentAssets(d.getTotalCurrentAssets())
                .equityJointCap(d.getEquityJointCap())
                .ltReceivable(d.getLtReceivable())
                .eqtInvest(d.getEqtInvest())
                .invRealEstate(d.getInvRealEstate())
                .fixAssetsNca(d.getFixAssetsNca())
                .cip(d.getCip())
                .constructionMaterials(d.getConstructionMaterials())
                .intangAssets(d.getIntangAssets())
                .goodwill(d.getGoodwill())
                .ltAmortDeferredExp(d.getLtAmortDeferredExp())
                .deferTaxAssets(d.getDeferTaxAssets())
                .otherNonCurrentAssets(d.getOtherNonCurrentAssets())
                .totalNonCurrentAssets(d.getTotalNonCurrentAssets())
                .totalAssets(d.getTotalAssets())
                .ltBorr(d.getLtBorr())
                .notesPayable(d.getNotesPayable())
                .accountsPayable(d.getAccountsPayable())
                .accountsPayableFin(d.getAccountsPayableFin())
                .prepaymentReceivables(d.getPrepaymentReceivables())
                .wagePayable(d.getWagePayable())
                .taxesSurcharges(d.getTaxesSurcharges())
                .otherPayable(d.getOtherPayable())
                .nonCurrentLiabIn1Yr(d.getNonCurrentLiabIn1Yr())
                .otherCurrentLiab(d.getOtherCurrentLiab())
                .totalCurrentLiab(d.getTotalCurrentLiab())
                .longTermBorr(d.getLongTermBorr())
                .ppayableBonds(d.getPpayableBonds())
                .longTermPayable(d.getLongTermPayable())
                .specificPayable(d.getSpecificPayable())
                .estimatedLiab(d.getEstimatedLiab())
                .deferTaxLiab(d.getDeferTaxLiab())
                .deferIncNonCurrLiab(d.getDeferIncNonCurrLiab())
                .otherNonCurrentLiab(d.getOtherNonCurrentLiab())
                .totalNonCurrentLiab(d.getTotalNonCurrentLiab())
                .totalLiab(d.getTotalLiab())
                .shareCapital(d.getShareCapital())
                .capitalReserve(d.getCapitalReserve())
                .treasuryStock(d.getTreasuryStock())
                .specificReserves(d.getSpecificReserves())
                .surplusReserve(d.getSurplusReserve())
                .generalRiskReserve(d.getGeneralRiskReserve())
                .undistributedProfit(d.getUndistributedProfit())
                .equityParentCompany(d.getEquityParentCompany())
                .minorityInterest(d.getMinorityInterest())
                .totalEquity(d.getTotalEquity())
                .totalLiabEquity(d.getTotalLiabEquity())
                .accountsReceDecr(d.getAccountsReceDecr())
                .accountsReceFinDecr(d.getAccountsReceFinDecr())
                .minorityInterestInc(d.getMinorityInterestInc())
                .minorityInterestDec(d.getMinorityInterestDec())
                .updateFlag(d.getUpdateFlag())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.BALANCESHEET.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = balancesheetMapper.selectCount(null);
            String maxAnnDate = balancesheetMapper.selectMaxAnnDate();
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
                        .name("total_assets_validity")
                        .displayName("总资产有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("accounting_identity")
                        .displayName("会计恒等式检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                return DataCheckResult.builder()
                        .tableCode(getTableCode())
                        .tableName(InitStep.BALANCESHEET.getLabel())
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

            // Check 2: Total assets validity in latest period (ERROR)
            String maxEndDate = balancesheetMapper.selectMaxEndDate();
            String assetsMsg;
            boolean assetsPassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                assetsPassed = false;
                assetsMsg = "无任何报告期数据";
            } else {
                int badCount = balancesheetMapper.countInvalidTotalAssets(maxEndDate);
                assetsPassed = badCount == 0;
                assetsMsg = assetsPassed ? "通过，最新报告期 " + maxEndDate + " 总资产数据正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条总资产为空或非正";
            }
            items.add(DataCheckItem.builder()
                    .name("total_assets_validity")
                    .displayName("总资产有效性检测")
                    .passed(assetsPassed)
                    .level(CheckLevel.ERROR)
                    .message(assetsMsg)
                    .build());

            // Check 3: Accounting identity (ERROR) - 最近3个季度
            boolean identityPassed;
            String identityMsg;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                identityPassed = true;
                identityMsg = "无数据，跳过检测";
            } else {
                String nineMonthsAgo = today.minusMonths(9).format(DATE_FMT);
                int errorCount = balancesheetMapper.countAccountingIdentityErrors(nineMonthsAgo);
                identityPassed = errorCount == 0;
                identityMsg = identityPassed ? "通过，近 3 季度无会计恒等式异常"
                        : "近 3 季度有 " + errorCount + " 条会计恒等式偏差超 1%";
            }
            items.add(DataCheckItem.builder()
                    .name("accounting_identity")
                    .displayName("会计恒等式检测")
                    .passed(identityPassed)
                    .level(CheckLevel.ERROR)
                    .message(identityMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.BALANCESHEET.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for balancesheet", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.BALANCESHEET.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
