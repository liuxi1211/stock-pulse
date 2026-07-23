package com.arthur.stock.controller;

import com.arthur.stock.annotation.RequireAdmin;
import com.arthur.stock.cache.DataSourceHealthCache;
import com.arthur.stock.cache.TaskProgressCache;
import com.arthur.stock.client.TushareClient;
import com.arthur.stock.config.TushareConfig;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.governance.*;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.model.DataGovernanceMetricDO;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.DataGovernanceService;
import com.arthur.stock.service.DataInitService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Tag(name = "数据管控中心", description = "数据质量监控与数据更新管理")
@Slf4j
@RestController
@RequestMapping("/api/data-governance")
@RequiredArgsConstructor
public class DataGovernanceController {

    private final DataGovernanceService dataGovernanceService;
    private final DataInitService dataInitService;
    private final TaskProgressCache taskProgressCache;
    private final DataPullLogMapper dataPullLogMapper;
    private final DataSourceHealthCache dataSourceHealthCache;
    private final TushareConfig tushareConfig;
    private final TushareClient tushareClient;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ==================== Overview ====================

    @Operation(summary = "数据管控概览", description = "返回总表数、正常表数、异常表数及最后检测时间")
    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview() {
        List<DataGovernanceMetricDO> allStatuses = dataGovernanceService.getAllTableStatuses();
        if (allStatuses == null) {
            allStatuses = Collections.emptyList();
        }
        int totalTables = InitStep.values().length;
        int errorTables = (int) allStatuses.stream()
                .filter(s -> "ERROR".equals(s.getStatus()))
                .count();
        int normalTables = totalTables - errorTables;
        String lastCheckTime = allStatuses.stream()
                .map(DataGovernanceMetricDO::getCheckTime)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElse(null);
        return ApiResponse.success(OverviewVO.builder()
                .totalTables(totalTables)
                .updatedToday(normalTables)
                .errorTables(errorTables)
                .lastCheckTime(lastCheckTime)
                .build());
    }

    // ==================== Tables ====================

