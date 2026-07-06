package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.screener.ScreenPlanCreateRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanRunRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanUpdateRequestDTO;
import com.arthur.stock.service.ScreenerService;
import com.arthur.stock.vo.ScreenLockDetailVO;
import com.arthur.stock.vo.ScreenLockVO;
import com.arthur.stock.vo.ScreenPlanVO;
import com.arthur.stock.vo.ScreenResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多因子选股中心 Controller（spec 003 阶段 2 Task 9/10/11，FR-9/FR-10）。
 * <p>
 * 路由风格与 {@code FactorController} 一致（无 {@code /api} 前缀，统一 {@code /screener}）。
 */
@Tag(name = "选股中心", description = "多因子选股方案 CRUD、执行编排与结果查询")
@RestController
@RequestMapping("/screener")
@RequiredArgsConstructor
@Validated
public class ScreenerController {

    private final ScreenerService screenerService;

    @Operation(summary = "新建选股方案", description = "创建一个选股方案（含 universe/conditions/ranking/filters 等配��）")
    @PostMapping("/plans")
    public ApiResponse<ScreenPlanVO> createPlan(@Valid @RequestBody ScreenPlanCreateRequestDTO req) {
        return ApiResponse.success("新建成功", screenerService.createPlan(req));
    }

    @Operation(summary = "分页查询选股方案")
    @GetMapping("/plans")
    public ApiResponse<PageResult<ScreenPlanVO>> listPlans(
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @Parameter(description = "每页条数")
            @RequestParam(value = "size", defaultValue = "20") @Min(1) int size) {
        return ApiResponse.success(screenerService.listPlans(page, size));
    }

    @Operation(summary = "获取选股方案详情")
    @GetMapping("/plans/{id}")
    public ApiResponse<ScreenPlanVO> getPlan(
            @Parameter(description = "方案ID", required = true)
            @PathVariable @NotNull @Min(1) Long id) {
        return ApiResponse.success(screenerService.getPlan(id));
    }

    @Operation(summary = "更新选股方案", description = "仅更新非空字段")
    @PostMapping("/plans/{id}/update")
    public ApiResponse<ScreenPlanVO> updatePlan(
            @Parameter(description = "方案ID", required = true)
            @PathVariable @NotNull @Min(1) Long id,
            @Valid @RequestBody ScreenPlanUpdateRequestDTO req) {
        return ApiResponse.success("修改成功", screenerService.updatePlan(id, req));
    }

    @Operation(summary = "删除选股方案")
    @PostMapping("/plans/{id}/delete")
    public ApiResponse<Void> deletePlan(
            @Parameter(description = "方案ID", required = true)
            @PathVariable @NotNull @Min(1) Long id) {
        screenerService.deletePlan(id);
        return ApiResponse.success("删除成功", null);
    }

    @Operation(summary = "执行选股方案",
            description = "核心编排：候选池解析 → candidates 拼装 → 调 engine snapshot → 落库")
    @PostMapping("/plans/{id}/run")
    public ApiResponse<ScreenResultVO> runPlan(
            @Parameter(description = "方案ID", required = true)
            @PathVariable @NotNull @Min(1) Long id,
            @RequestBody(required = false) ScreenPlanRunRequestDTO req) {
        return ApiResponse.success("执行成功", screenerService.runPlan(id, req));
    }

    @Operation(summary = "获取选股结果详情")
    @GetMapping("/results/{id}")
    public ApiResponse<ScreenResultVO> getResult(
            @Parameter(description = "结果ID", required = true)
            @PathVariable @NotNull @Min(1) Long id) {
        return ApiResponse.success(screenerService.getResult(id));
    }

    @Operation(summary = "查询某方案的全部执行结果")
    @GetMapping("/plans/{id}/results")
    public ApiResponse<List<ScreenResultVO>> listResultsByPlan(
            @Parameter(description = "方案ID", required = true)
            @PathVariable @NotNull @Min(1) Long id) {
        return ApiResponse.success(screenerService.listResultsByPlan(id));
    }

    // ==================== 结果锁定与收益追踪（spec 003 阶段 2 Task 11，FR-9） ====================

    @Operation(summary = "锁定选股结果为持仓组合快照",
            description = "把某次选股结果锁定为持仓组合，生成 screen_lock 记录（防重复锁定）")
    @PostMapping("/results/{resultId}/lock")
    public ApiResponse<ScreenLockVO> lockResult(
            @Parameter(description = "选股结果ID", required = true)
            @PathVariable @NotNull @Min(1) Long resultId) {
        return ApiResponse.success("锁定成功", screenerService.lockResult(resultId));
    }

    @Operation(summary = "查询某次锁定的追踪明细",
            description = "返回基础字段 + 解析后的个股列表 + 个股贡献明细（以当前最新交易日为终点）")
    @GetMapping("/locks/{lockId}")
    public ApiResponse<ScreenLockDetailVO> getLockDetail(
            @Parameter(description = "锁定记录ID", required = true)
            @PathVariable @NotNull @Min(1) Long lockId) {
        return ApiResponse.success(screenerService.getLockDetail(lockId));
    }

    @Operation(summary = "列出所有锁定记录", description = "可选按 planId 过滤；返回基础字段，不含明细")
    @GetMapping("/locks")
    public ApiResponse<List<ScreenLockVO>> listLocks(
            @Parameter(description = "方案ID，可选；不传则返回全部")
            @RequestParam(value = "planId", required = false) Long planId) {
        return ApiResponse.success(screenerService.listLocks(planId));
    }
}
