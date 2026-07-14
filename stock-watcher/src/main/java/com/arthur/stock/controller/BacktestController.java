package com.arthur.stock.controller;

import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.backtest.BacktestCompareVO;
import com.arthur.stock.dto.backtest.BacktestReportVO;
import com.arthur.stock.dto.backtest.BacktestRunRequestDTO;
import com.arthur.stock.dto.backtest.BacktestTaskVO;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.BacktestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回测中心 REST Controller（spec 007 T3，11 个接口）。
 * <p>
 * 统一返回 {@link ApiResponse}；业务异常由 {@code GlobalExceptionHandler} 转 {@code code+message}。
 * <p>
 * <b>路径约定</b>：根 {@code /api/backtest}。
 * <ul>
 *   <li>{@code /tasks/**} —— 按 taskId（UUID）寻址（run / 分页 / 单查 / 取消 / 重跑）。</li>
 *   <li>{@code /{backtestId:\d+}} —— 按主键数字 id 寻址（单查 / 报告 / 删除）。</li>
 *   <li>{@code /compare?ids=1,2,3} —— 多任务对比（ids 为逗号分隔的数字 id）。</li>
 *   <li>{@code /benchmarks} —— 基准白名单。</li>
 *   <li>{@code /constants-proxy} —— 代理 engine /constants。</li>
 * </ul>
 * 数字正则约束避免 {@code {backtestId}} 与字面量子路径（tasks/compare/benchmarks/constants-proxy）冲突。
 * <p>
 * <b>认证</b>：由 {@code AuthInterceptor} 统一拦截，本路径不在排除列表，所有接口默认需登录。
 * <b>currentUser</b>：在调用线程内读取 {@link UserContext}（异步线程不可读 ThreadLocal，故显式传给 service）。
 */
@Tag(name = "回测中心", description = "策略回测任务编排、报告查询、多任务对比")
@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Validated
public class BacktestController {

    private final BacktestService backtestService;

    // ==================== 任务提交 / 查询（按 taskId） ====================

    @Operation(summary = "提交回测任务", description = "同步落 PENDING 后异步执行；第一波仅 SINGLE")
    @PostMapping("/run")
    public ApiResponse<BacktestTaskVO> run(@RequestBody BacktestRunRequestDTO req) {
        return ApiResponse.success("已提交", backtestService.run(req, currentUsername()));
    }

    @Operation(summary = "分页查询任务列表", description = "支持 strategyId/status/startDate/endDate 筛选")
    @GetMapping("/tasks")
    public ApiResponse<PageResult<BacktestTaskVO>> listTasks(
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @Parameter(description = "每页条数") @RequestParam(value = "size", defaultValue = "10") @Min(1) int size,
            @Parameter(description = "策略ID") @RequestParam(value = "strategyId", required = false) String strategyId,
            @Parameter(description = "状态") @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "起始日期（含），格式同 createdAt") @RequestParam(value = "startDate", required = false) String startDate,
            @Parameter(description = "结束日期（含）") @RequestParam(value = "endDate", required = false) String endDate) {
        return ApiResponse.success(backtestService.listTasks(page, size, strategyId, status, startDate, endDate));
    }

    @Operation(summary = "按 taskId 查询单个任务")
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<BacktestTaskVO> getTask(@PathVariable String taskId) {
        return ApiResponse.success(backtestService.getTask(taskId));
    }

    @Operation(summary = "取消任务", description = "仅 PENDING 可取消；RUNNING 不可取消（无法终止 engine 进程）")
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<BacktestTaskVO> cancelTask(@PathVariable String taskId) {
        return ApiResponse.success("已取消", backtestService.cancelTask(taskId));
    }

    @Operation(summary = "重跑任务", description = "复用原 overrideConfig + benchmark 生成新任务，不删原任务")
    @PostMapping("/tasks/{taskId}/rerun")
    public ApiResponse<BacktestTaskVO> rerunTask(@PathVariable String taskId) {
        return ApiResponse.success("已提交重跑", backtestService.rerunTask(taskId, currentUsername()));
    }

    // ==================== 按主键 id（数字） ====================

    @Operation(summary = "按主键 id 查询任务", description = "与 /tasks/{taskId}（UUID）不同，此处用数字主键")
    @GetMapping("/{backtestId:\\d+}")
    public ApiResponse<BacktestTaskVO> getById(@PathVariable Long backtestId) {
        return ApiResponse.success(backtestService.getTaskById(backtestId));
    }

    @Operation(summary = "获取回测报告", description = "联查 quant_backtest_report，返回 metrics/曲线/明细")
    @GetMapping("/{backtestId:\\d+}/report")
    public ApiResponse<BacktestReportVO> getReport(@PathVariable Long backtestId) {
        return ApiResponse.success(backtestService.getReport(backtestId));
    }

    @Operation(summary = "删除回测任务", description = "物理删除 quant_backtest + quant_backtest_report")
    @DeleteMapping("/{backtestId:\\d+}")
    public ApiResponse<Void> deleteBacktest(@PathVariable Long backtestId) {
        backtestService.deleteBacktest(backtestId);
        return ApiResponse.success("已删除", null);
    }

    // ==================== 对比 / 基准 / 常量代理 ====================

    @Operation(summary = "多任务对比", description = "ids 为逗号分隔的数字 id，至少 2 个")
    @GetMapping("/compare")
    public ApiResponse<BacktestCompareVO> compare(@Parameter(description = "逗号分隔的回测 id", required = true)
                                                  @RequestParam("ids") String ids) {
        List<Long> idList = parseIdList(ids);
        return ApiResponse.success(backtestService.compare(idList));
    }

    @Operation(summary = "基准候选白名单")
    @GetMapping("/benchmarks")
    public ApiResponse<List<Map<String, String>>> benchmarks() {
        return ApiResponse.success(backtestService.listBenchmarks());
    }

    @Operation(summary = "代理 engine /constants", description = "前端常量下拉数据源；engine 不可达时返回 watcher 默认值")
    @GetMapping("/constants-proxy")
    public ApiResponse<Object> constantsProxy() {
        return ApiResponse.success(backtestService.getConstants());
    }

    // ==================== 内部 ====================

    /**
     * 把逗号分隔的 id 字符串解析为 Long 列表；非法字符跳过。
     */
    private static List<Long> parseIdList(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 在调用线程读取当前用户名（异步线程不可读 ThreadLocal，故显式取出后传给 service）。
     */
    private static String currentUsername() {
        UserDO u = UserContext.get();
        return u == null ? null : u.getUsername();
    }
}
