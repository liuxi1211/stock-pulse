package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.dto.tushare.DividendQueryDTO;
import com.arthur.stock.mapper.DividendMapper;
import com.arthur.stock.model.DividendDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.DividendService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.collect.Lists;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分红送股服务实现类，负责从Tushare获取分红送股数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendServiceImpl implements DividendService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final String DATE_FORMAT_PATTERN = "yyyyMMdd";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN);

    private final TushareClient tushareClient;
    private final DividendMapper dividendMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<DividendDTO> queryByTsCode(String tsCode) {
        List<DividendDO> records = dividendMapper.selectList(
                new LambdaQueryWrapper<DividendDO>()
                        .eq(DividendDO::getTsCode, tsCode)
                        .orderByDesc(DividendDO::getEndDate));
        return records.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public List<DividendDTO> fetchAndSaveDividend(String tsCode) {
        log.info("Fetching dividend for {}", tsCode);

        DividendQueryDTO param = DividendQueryDTO.builder()
                .tsCode(tsCode)
                .build();
        List<DividendDTO> dividends = tushareClient.dividend(param);

        if (dividends.isEmpty()) {
            log.info("No dividend data returned for {}", tsCode);
            return Collections.emptyList();
        }

        List<DividendDO> entities = dividends.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveDividends(entities);
        log.info("Saved {} dividend records for {}", entities.size(), tsCode);
        return dividends;
    }

    @Override
    public List<DividendDTO> fetchAndSaveByAnnDate(String annDate) {
        log.info("Fetching dividend for ann_date={}", annDate);

        DividendQueryDTO param = DividendQueryDTO.builder()
                .annDate(annDate)
                .build();
        List<DividendDTO> dividends = tushareClient.dividend(param);

        if (dividends.isEmpty()) {
            log.info("No dividend data returned for ann_date={}", annDate);
            return Collections.emptyList();
        }

        List<DividendDO> entities = dividends.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveDividends(entities);
        log.info("Saved {} dividend records for ann_date={}", entities.size(), annDate);
        return dividends;
    }

    @Override
    public List<DividendDTO> fetchAndSaveDividendByRange(String tsCode, String startDate, String endDate) {
        log.info("Fetching dividend for {} [{}~{}]", tsCode, startDate, endDate);

        DividendQueryDTO param = DividendQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<DividendDTO> dividends = tushareClient.dividend(param);

        if (dividends.isEmpty()) {
            log.info("No dividend data returned for {} [{}~{}]", tsCode, startDate, endDate);
            return Collections.emptyList();
        }

        List<DividendDO> entities = dividends.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveDividends(entities);
        log.info("Saved {} dividend records for {} [{}~{}]", entities.size(), tsCode, startDate, endDate);
        return dividends;
    }

    /**
     * 按公告日期范围全市场批量拉取分红送股数据并保存。
     * <p>
     * 遍历 [startDate, endDate] 内每个公告日，调用全市场 ann_date 批量接口
     * ({@link TushareClient#dividendByAnnDate(String)})。HTTP 在事务外，仅持久化
     * （delete+insert）包裹在 {@link TransactionTemplate} 内（A-class）。
     * 分红公告稀疏，多数公告日返回空，直接跳过；每个有数据的公告日独立提交一次。
     * <p>
     * 与 {@link #fetchAndSaveDividendByRange} 的 per-stock 增量路径互为补充：
     * 该方法面向"按 ann_date 全市场批量"的增量/补数据场景，由调度层按需选用。
     *
     * @param startDate 起始公告日期（含，格式 yyyyMMdd）
     * @param endDate   结束公告日期（含，格式 yyyyMMdd）
     * @return 区间内拉取并保存的记录总数
     */
    @Override
    public int fetchAndSaveByAnnDateRange(String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);
        if (start.isAfter(end)) {
            log.warn("dividend ann_date 增量拉取：startDate={} 晚于 endDate={}，跳过", startDate, endDate);
            return 0;
        }
        log.info("Fetching dividend by ann_date range [{}~{}]", startDate, endDate);

        int total = 0;
        int hitDays = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String annDate = date.format(DATE_FORMATTER);
            List<DividendDTO> dividends;
            try {
                // ⚠️ API 调用在事务外执行，避免限流等待时长时间占用数据库连接
                dividends = tushareClient.dividendByAnnDate(annDate);
            } catch (Exception e) {
                log.warn("dividend ann_date={} 拉取失败: {}", annDate, e.getMessage());
                continue;
            }
            if (dividends == null || dividends.isEmpty()) {
                continue;
            }
            hitDays++;
            List<DividendDO> entities = dividends.stream()
                    .map(this::toEntity)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            saveDividends(entities);
            total += entities.size();
            log.info("Saved {} dividend records for ann_date={}", entities.size(), annDate);
        }

        log.info("Fetched total {} dividend records for ann_date range [{}~{}], hit {} days",
                total, startDate, endDate, hitDays);
        return total;
    }

    private DividendDO toEntity(DividendDTO dto) {
        return DividendDO.builder()
                .tsCode(dto.getTsCode())
                .endDate(dto.getEndDate())
                .annDate(dto.getAnnDate())
                .divProc(dto.getDivProc())
                .stkDiv(dto.getStkDiv())
                .stkBoRate(dto.getStkBoRate())
                .stkCoRate(dto.getStkCoRate())
                .cashDiv(dto.getCashDiv())
                .cashDivTax(dto.getCashDivTax())
                .recordDate(dto.getRecordDate())
                .exDate(dto.getExDate())
                .payDate(dto.getPayDate())
                .divListdate(dto.getDivListdate())
                .impAnnDate(dto.getImpAnnDate())
                .baseDate(dto.getBaseDate())
                .baseShare(dto.getBaseShare())
                .build();
    }

    private DividendDTO toDTO(DividendDO entity) {
        return DividendDTO.builder()
                .tsCode(entity.getTsCode())
                .endDate(entity.getEndDate())
                .annDate(entity.getAnnDate())
                .divProc(entity.getDivProc())
                .stkDiv(entity.getStkDiv())
                .stkBoRate(entity.getStkBoRate())
                .stkCoRate(entity.getStkCoRate())
                .cashDiv(entity.getCashDiv())
                .cashDivTax(entity.getCashDivTax())
                .recordDate(entity.getRecordDate())
                .exDate(entity.getExDate())
                .payDate(entity.getPayDate())
                .divListdate(entity.getDivListdate())
                .impAnnDate(entity.getImpAnnDate())
                .baseDate(entity.getBaseDate())
                .baseShare(entity.getBaseShare())
                .build();
    }

    /**
     * 批量保存分红数据。先删除同唯一键（ts_code+end_date+ann_date）已存在记录，再插入；跨方言通用。
     * 持久化（delete+insert）包裹在 {@link TransactionTemplate} 内，HTTP 已在事务外完成（A-class）。
     */
    private void saveDividends(List<DividendDO> dividends) {
        transactionTemplate.execute(status -> {
            Lists.partition(dividends, BATCH_SIZE).forEach(batch -> {
                dividendMapper.deleteBatchByKeys(batch);
                dividendMapper.insertBatch(batch);
            });
            return null;
        });
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.DIVIDEND.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = dividendMapper.selectCount(null);
            String latestDate = dividendMapper.selectMaxAnnDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("cash_div_validity")
                        .displayName("现金分红有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("stock_div_validity")
                        .displayName("送股有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("date_logic")
                        .displayName("日期逻辑检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                int invalidCashDiv = dividendMapper.countInvalidCashDiv();
                items.add(DataCheckItem.builder()
                        .name("cash_div_validity")
                        .displayName("现金分红有效性检测")
                        .passed(invalidCashDiv == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidCashDiv == 0 ? "通过，现金分红数据正常"
                                : "现金分红为负的记录 " + invalidCashDiv + " 条")
                        .build());

                int invalidStockDiv = dividendMapper.countInvalidStockDiv();
                items.add(DataCheckItem.builder()
                        .name("stock_div_validity")
                        .displayName("送股有效性检测")
                        .passed(invalidStockDiv == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidStockDiv == 0 ? "通过，送股数据正常"
                                : "送股比例为负的记录 " + invalidStockDiv + " 条")
                        .build());

                int dateLogicErrors = dividendMapper.countDateLogicErrors();
                items.add(DataCheckItem.builder()
                        .name("date_logic")
                        .displayName("日期逻辑检测")
                        .passed(dateLogicErrors == 0)
                        .level(CheckLevel.ERROR)
                        .message(dateLogicErrors == 0 ? "通过，日期逻辑正常"
                                : "日期逻辑异常记录 " + dateLogicErrors + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DIVIDEND.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for dividend", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DIVIDEND.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}