package com.arthur.stock.controller;

import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.BacktestClient;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.BacktestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数寻优 REST Controller（spec 015 FR-O1/O4/O7）。
 * <p>
 * 透传 stock-engine 的 GRID / WALK-FORWARD 寻优能力（engine 内存态任务表）。
 * watcher 侧不持久化寻优结果（持久化由后续 optimization_result 表承接，第二波）。
 * <p>
 * <b>路径约定</b>：根 {@code /api/optimize}。
 * <ul>
 *   <li>{@code POST /grid} —— 提交 GRID 寻优任务（异步，返回 task_id）。</li>
 *   <li>{@code POST /walk-forward} —— 提交 WALK-FORWARD 验证任务。</li>
 *   <li>{@code GET /{taskId}} —— 查询任务状态/结果（前端轮询）。</li>
 *   <li>{@code POST /{taskId}/cancel} —— 取消任务。</li>
 *   <li>{@code GET} —— 列出寻优任务（按 user_id/task_type 过滤）。</li>
 * </ul>
 * <p>
 * <b>合规约束</b>（spec 015 PRD §1.2.3）：响应固定附带免责声明；前端「应用」按钮需用户二次确认。
 * <p>
 * <b>认证</b>：由 {@code AuthInterceptor} 统一拦截，本路径不在排除列表，所有接口默认需登录。
 */
@Tag(name = "参数寻优", description = "GRID 网格寻优 + WALK-FORWARD 滚动样本外验证（spec 015）")
@Slf4j
@RestController
@RequestMapping("/api/optimize")
@RequiredArgsConstructor
public class OptimizeController {

    private static final String DISCLAIMER =
            "本工具为量化研究辅助，不构成任何投资建议；历史回测结果不代表未来收益。";

    private final BacktestClient backtestClient;
    private final BacktestService backtestService;

    // ==================== 任务提交 ====================

    @Operation(summary = "提交 GRID 寻优任务",
            description = "异步执行，立即返回 task_id。请求体：uuid / versionNo / param_grid / "
                    + "sort_by / max_workers / constraint / result_filter / top_n。watcher 装配 config+kline_data 后透传 engine。"
                    + DISCLAIMER)
    @PostMapping("/grid")
    public ApiResponse<Object> submitGrid(@RequestBody JSONObject req) {
        JSONObject engineReq = assembleOptimizeEngineRequest(req);
        Object data = backtestClient.submitOptimize(engineReq);
        return ApiResponse.success("GRID 寻优任务已提交", data);
    }

    @Operation(summary = "提交 WALK-FORWARD 验证任务",
            description = "在 GRID 之上做滚动样本外验证，返回 6 维过拟合指标 + 综合可信度评分。"
                    + DISCLAIMER)
    @PostMapping("/walk-forward")
    public ApiResponse<Object> submitWalkForward(@RequestBody JSONObject req) {
        JSONObject engineReq = assembleOptimizeEngineRequest(req);
        // WF 额外必填：train_period / test_period；可选 metric / window_align
        // 已由 assembleOptimizeEngineRequest 透传（非 optimize 专属字段原样保留）
        Object data = backtestClient.submitWalkForward(engineReq);
        return ApiResponse.success("WALK-FORWARD 任务已提交", data);
    }

    // ==================== 任务查询 / 取消 ====================

    @Operation(summary = "查询寻优任务状态/结果", description = "前端轮询：PENDING→RUNNING→SUCCESS/FAILED/CANCELLED")
    @GetMapping("/{taskId}")
    public ApiResponse<Object> getTask(@PathVariable String taskId) {
        Object data = backtestClient.getOptimizeTask(taskId);
        return ApiResponse.success(data);
    }

    @Operation(summary = "取消寻优任务", description = "仅 PENDING/RUNNING 可取消；60s 内终止")
    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Object> cancelTask(@PathVariable String taskId) {
        Object data = backtestClient.cancelOptimizeTask(taskId);
        return ApiResponse.success("取消请求已提交", data);
    }

    @Operation(summary = "列出寻优任务", description = "按 user_id / task_type(grid/walk_forward) 过滤")
    @GetMapping
    public ApiResponse<Object> listTasks(
            @Parameter(description = "任务类型：grid / walk_forward") @RequestParam(value = "task_type", required = false) String taskType) {
        String userId = currentUsername();
        Object data = backtestClient.listOptimizeTasks(userId, taskType);
        return ApiResponse.success(data);
    }

    // ==================== 内部 ====================

    /**
     * 装配 engine 寻优请求体（spec 015 FR-O1）。
     * <p>
     * 前端只需传 {@code uuid/versionNo + param_grid + 排序/约束/Top-N}，
     * watcher 通过 {@link BacktestService#buildOptimizeContext} 加载 config + kline_data，
     * 合并成 engine {@code /optimize} 请求体：
     * <pre>
     * {
     *   "strategy_config": <configJson>,
     *   "kline_data": <JSONObject>,
     *   "param_grid": {...}, "sort_by": "...", "max_workers": N,
     *   "constraint": {...}, "result_filter": {...}, "top_n": N,
     *   "user_id": "<当前用户>",
     *   // WF 专属（原样透传）
     *   "train_period": N, "test_period": N, "metric": "...", "window_align": "..."
     * }
     * </pre>
     */
    private JSONObject assembleOptimizeEngineRequest(JSONObject req) {
        if (req == null) {
            throw new BusinessException(400, "请求体为空");
        }
        String uuid = req.getString("uuid");
        if (uuid == null || uuid.isBlank()) {
            throw new BusinessException(400, "缺少 uuid");
        }
        Integer versionNo = req.getInteger("versionNo");

        // 1. watcher 侧装配 config + kline_data（复用回测链路）
        JSONObject ctx = backtestService.buildOptimizeContext(uuid, versionNo);

        // 2. 组装 engine 请求体
        JSONObject engineReq = new JSONObject();
        engineReq.put("strategy_config", ctx.get("config"));
        engineReq.put("kline_data", ctx.get("kline_data"));
        // 寻优专属字段（必填）
        engineReq.put("param_grid", req.get("param_grid"));
        engineReq.put("sort_by", req.getOrDefault("sort_by", "sharpe_ratio"));
        engineReq.put("top_n", req.getOrDefault("top_n", 10));
        // 可选字段
        if (req.containsKey("max_workers")) engineReq.put("max_workers", req.get("max_workers"));
        if (req.containsKey("constraint")) engineReq.put("constraint", req.get("constraint"));
        if (req.containsKey("result_filter")) engineReq.put("result_filter", req.get("result_filter"));
        // WF 专属字段原样透传
        if (req.containsKey("train_period")) engineReq.put("train_period", req.get("train_period"));
        if (req.containsKey("test_period")) engineReq.put("test_period", req.get("test_period"));
        if (req.containsKey("metric")) engineReq.put("metric", req.get("metric"));
        if (req.containsKey("window_align")) engineReq.put("window_align", req.get("window_align"));
        // 用户级并发限制（engine 按 user_id 限制 GRID≤2 / WF≤1）
        String username = currentUsername();
        if (username != null) engineReq.put("user_id", username);
        return engineReq;
    }

    /**
     * 在调用线程读取当前用户名（异步线程不可读 ThreadLocal，故显式取出）。
     */
    private static String currentUsername() {
        UserDO u = UserContext.get();
        return u == null ? null : u.getUsername();
    }
}
