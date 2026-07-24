package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.FinaIndicatorDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.FinaIndicatorMapper;
import com.arthur.stock.model.FinaIndicatorDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.FinaIndicatorService;
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
 * 财务指标服务实现：从 Tushare 按 ts_code + 报告期范围拉取，批量 upsert 到 fina_indicator 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinaIndicatorServiceImpl implements FinaIndicatorService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final FinaIndicatorMapper finaIndicatorMapper;
    private final StockBasicService stockBasicService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveFinaIndicator(String tsCode, String startDate, String endDate) {
        log.info("拉取 fina_indicator {} [{}~{}]", tsCode, startDate, endDate);
        List<FinaIndicatorDTO> rows = tushareClient.finaIndicator(tsCode, startDate, endDate);
        if (rows == null || rows.isEmpty()) {
            log.info("fina_indicator {} 无数据", tsCode);
            return 0;
        }
        List<FinaIndicatorDO> entities = rows.stream().map(this::toEntity).collect(Collectors.toList());
        saveBatch(entities);
        log.info("fina_indicator {} 保存 {} 条", tsCode, entities.size());
        return entities.size();
    }

    @Override
    public int fetchAndSaveAllByRange(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            log.warn("fina_indicator: 本地无在市股票，跳过");
            return 0;
        }
        log.info("拉取 fina_indicator [{}~{}], 共 {} 只股票", startDate, endDate, stocks.size());
        int total = 0;
        int batch = 0;
        for (StockBasicDTO s : stocks) {
            try {
                total += fetchAndSaveFinaIndicator(s.getTsCode(), startDate, endDate);
                batch++;
            } catch (Exception e) {
                log.warn("fina_indicator {} 拉取失败: {}", s.getTsCode(), e.getMessage());
            }
        }
        log.info("fina_indicator 拉取完成，{} 只股票成功，共 {} 条记录", batch, total);
        return total;
    }

    @Override
    public List<FinaIndicatorDO> queryLocalByTsCode(String tsCode) {
        return finaIndicatorMapper.selectList(
                new LambdaQueryWrapper<FinaIndicatorDO>()
                        .eq(FinaIndicatorDO::getTsCode, tsCode)
                        .orderByAsc(FinaIndicatorDO::getEndDate));
    }

    @Override
    public FinaIndicatorDO selectLatestAnnouncedBefore(String tsCode, String tradeDate) {
        return finaIndicatorMapper.selectLatestAnnouncedBefore(tsCode, tradeDate);
    }

    // ==================== 内部 ====================

    private void saveBatch(List<FinaIndicatorDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            finaIndicatorMapper.deleteBatchByKeys(batch);
            finaIndicatorMapper.insertBatch(batch);
        });
    }

    private FinaIndicatorDO toEntity(FinaIndicatorDTO d) {
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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.FINA_INDICATOR.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = finaIndicatorMapper.selectCount(null);
            String maxAnnDate = finaIndicatorMapper.selectMaxAnnDate();
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
                        .name("roe_roa_validity")
                        .displayName("ROE/ROA有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("debt_ratio_validity")
                        .displayName("资产负债率有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                return DataCheckResult.builder()
                        .tableCode(getTableCode())
                        .tableName(InitStep.FINA_INDICATOR.getLabel())
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

            // Check 2: ROE/ROA validity in latest period (ERROR)
            String maxEndDate = finaIndicatorMapper.selectMaxEndDate();
            String rrMsg;
            boolean rrPassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                rrPassed = false;
                rrMsg = "无任何报告期数据";
            } else {
                int badCount = finaIndicatorMapper.countNullRoeRoa(maxEndDate);
                rrPassed = badCount == 0;
                rrMsg = rrPassed ? "通过，最新报告期 " + maxEndDate + " ROE/ROA 数据正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条 ROE 和 ROA 都为空";
            }
            items.add(DataCheckItem.builder()
                    .name("roe_roa_validity")
                    .displayName("ROE/ROA有效性检测")
                    .passed(rrPassed)
                    .level(CheckLevel.ERROR)
                    .message(rrMsg)
                    .build());

            // Check 3: Debt ratio validity (ERROR) - 最近报告期
            String debtMsg;
            boolean debtPassed;
            if (maxEndDate == null || maxEndDate.isEmpty()) {
                debtPassed = false;
                debtMsg = "无任何报告期数据";
            } else {
                int badCount = finaIndicatorMapper.countInvalidDebtRatio(maxEndDate);
                debtPassed = badCount == 0;
                debtMsg = debtPassed ? "通过，最新报告期 " + maxEndDate + " 资产负债率正常"
                        : "最新报告期 " + maxEndDate + " 有 " + badCount + " 条资产负债率异常（<0或>100）";
            }
            items.add(DataCheckItem.builder()
                    .name("debt_ratio_validity")
                    .displayName("资产负债率有效性检测")
                    .passed(debtPassed)
                    .level(CheckLevel.ERROR)
                    .message(debtMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.FINA_INDICATOR.getLabel())
                    .totalRows(totalRows)
                    .latestDate(maxAnnDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for fina_indicator", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.FINA_INDICATOR.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
