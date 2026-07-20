package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.CashflowDTO;
import com.arthur.stock.dto.tushare.CashflowQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.CashflowMapper;
import com.arthur.stock.model.CashflowDO;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 现金流量表服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 cashflow 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashflowServiceImpl implements CashflowService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final CashflowMapper cashflowMapper;
    private final StockBasicService stockBasicService;

    @Override
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
        List<CashflowDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
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
}
