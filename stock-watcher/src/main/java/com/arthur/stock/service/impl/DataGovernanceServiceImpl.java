package com.arthur.stock.service.impl;

import com.arthur.stock.cache.TaskProgressCache;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.governance.TableStatus;
import com.arthur.stock.dto.governance.TaskProgress;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DataGovernanceMetricMapper;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.model.DataGovernanceMetricDO;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.DataGovernanceService;
import com.arthur.stock.service.TradeCalService;
import com.arthur.stock.util.SensitiveDataUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 数据治理服务实现。
 * <p>
 * 通过 Spring 自动发现所有 {@link DataCheckable} 实现，按 tableCode 索引。
 * 检测结果写入 data_governance_metric 表，状态查询时叠加实时 UPDATING 覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataGovernanceServiceImpl implements DataGovernanceService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter BATCH_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter CHECK_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final double ROW_DELTA_THRESHOLD = -30.0;

    /** I/O 密集型任务使用虚拟线程 */
    private static final Executor IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final List<DataCheckable> checkables;
    private final DataGovernanceMetricMapper metricMapper;
    private final DataPullLogMapper pullLogMapper;
    private final TradeCalService tradeCalService;
    private final TaskProgressCache taskProgressCache;

    private Map<String, DataCheckable> checkableMap;

    @PostConstruct
    public void init() {
        checkableMap = checkables.stream()
                .collect(Collectors.toMap(DataCheckable::getTableCode, c -> c, (a, b) -> a));
        log.info("DataGovernanceService initialized with {} checkable(s): {}", checkableMap.size(), checkableMap.keySet());
    }

    @Override
    public DataCheckResult checkTable(String tableCode) {
        String batchId = "check_" + LocalDateTime.now().format(BATCH_ID_FMT);
        return doCheckTable(tableCode, batchId, "MANUAL");
    }

    @Override
    public String checkAll() {
        if (!taskProgressCache.tryAcquireCheckLock()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "有检测任务正在执行，请稍后再试");
        }
        String taskId = UUID.randomUUID().toString();
        String batchId = "check_" + LocalDateTime.now().format(BATCH_ID_FMT);
        InitStep[] steps = InitStep.values();
        int totalTables = steps.length;

        TaskProgress initialProgress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode("ALL")
                .progressPct(0)
                .currentStep("准备中")
                .processedItems(0)
                .totalItems(totalTables)
                .cancelled(false)
                .lastUpdated(LocalDateTime.now().format(CHECK_TIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, initialProgress);

        CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < totalTables; i++) {
                    InitStep step = steps[i];
                    if (taskProgressCache.isCancelled(taskId)) {
                        log.info("全表检测被取消，taskId={}, 已完成 {}/{}", taskId, i, totalTables);
                        updateCheckProgress(taskId, i, totalTables, "已取消", step.getCode());
                        break;
                    }
                    updateCheckProgress(taskId, i, totalTables, "检测中: " + step.getLabel(), step.getCode());
                    try {
                        doCheckTable(step.getCode(), batchId, "MANUAL");
                    } catch (Exception e) {
                        log.warn("检测表 {} 失败: {}", step.getCode(), e.getMessage(), e);
                    }
                }
                updateCheckProgress(taskId, totalTables, totalTables, "完成", "ALL");
                log.info("全表检测完成, taskId={}, batchId={}", taskId, batchId);
            } catch (Exception e) {
                log.error("全表检测异常, taskId={}", taskId, e);
                updateCheckProgress(taskId, 0, totalTables, "失败: " + e.getMessage(), "ALL");
            } finally {
                taskProgressCache.releaseCheckLock();
            }
        }, IO_EXECUTOR);

        return taskId;
    }

    @Override
    public String checkAllScheduled() {
        String batchId = "check_" + LocalDateTime.now().format(BATCH_ID_FMT);
        InitStep[] steps = InitStep.values();
        int totalTables = steps.length;
        for (int i = 0; i < totalTables; i++) {
            InitStep step = steps[i];
            try {
                doCheckTable(step.getCode(), batchId, "SCHEDULED");
            } catch (Exception e) {
                log.warn("定时检测表 {} 失败: {}", step.getCode(), e.getMessage(), e);
            }
        }
        log.info("定时全表检测完成, batchId={}, 共 {} 张表", batchId, totalTables);
        return batchId;
    }

    /**
     * 更新检测任务进度。
     */
    private void updateCheckProgress(String taskId, int processed, int total, String currentStep, String tableCode) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        if (existing == null) {
            return;
        }
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(tableCode)
                .progressPct(total > 0 ? processed * 100 / total : 0)
                .currentStep(currentStep)
                .processedItems(processed)
                .totalItems(total)
                .cancelled(existing.isCancelled())
                .lastUpdated(LocalDateTime.now().format(CHECK_TIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    @Override
    public List<DataGovernanceMetricDO> getLatestBatch() {
        List<DataGovernanceMetricDO> metrics = metricMapper.selectLatestBatch();
        return metrics != null ? metrics : Collections.emptyList();
    }

    /**
     * 获取每张表各自的最新检测记录（不依赖 batch 语义）。
     * 以 InitStep 枚举为主表，无检测记录的表返回 NORMAL 状态的占位对象，
     * 确保从未检测过的表也能出现在列表中。
     */
    private List<DataGovernanceMetricDO> getAllLatestMetrics() {
        List<DataGovernanceMetricDO> result = new ArrayList<>();
        for (InitStep step : InitStep.values()) {
            try {
                DataGovernanceMetricDO metric = metricMapper.selectByTableCodeLatest(step.getCode());
                if (metric != null) {
                    result.add(metric);
                } else {
                    result.add(buildEmptyMetric(step));
                }
            } catch (Exception e) {
                log.warn("获取表 {} 最新检测记录失败: {}", step.getCode(), e.getMessage());
                result.add(buildEmptyMetric(step));
            }
        }
        return result;
    }

    /**
     * 构造一张从未检测过的表的占位 metric 对象（NORMAL 状态，无检测数据）。
     */
    private DataGovernanceMetricDO buildEmptyMetric(InitStep step) {
        return DataGovernanceMetricDO.builder()
                .tableCode(step.getCode())
                .tableName(step.getLabel())
                .tableGroup(step.getGroup().name())
                .totalRows(null)
                .rowDeltaPct(null)
                .latestDate(null)
                .earliestDate(null)
                .status(TableStatus.NORMAL.name())
                .checkItems("[]")
                .checkTime(null)
                .checkType(null)
                .build();
    }

    @Override
    public DataGovernanceMetricDO getLatestMetric(String tableCode) {
        return metricMapper.selectByTableCodeLatest(tableCode);
    }

    @Override
    public String getTableStatus(String tableCode) {
        if (taskProgressCache.isLocked() || taskProgressCache.isCheckLocked()) {
            return TableStatus.UPDATING.name();
        }
        DataGovernanceMetricDO metric = metricMapper.selectByTableCodeLatest(tableCode);
        if (metric == null || metric.getStatus() == null) {
            return TableStatus.NORMAL.name();
        }
        return metric.getStatus();
    }

    @Override
    public List<DataGovernanceMetricDO> getAllTableStatuses() {
        List<DataGovernanceMetricDO> metrics = getAllLatestMetrics();
        if (metrics.isEmpty()) {
            return Collections.emptyList();
        }
        if (taskProgressCache.isLocked() || taskProgressCache.isCheckLocked()) {
            for (DataGovernanceMetricDO metric : metrics) {
                metric.setStatus(TableStatus.UPDATING.name());
            }
        }
        return metrics;
    }

    @Override
    public List<DataGovernanceMetricDO> getMetricHistory(String tableCode, int limit) {
        List<DataGovernanceMetricDO> list = metricMapper.selectHistoryByTableCode(tableCode, limit);
        return list != null ? list : Collections.emptyList();
    }

    // ────────────────────────── 内部方法 ──────────────────────────

    /**
     * 执行单表检测并保存结果。
     *
     * @param tableCode 表代码
     * @param batchId   批次ID
     * @param checkType 检测类型（MANUAL / SCHEDULED）
     * @return 检测结果
     */
    private DataCheckResult doCheckTable(String tableCode, String batchId, String checkType) {
        InitStep step = InitStep.fromCode(tableCode);
        String tableName = step != null ? step.getLabel() : tableCode;
        String tableGroup = step != null ? step.getGroup().name() : null;

        List<DataCheckItem> items = new ArrayList<>();
        long totalRows = 0;
        String latestDate = null;
        BigDecimal rowDeltaPct = null;

        DataCheckable checkable = checkableMap.get(tableCode);
        if (checkable == null) {
            items.add(DataCheckItem.builder()
                    .name("checkable")
                    .displayName("校验器")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("未注册数据校验器")
                    .build());
        } else {
            try {
                DataCheckResult checkResult = checkable.checkData();
                if (checkResult.getItems() != null) {
                    items.addAll(checkResult.getItems());
                }
                totalRows = checkResult.getTotalRows();
                latestDate = checkResult.getLatestDate();
                if (checkResult.getTableName() != null) {
                    tableName = checkResult.getTableName();
                }

                // 空表检测
                if (totalRows == 0) {
                    items.add(DataCheckItem.builder()
                            .name("empty")
                            .displayName("空表检测")
                            .passed(false)
                            .level(CheckLevel.ERROR)
                            .message("表为空，0 条记录")
                            .build());
                }

                // 行数变动检测
                rowDeltaPct = detectRowDelta(tableCode, totalRows, items);
            } catch (Exception e) {
                log.warn("校验表 {} 时出错: {}", tableCode, e.getMessage(), e);
                items.add(DataCheckItem.builder()
                        .name("check_error")
                        .displayName("校验执行错误")
                        .passed(false)
                        .level(CheckLevel.ERROR)
                        .message("校验执行异常: " + SensitiveDataUtil.mask(e.getMessage()))
                        .build());
            }
        }

        // 确定状态
        String status = determineBaseStatus(step, items, latestDate);
        String checkTime = LocalDateTime.now().format(CHECK_TIME_FMT);

        // 保存检测结果
        DataGovernanceMetricDO metric = DataGovernanceMetricDO.builder()
                .checkBatchId(batchId)
                .tableCode(tableCode)
                .tableName(tableName)
                .tableGroup(tableGroup)
                .totalRows(totalRows)
                .rowDeltaPct(rowDeltaPct)
                .latestDate(latestDate)
                .earliestDate(null)
                .status(status)
                .checkItems(itemsToJson(items))
                .checkTime(checkTime)
                .checkType(checkType)
                .build();
        metricMapper.insert(metric);

        return DataCheckResult.builder()
                .tableCode(tableCode)
                .tableName(tableName)
                .totalRows(totalRows)
                .latestDate(latestDate)
                .items(items)
                .build();
    }

    /**
     * 行数变动检测：与上次检测对比，若减少超过 30% 且非全量拉取则追加 WARN。
     *
     * @return 变动百分比（正数=增加，负数=减少），无历史数据则 null
     */
    private BigDecimal detectRowDelta(String tableCode, long totalRows, List<DataCheckItem> items) {
        if (totalRows <= 0) {
            return null;
        }
        DataGovernanceMetricDO previous = metricMapper.selectPreviousByTableCode(tableCode);
        if (previous == null || previous.getTotalRows() == null || previous.getTotalRows() <= 0) {
            return null;
        }
        long prevRows = previous.getTotalRows();
        double deltaPct = (totalRows - prevRows) * 100.0 / prevRows;
        BigDecimal rowDeltaPct = BigDecimal.valueOf(deltaPct).setScale(2, RoundingMode.HALF_UP);

        if (deltaPct < ROW_DELTA_THRESHOLD) {
            boolean exempt = isManualFullExempt(tableCode);
            if (!exempt) {
                items.add(DataCheckItem.builder()
                        .name("row_delta")
                        .displayName("行数变动检测")
                        .passed(false)
                        .level(CheckLevel.WARN)
                        .message(String.format("记录数较上次减少 %.2f%%（%d -> %d）", Math.abs(deltaPct), prevRows, totalRows))
                        .build());
            }
        }
        return rowDeltaPct;
    }

    /**
     * 检查最近一次拉取日志是否为 MANUAL_FULL（全量拉取，行数大幅变动属正常）。
     */
    private boolean isManualFullExempt(String tableCode) {
        List<DataPullLogDO> pullLogs = pullLogMapper.selectByTableCode(tableCode, 1);
        if (pullLogs == null || pullLogs.isEmpty()) {
            return false;
        }
        return "MANUAL_FULL".equals(pullLogs.get(0).getOperationType());
    }

    /**
     * 基础状态判定（不含 UPDATING 实时覆盖）：
     * <ul>
     *   <li>存在 ERROR 级未通过项 -> ERROR</li>
     *   <li>日线表且最新日期早于上一交易日 -> DELAYED</li>
     *   <li>其余 -> NORMAL</li>
     * </ul>
     */
    private String determineBaseStatus(InitStep step, List<DataCheckItem> items, String latestDate) {
        boolean hasError = items.stream()
                .anyMatch(i -> !i.isPassed() && i.getLevel() == CheckLevel.ERROR);
        if (hasError) {
            return TableStatus.ERROR.name();
        }
        if (step != null && isDelayed(step, latestDate)) {
            return TableStatus.DELAYED.name();
        }
        return TableStatus.NORMAL.name();
    }

    /**
     * 延迟检测：日线表的最新数据日期是否早于最近一个交易日。
     */
    private boolean isDelayed(InitStep step, String latestDate) {
        if (!step.isDaily() || latestDate == null || latestDate.isEmpty()) {
            return false;
        }
        String today = LocalDate.now().format(DATE_FMT);
        List<TradeCalDTO> tradeDays = tradeCalService.queryLocal("SSE", null, today, "1");
        if (tradeDays == null || tradeDays.isEmpty()) {
            return false;
        }
        String lastTradeDay = tradeDays.stream()
                .map(TradeCalDTO::getCalDate)
                .filter(d -> d.compareTo(today) <= 0)
                .max(String::compareTo)
                .orElse(null);
        if (lastTradeDay == null) {
            return false;
        }
        return latestDate.compareTo(lastTradeDay) < 0;
    }

    /**
     * 将检测项列表序列化为 JSON 字符串。
     */
    private String itemsToJson(List<DataCheckItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("序列化检测项失败: {}", e.getMessage());
            return "[]";
        }
    }
}