    @Operation(summary = "查询全部表状态", description = "支持按分组、状态、关键字过滤")
    @GetMapping("/tables")
    public ApiResponse<List<TableStatusVO>> tables(TableQueryDTO query) {
        List<DataGovernanceMetricDO> allStatuses = dataGovernanceService.getAllTableStatuses();
        if (allStatuses == null) {
            allStatuses = Collections.emptyList();
        }
        List<TableStatusVO> result = allStatuses.stream()
                .map(this::buildTableStatusVO)
                .filter(vo -> matchesFilter(vo, query))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @Operation(summary = "查询单表详情", description = "返回表元信息、检测项、最新数据日期等")
    @GetMapping("/tables/{tableCode}")
    public ApiResponse<TableDetailVO> tableDetail(@PathVariable String tableCode) {
        InitStep step = InitStep.fromCode(tableCode);
        if (step == null) {
            return ApiResponse.error(404, "表代码不存在: " + tableCode);
        }
        DataGovernanceMetricDO metric = dataGovernanceService.getLatestMetric(tableCode);
        List<DataCheckItem> checkItems = metric != null
                ? parseCheckItems(metric.getCheckItems())
                : Collections.emptyList();
        String status = metric != null ? metric.getStatus() : "NORMAL";
        TableDetailVO vo = TableDetailVO.builder()
                .tableCode(tableCode)
                .tableName(step.getLabel())
                .tableGroup(step.getGroup().name())
                .tushareApi(step.getTushareApi())
                .totalRows(metric != null ? metric.getTotalRows() : null)
                .latestDate(metric != null ? metric.getLatestDate() : null)
                .earliestDate(metric != null ? metric.getEarliestDate() : null)
                .updateFrequency(step.getUpdateFrequency())
                .expectedUpdateTime(step.getExpectedUpdateTime())
                .isDaily(step.isDaily())
                .checkItems(checkItems)
                .lastCheckTime(metric != null ? metric.getCheckTime() : null)
                .status(status)
                .build();
        return ApiResponse.success(vo);
    }

    @Operation(summary = "查询单表检测结果", description = "返回最新一次检测的所有检测项明细")
    @GetMapping("/tables/{tableCode}/check-result")
    public ApiResponse<List<DataCheckItem>> checkResult(@PathVariable String tableCode) {
        DataGovernanceMetricDO metric = dataGovernanceService.getLatestMetric(tableCode);
        if (metric == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        return ApiResponse.success(parseCheckItems(metric.getCheckItems()));
    }

    @Operation(summary = "查询单表拉取历史", description = "返回最近30条拉取日志")
    @GetMapping("/tables/{tableCode}/pull-history")
    public ApiResponse<List<PullLogVO>> pullHistory(@PathVariable String tableCode) {
        List<DataPullLogDO> logs = dataPullLogMapper.selectByTableCode(tableCode, 30);
        if (logs == null || logs.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }
        List<PullLogVO> result = logs.stream()
                .map(this::convertPullLogToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @Operation(summary = "查询单表检测历史", description = "返回最近30条检测记录")
    @GetMapping("/tables/{tableCode}/check-history")
    public ApiResponse<List<DataGovernanceMetricDO>> checkHistory(@PathVariable String tableCode) {
        List<DataGovernanceMetricDO> history = dataGovernanceService.getMetricHistory(tableCode, 30);
        return ApiResponse.success(history);
    }

    // ==================== Update ====================

    @Operation(summary = "增量更新单表", description = "从最新数据日期的下一天开始增量拉取，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/tables/{tableCode}/incremental-update")
    public ApiResponse<TaskResponseVO> incrementalUpdate(@PathVariable String tableCode) {
        String operator = getOperator();
        String taskId = dataInitService.incrementalUpdate(tableCode, operator);
        return ApiResponse.success(TaskResponseVO.builder()
                .taskId(taskId)
                .tableCode(tableCode)
                .operationType("MANUAL_INCREMENTAL")
                .status("RUNNING")
                .build());
    }

    @Operation(summary = "全量重建单表", description = "清空表后从头拉取全部历史数据，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/tables/{tableCode}/full-rebuild")
    public ApiResponse<TaskResponseVO> fullRebuild(@PathVariable String tableCode) {
        String operator = getOperator();
        String taskId = dataInitService.fullRebuild(tableCode, operator);
        return ApiResponse.success(TaskResponseVO.builder()
                .taskId(taskId)
                .tableCode(tableCode)
                .operationType("MANUAL_FULL")
                .status("RUNNING")
                .build());
    }

    // ==================== Check ====================

    @Operation(summary = "检测全部表", description = "对全部25张业务表执行数据质量检测，异步执行，返回taskId供轮询进度，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/check/all")
    public ApiResponse<TaskResponseVO> checkAll() {
        String taskId = dataGovernanceService.checkAll();
        return ApiResponse.success(TaskResponseVO.builder()
                .taskId(taskId)
                .tableCode("ALL")
                .operationType("MANUAL_CHECK_ALL")
                .status("RUNNING")
                .build());
    }

    @Operation(summary = "检测单表", description = "对指定表执行数据质量检测，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/check/{tableCode}")
    public ApiResponse<DataCheckResult> checkTable(@PathVariable String tableCode) {
        DataCheckResult result = dataGovernanceService.checkTable(tableCode);
        return ApiResponse.success(result);
    }

    // ==================== Task ====================

    @Operation(summary = "查询任务进度", description = "根据任务ID查询实时进度")
    @GetMapping("/tasks/{taskId}/progress")
    public ApiResponse<TaskProgressVO> taskProgress(@PathVariable String taskId) {
        TaskProgress progress = taskProgressCache.getProgress(taskId);
        if (progress == null) {
            return ApiResponse.error(404, "任务不存在或已过期");
        }
        return ApiResponse.success(TaskProgressVO.builder()
                .taskId(progress.getTaskId())
                .tableCode(progress.getTableCode())
                .progressPct(progress.getProgressPct())
                .currentStep(progress.getCurrentStep())
                .processedItems(progress.getProcessedItems())
                .totalItems(progress.getTotalItems())
                .cancelled(progress.isCancelled())
                .lastUpdated(progress.getLastUpdated())
                .build());
    }

    @Operation(summary = "取消任务", description = "设置取消标志，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId) {
        taskProgressCache.setCancelled(taskId, true);
        return ApiResponse.success(null);
    }

    // ==================== Logs ====================

    @Operation(summary = "分页查询拉取日志", description = "支持按表代码、状态、操作类型、时间范围过滤")
    @GetMapping("/logs")
    public ApiResponse<LogPageVO> logs(LogQueryDTO query) {
        int page = query.getPage() != null ? query.getPage() : 1;
        int size = query.getSize() != null ? query.getSize() : 20;
        int offset = (page - 1) * size;
        String startTime = convertDate(query.getStartDate());
        String endTime = convertDate(query.getEndDate());
        List<DataPullLogDO> logs = dataPullLogMapper.selectPageList(
                query.getTableCode(), query.getStatus(), query.getOperationType(),
                startTime, endTime, offset, size);
        long total = dataPullLogMapper.selectPageCount(
                query.getTableCode(), query.getStatus(), query.getOperationType(),
                startTime, endTime);
        List<PullLogVO> records = logs != null
                ? logs.stream().map(this::convertPullLogToVO).collect(Collectors.toList())
                : Collections.emptyList();
        return ApiResponse.success(LogPageVO.builder()
                .records(records)
                .total(total)
                .page(page)
                .size(size)
                .build());
    }

    @Operation(summary = "查询单条拉取日志", description = "返回日志详情，errorStack仅管理员可见")
    @GetMapping("/logs/{logId}")
    public ApiResponse<PullLogVO> logDetail(@PathVariable Long logId) {
        DataPullLogDO log = dataPullLogMapper.selectById(logId);
        if (log == null) {
            return ApiResponse.error(404, "日志不存在");
        }
        return ApiResponse.success(convertPullLogToVO(log));
    }

    // ==================== Datasource ====================

    @Operation(summary = "查询数据源状态", description = "返回数据源连通性检测结果，不返回token")
    @GetMapping("/datasource")
    public ApiResponse<DatasourceVO> datasource() {
        DatasourceVO vo = dataSourceHealthCache.getLatest();
        if (vo == null) {
            vo = DatasourceVO.builder()
                    .sourceCode("TUSHARE")
                    .sourceName("Tushare Pro")
                    .status("UNKNOWN")
                    .lastTestTime(null)
                    .lastTestOk(false)
                    .responseTimeMs(0)
                    .testInterface("trade_cal（交易日历）")
                    .build();
        }
        return ApiResponse.success(vo);
    }

    @Operation(summary = "测试数据源连通性", description = "调用Tushare trade_cal接口测试连通性，仅管理员可操作")
    @RequireAdmin
    @PostMapping("/datasource/test")
    public ApiResponse<DatasourceVO> testDatasource() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long startMs = System.currentTimeMillis();
        boolean ok = false;
        try {
            tushareClient.tradeCal(TradeCalQueryDTO.builder()
                    .startDate(today)
                    .endDate(today)
                    .build());
            ok = true;
        } catch (Exception e) {
            log.warn("数据源连通性测试失败: {}", e.getMessage());
        }
        long responseTime = System.currentTimeMillis() - startMs;
        DatasourceVO vo = DatasourceVO.builder()
                .sourceCode("TUSHARE")
                .sourceName("Tushare Pro")
                .status(ok ? "ACTIVE" : "INACTIVE")
                .lastTestTime(now)
                .lastTestOk(ok)
                .responseTimeMs(responseTime)
                .testInterface("trade_cal（交易日历）")
                .build();
        dataSourceHealthCache.update(vo);
        return ApiResponse.success(vo);
    }

    // ==================== Helper methods ====================

    private String getOperator() {
        UserDO user = UserContext.get();
        return user != null && user.getUsername() != null ? user.getUsername() : "SYSTEM";
    }

    private TableStatusVO buildTableStatusVO(DataGovernanceMetricDO metric) {
        List<DataCheckItem> checkItems = parseCheckItems(metric.getCheckItems());
        int failedCount = (int) checkItems.stream().filter(item -> !item.isPassed()).count();
        InitStep step = InitStep.fromCode(metric.getTableCode());
        String updateFrequency = step != null ? step.getUpdateFrequency() : null;
        String lastUpdateTime = getLastUpdateTime(metric.getTableCode());
        return TableStatusVO.builder()
                .tableCode(metric.getTableCode())
                .tableName(metric.getTableName())
                .tableGroup(metric.getTableGroup())
                .totalRows(metric.getTotalRows())
                .rowDeltaPct(metric.getRowDeltaPct())
                .latestDate(metric.getLatestDate())
                .status(metric.getStatus())
                .failedCount(failedCount)
                .checkItems(checkItems)
                .lastCheckTime(metric.getCheckTime())
                .lastUpdateTime(lastUpdateTime)
                .updateFrequency(updateFrequency)
                .build();
    }

    private String getLastUpdateTime(String tableCode) {
        List<DataPullLogDO> logs = dataPullLogMapper.selectByTableCode(tableCode, 1);
        if (logs != null && !logs.isEmpty()) {
            return logs.get(0).getEndTime();
        }
        return null;
    }

    private boolean matchesFilter(TableStatusVO vo, TableQueryDTO query) {
        if (query == null) {
            return true;
        }
        if (query.getGroup() != null && !query.getGroup().isEmpty()
                && !query.getGroup().equalsIgnoreCase(vo.getTableGroup())) {
            return false;
        }
        if (query.getStatus() != null && !query.getStatus().isEmpty()
                && !query.getStatus().equalsIgnoreCase(vo.getStatus())) {
            return false;
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            String kw = query.getKeyword().toLowerCase();
            boolean matchCode = vo.getTableCode() != null
                    && vo.getTableCode().toLowerCase().contains(kw);
            boolean matchName = vo.getTableName() != null
                    && vo.getTableName().toLowerCase().contains(kw);
            if (!matchCode && !matchName) {
                return false;
            }
        }
        return true;
    }

    private PullLogVO convertPullLogToVO(DataPullLogDO log) {
        PullLogVO.PullLogVOBuilder builder = PullLogVO.builder()
                .id(log.getId())
                .taskId(log.getTaskId())
                .tableCode(log.getTableCode())
                .tableName(log.getTableName())
                .operationType(log.getOperationType())
                .status(log.getStatus())
                .startTime(log.getStartTime())
                .endTime(log.getEndTime())
                .durationMs(log.getDurationMs())
                .totalCount(log.getTotalCount())
                .successCount(log.getSuccessCount())
                .failCount(log.getFailCount())
                .errorMessage(log.getErrorMessage())
                .operator(log.getOperator());
        if (UserContext.isAdmin()) {
            builder.errorStack(log.getErrorStack());
        }
        return builder.build();
    }

    private List<DataCheckItem> parseCheckItems(String checkItemsJson) {
        if (checkItemsJson == null || checkItemsJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(checkItemsJson, new TypeReference<List<DataCheckItem>>() {});
        } catch (Exception e) {
            log.warn("解析检测项失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String convertDate(String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        if (date.length() == 8) {
            return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
        }
        return date;
    }
}
