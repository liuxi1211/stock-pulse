package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.ForecastDTO;
import com.arthur.stock.dto.tushare.ForecastQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.ForecastMapper;
import com.arthur.stock.model.ForecastDO;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.StockBasicService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 业绩预告服务实现：从 Tushare 按 ts_code + 日期范围拉取，批量 upsert 到 forecast 表。
 * forecast 表保留同一报告期的多次预告（首次+修正），按 (ts_code, end_date, ann_date) 唯一键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastServiceImpl implements ForecastService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final ForecastMapper forecastMapper;
    private final StockBasicService stockBasicService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveForecast(String tsCode, String startDate, String endDate) {
        log.info("拉取 forecast {} [{}~{}]", tsCode, startDate, endDate);
        ForecastQueryDTO param = ForecastQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<ForecastDTO> rows = tushareClient.forecast(param);
        if (rows == null || rows.isEmpty()) {
            log.info("forecast {} 无数据", tsCode);
            return 0;
        }
        List<ForecastDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        saveBatch(entities);
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
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            forecastMapper.deleteBatchByKeys(batch);
            forecastMapper.insertBatch(batch);
        });
    }

    private ForecastDO toEntity(ForecastDTO d) {
        return ForecastDO.builder()
                .tsCode(d.getTsCode())
                .annDate(d.getAnnDate())
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
}
