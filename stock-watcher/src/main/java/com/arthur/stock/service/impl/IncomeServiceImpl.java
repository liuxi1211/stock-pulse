package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.IncomeDTO;
import com.arthur.stock.dto.tushare.IncomeQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.IncomeMapper;
import com.arthur.stock.model.IncomeDO;
import com.arthur.stock.service.IncomeService;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 利润表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 income 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncomeServiceImpl implements IncomeService {

    private static final int BATCH_SIZE = 500;

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
}
