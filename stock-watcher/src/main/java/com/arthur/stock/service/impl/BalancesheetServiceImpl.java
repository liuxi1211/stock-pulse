package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.BalancesheetDTO;
import com.arthur.stock.dto.tushare.BalancesheetQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.BalancesheetMapper;
import com.arthur.stock.model.BalancesheetDO;
import com.arthur.stock.service.BalancesheetService;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 资产负债表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 balancesheet 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalancesheetServiceImpl implements BalancesheetService {

    private static final int BATCH_SIZE = 500;

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
}
