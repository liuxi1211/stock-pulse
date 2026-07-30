package com.arthur.stock.service;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.constant.SwIndustryConstants;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.IndexBasicMapper;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.model.SwIndustryDO;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 落库策略：按 (ts_code, trade_date) 先删后插，实现幂等 upsert。
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

    /** DB 批量操作分片大小（delete/insert 分批，控制单次 SQL 体积）。 */
    private static final int DB_BATCH_SIZE = 500;

    /** Tushare 分页拉取每页条数（index_daily 单次返回上限，单指数 30 年约 7527 行需多页）。 */
    private static final int BATCH_SIZE = 5000;

    /** 分页拉取最大页数保护，超出即告警（防止异常死循环）。 */
    private static final int MAX_PAGES = 10;

    private final TushareClient tushareClient;
    private final IndexDailyMapper indexDailyMapper;
    private final IndexBasicMapper indexBasicMapper;
    private final SwIndustryMapper swIndustryMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 拉取指定指数在 [startDate, endDate] 区间的日线行情并落库（幂等：同主键先删后插）。
     *
     * @param tsCode    指数代码（如 000001.SH）
     * @param startDate 起始交易日 yyyyMMdd（含）
     * @param endDate   结束交易日 yyyyMMdd（含）
     * @return 落库记录数
     */
    public int fetchAndSaveIndexDaily(String tsCode, String startDate, String endDate) {
        log.info("Fetching index_daily: tsCode={}, {}~{}", tsCode, startDate, endDate);

        // ① HTTP 分页拉取（事务外）：Tushare index_daily 单次返回有上限，单指数 30 年约 7527 行需分页。
        List<IndexDailyDO> rows = new ArrayList<>();
        int offset = 0;
        boolean maybeTruncated = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<IndexDailyDO> pageRows = tushareClient.fetchIndexDaily(tsCode, startDate, endDate, offset, BATCH_SIZE);
            if (pageRows == null || pageRows.isEmpty()) {
                break;
            }
            rows.addAll(pageRows);
            if (pageRows.size() < BATCH_SIZE) {
                break; // 末页，已取完
            }
            offset += BATCH_SIZE;
            maybeTruncated = (page == MAX_PAGES - 1);
        }
        if (maybeTruncated) {
            log.warn("index_daily 达到 MAX_PAGES={} 上限，可能存在截断；tsCode={}, {}~{}",
                    MAX_PAGES, tsCode, startDate, endDate);
        }

        if (rows.isEmpty()) {
            log.info("No index_daily data for tsCode={}, {}~{}", tsCode, startDate, endDate);
            return 0;
        }

        // 过滤掉主键缺失的脏数据
        List<IndexDailyDO> entities = rows.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.getTsCode() != null && e.getTradeDate() != null)
                .collect(Collectors.toList());

        // ② 落库（短事务，HTTP 拉取已在外部完成）
        int saved = transactionTemplate.execute(status -> saveBatch(entities));
        log.info("Saved {} index_daily records for tsCode={}, {}~{}", saved, tsCode, startDate, endDate);
        return saved;
    }

    /**
     * 从 sw_industry 表读取 level=1 的所有申万一级行业指数代码。
     * 表为空时返回空列表（不影响大盘指数同步）。
     */
    private List<String> listSwL1IndexCodes() {
        try {
            List<SwIndustryDO> l1 = swIndustryMapper.selectByLevel(1, SwIndustryConstants.SW_SRC);
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
        for (List<IndexDailyDO> batch : Lists.partition(rows, DB_BATCH_SIZE)) {
            indexDailyMapper.deleteBatchByKeys(batch);
            indexDailyMapper.insertBatch(batch);
            count += batch.size();
        }
        return count;
    }
}
