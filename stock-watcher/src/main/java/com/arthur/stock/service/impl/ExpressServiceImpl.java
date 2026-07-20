package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.ExpressDTO;
import com.arthur.stock.dto.tushare.ExpressQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.ExpressMapper;
import com.arthur.stock.model.ExpressDO;
import com.arthur.stock.service.ExpressService;
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
 * 业绩快报服务实现：从 Tushare 按 ts_code + 日期范围拉取，批量 upsert 到 express 表。
 * 一个报告期一条快报，按 (ts_code, end_date) 唯一键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpressServiceImpl implements ExpressService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final ExpressMapper expressMapper;
    private final StockBasicService stockBasicService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveExpress(String tsCode, String startDate, String endDate) {
        log.info("拉取 express {} [{}~{}]", tsCode, startDate, endDate);
        ExpressQueryDTO param = ExpressQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<ExpressDTO> rows = tushareClient.express(param);
        if (rows == null || rows.isEmpty()) {
            log.info("express {} 无数据", tsCode);
            return 0;
        }
        List<ExpressDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        saveBatch(entities);
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
}
