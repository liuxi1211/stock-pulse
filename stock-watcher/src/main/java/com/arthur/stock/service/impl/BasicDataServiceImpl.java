package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.DailyBasicDTO;
import com.arthur.stock.dto.tushare.FinaIndicatorDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.mapper.FinaIndicatorMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.FinaIndicatorDO;
import com.arthur.stock.service.BasicDataService;
import com.arthur.stock.service.StockBasicService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基本面数据服务实现：daily_basic（估值/换手率/市值）+ fina_indicator（财务指标）。
 * <p>
 * daily_basic 按交易日拉全市场；fina_indicator 按股票拉取最近若干报告期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasicDataServiceImpl implements BasicDataService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final DailyBasicMapper dailyBasicMapper;
    private final FinaIndicatorMapper finaIndicatorMapper;
    private final StockBasicService stockBasicService;

    @Override
    public int fetchAndSaveDailyBasic(String tradeDate) {
        if (tradeDate == null || tradeDate.isBlank()) {
            log.warn("fetchAndSaveDailyBasic: tradeDate 为空，跳过");
            return 0;
        }
        log.info("拉取 daily_basic tradeDate={}", tradeDate);
        List<DailyBasicDTO> rows = tushareClient.dailyBasic(tradeDate, null);
        if (rows == null || rows.isEmpty()) {
            log.info("daily_basic tradeDate={} 无数据", tradeDate);
            return 0;
        }
        List<DailyBasicDO> entities = rows.stream().map(this::toDailyBasicDO).collect(Collectors.toList());
        saveDailyBasic(entities);
        log.info("daily_basic tradeDate={} 保存 {} 条", tradeDate, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveFinaIndicator(String startPeriod, String endPeriod) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("fina_indicator: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 fina_indicator [{}~{}], 共 {} 只股票", startPeriod, endPeriod, stocks.size());
        int total = 0;
        int batch = 0;
        for (StockBasicDTO s : stocks) {
            try {
                List<FinaIndicatorDTO> rows = tushareClient.finaIndicator(s.getTsCode(), startPeriod, endPeriod);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                List<FinaIndicatorDO> entities = rows.stream().map(this::toFinaIndicatorDO).collect(Collectors.toList());
                saveFinaIndicator(entities);
                total += entities.size();
                batch++;
            } catch (Exception e) {
                log.warn("fina_indicator {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("fina_indicator 拉取完成，共 {} 只股票，{} 条记录", batch, total);
        return total;
    }

    // ==================== 内部 ====================

    private void saveDailyBasic(List<DailyBasicDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            dailyBasicMapper.deleteBatchByKeys(batch);
            dailyBasicMapper.insertBatch(batch);
        });
    }

    private void saveFinaIndicator(List<FinaIndicatorDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            finaIndicatorMapper.deleteBatchByKeys(batch);
            finaIndicatorMapper.insertBatch(batch);
        });
    }

    private DailyBasicDO toDailyBasicDO(DailyBasicDTO d) {
        return DailyBasicDO.builder()
                .tradeDate(d.getTradeDate())
                .tsCode(d.getTsCode())
                .close(d.getClose())
                .turnoverRate(d.getTurnoverRate())
                .turnoverRateF(d.getTurnoverRateF())
                .volumeRatio(d.getVolumeRatio())
                .pe(d.getPe())
                .peTtm(d.getPeTtm())
                .pb(d.getPb())
                .ps(d.getPs())
                .psTtm(d.getPsTtm())
                .dvRatio(d.getDvRatio())
                .dvTtm(d.getDvTtm())
                .totalShare(d.getTotalShare())
                .floatShare(d.getFloatShare())
                .freeShare(d.getFreeShare())
                .totalMv(d.getTotalMv())
                .circMv(d.getCircMv())
                .build();
    }

    private FinaIndicatorDO toFinaIndicatorDO(FinaIndicatorDTO d) {
        return FinaIndicatorDO.builder()
                .tsCode(d.getTsCode())
                .endDate(d.getEndDate())
                .annDate(d.getAnnDate())
                .roe(d.getRoe())
                .roa(d.getRoa())
                .grossprofitMargin(d.getGrossprofitMargin())
                .netprofitMargin(d.getNetprofitMargin())
                .dtNetprofitYoy(d.getDtNetprofitYoy())
                .revenueYoy(d.getRevenueYoy())
                .debtToAssets(d.getDebtToAssets())
                .epsYoy(d.getEpsYoy())
                .build();
    }
}
