package com.arthur.stock.service;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.model.SwIndustryDO;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 指数日线行情抓取与定时同步服务。
 * <p>
 * 数据源：tushare index_daily 接口（指数日线 OHLCV）。
 * 落库策略：按 (ts_code, trade_date) 先删后插，实现幂等 upsert（跨 SQLite/MySQL 通用）。
 * <p>
 * 定时任务：每个交易日 16:30 盘后同步以下指数当日行情：
 * <ul>
 *   <li>4 个大盘指数：000001.SH / 399001.SZ / 399006.SZ / 000688.SH</li>
 *   <li>申万一级行业指数：从 sw_industry 表 level=1 动态读取（约 31 个，801010.SI ~ 801980.SI）</li>
 * </ul>
 * 限流由 {@link com.arthur.stock.client.RateLimiter} 按 api_name=index_daily 自动控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDailyFetchService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final IndexDailyMapper indexDailyMapper;
    private final SwIndustryMapper swIndustryMapper;

    /**
     * 拉取指定指数在 [startDate, endDate] 区间的日线行情并落库（幂等：同主键先删后插）。
     *
     * @param tsCode    指数代码（如 000001.SH）
     * @param startDate 起始交易日 yyyyMMdd（含）
     * @param endDate   结束交易日 yyyyMMdd（含）
     * @return 落库记录数
     */
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveIndexDaily(String tsCode, String startDate, String endDate) {
        log.info("Fetching index_daily: tsCode={}, {}~{}", tsCode, startDate, endDate);

        List<IndexDailyDO> rows = tushareClient.fetchIndexDaily(tsCode, startDate, endDate);
        if (rows == null || rows.isEmpty()) {
            log.info("No index_daily data for tsCode={}, {}~{}", tsCode, startDate, endDate);
            return 0;
        }

        // 过滤掉主键缺失的脏数据
        List<IndexDailyDO> entities = rows.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.getTsCode() != null && e.getTradeDate() != null)
                .collect(Collectors.toList());

        int saved = saveBatch(entities);
        log.info("Saved {} index_daily records for tsCode={}, {}~{}", saved, tsCode, startDate, endDate);
        return saved;
    }

    /**
     * 每交易日 16:30 盘后同步大盘指数 + 申万一级行业指数当日行情。
     * <p>
     * 单只失败不影响其他，逐只捕获异常并记录。
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI")
    public void dailySync() {
        log.info("===== IndexDailyFetchService daily sync start =====");
        String today = LocalDate.now().format(DATE_FMT);

        List<String> codes = new ArrayList<>(IndexConstants.DEFAULT_INDEX_CODES);
        List<String> swL1 = listSwL1IndexCodes();
        codes.addAll(swL1);

        log.info("IndexDailyFetchService syncing {} indices for {}", codes.size(), today);
        int total = 0;
        for (String code : codes) {
            try {
                total += fetchAndSaveIndexDaily(code, today, today);
            } catch (Exception e) {
                log.error("IndexDailyFetchService 同步失败 {}: {}", code, e.getMessage(), e);
            }
        }
        log.info("===== IndexDailyFetchService daily sync done: {} records =====", total);
    }

    /**
     * 从 sw_industry 表读取 level=1 的所有申万一级行业指数代码。
     * 表为空时返回空列表（不影响大盘指数同步）。
     */
    private List<String> listSwL1IndexCodes() {
        try {
            List<SwIndustryDO> l1 = swIndustryMapper.selectByLevel(1, "SWS2021");
            if (l1 == null || l1.isEmpty()) {
                log.warn("sw_industry level=1 为空，跳过申万行业指数同步");
                return Collections.emptyList();
            }
            return l1.stream()
                    .map(SwIndustryDO::getIndexCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("读取 sw_industry level=1 失败，跳过申万行业指数同步: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量落库：按 (ts_code, trade_date) 先删后插，跨方言通用。
     */
    private int saveBatch(List<IndexDailyDO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<IndexDailyDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            indexDailyMapper.deleteBatchByKeys(batch);
            indexDailyMapper.insertBatch(batch);
            count += batch.size();
        }
        return count;
    }
}
