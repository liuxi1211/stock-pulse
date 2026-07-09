package com.arthur.stock.controller;

import com.arthur.stock.config.StrategyTemplateLoader;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.strategy.StrategyConfigUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyCreateRequest;
import com.arthur.stock.dto.strategy.StrategyDTO;
import com.arthur.stock.dto.strategy.StrategyDiffDTO;
import com.arthur.stock.dto.strategy.StrategyPageRequest;
import com.arthur.stock.dto.strategy.StrategyRollbackRequest;
import com.arthur.stock.dto.strategy.StrategyStatusUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyTemplateDTO;
import com.arthur.stock.dto.strategy.StrategyUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyVersionDTO;
import com.arthur.stock.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 策略管理 REST Controller（spec 004 Task 7，FR-1 ~ FR-13）。
 * <p>
 * 统一返回 {@link ApiResponse}。所有「写」操作（create/update/delete/rollback）由
 * {@link StrategyService} 内部调用 {@code StrategyEngineClient.validate}（事务外）做配置校验，
 * 校验失败由 {@code GlobalExceptionHandler} 转 400 + errors。
 * <p>
 * <b>路径约定</b>：根 {@code /api/strategies}；动态段 {@code {id}} 为 strategyId（TEXT 业务 ID）；
 * {@code {versionNo}} 为版本号（INTEGER）。模板接口的 {@code {templateId}} 是模板文件名（如 dual_ma）。
 * <p>
 * <b>认证</b>：由 {@code AuthInterceptor}（拦截 {@code /**}，排除登录/静态资源）统一覆盖，
 * {@code /api/strategies/**} 不在排除列表内，所有接口默认需登录。
 */
@Tag(name = "策略管理", description = "策略 CRUD、版本管理、配置校验与模板加载")
@Slf4j
@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
@Validated
public class QuantStrategyController {

    private final StrategyService strategyService;
    private final StrategyTemplateLoader strategyTemplateLoader;

    // ==================== 策略 CRUD ====================

    @Operation(summary = "分页查询策略列表", description = "支持 keyword/category/status/scope/tag 多维过滤；status 不传默认排除 ARCHIVED")
    @GetMapping
    public ApiResponse<PageResult<StrategyDTO>> listStrategies(
            @Parameter(description = "关键字（name / description 模糊）")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED）")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "适用范围（single/portfolio/mixed）")
            @RequestParam(value = "scope", required = false) String scope,
            @Parameter(description = "标签（逗号分隔 tags 中精确包含）")
            @RequestParam(value = "tag", required = false) String tag,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @Parameter(description = "每页条数")
            @RequestParam(value = "size", defaultValue = "10") @Min(1) int size) {
        StrategyPageRequest req = new StrategyPageRequest();
        req.setPage(page);
        req.setSize(size);
        req.setKeyword(keyword);
        req.setCategory(category);
        req.setStatus(status);
        req.setScope(scope);
        req.setTag(tag);
        return ApiResponse.success(strategyService.getStrategiesPage(req));
    }

    @Operation(summary = "策略统计聚合", description = "按状态分组计数（total/verified/active/draft/archived），用于列表页统计条")
    @GetMapping("/stats")
    public ApiResponse<com.arthur.stock.dto.strategy.StrategyStatsDTO> getStats() {
        return ApiResponse.success(strategyService.getStats());
    }

    @Operation(summary = "新建策略", description = "configJson 非空会先 engine 校验，通过后 status=VERIFIED；为空则 status=DRAFT + 默认配置")
    @PostMapping
    public ApiResponse<StrategyDTO> createStrategy(@Valid @RequestBody StrategyCreateRequest req) {
        return ApiResponse.success("新建成功", strategyService.createStrategy(req));
    }

    @Operation(summary = "获取策略详情", description = "含当前版本的 configJson")
    @GetMapping("/{id:[0-9a-fA-F]{32}}")
    public ApiResponse<StrategyDTO> getStrategy(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id) {
        return ApiResponse.success(strategyService.getStrategyDetail(id));
    }

    @Operation(summary = "更新策略基本信息", description = "仅更新 name/description/category/tags，不改 config（改 config 走 PUT /{id}/config）")
    @PutMapping("/{id:[0-9a-fA-F]{32}}")
    public ApiResponse<Void> updateStrategy(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @RequestBody StrategyUpdateRequest req) {
        strategyService.updateStrategy(id, req);
        return ApiResponse.success("修改成功", null);
    }

    @Operation(summary = "更新策略配置（生成新版本）",
            description = "含长度/JSON/乐观锁校验、engine 校验、状态机自动转换（DRAFT→VERIFIED）")
    @PutMapping("/{id:[0-9a-fA-F]{32}}/config")
    public ApiResponse<StrategyDTO> updateStrategyConfig(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @Valid @RequestBody StrategyConfigUpdateRequest req) {
        return ApiResponse.success("配置已更新", strategyService.updateStrategyConfig(id, req));
    }

    @Operation(summary = "变更策略状态", description = "校验 StrategyStatusEnum.canTransitionTo；用于 ACTIVE 上线 / ARCHIVED 归档等")
    @PutMapping("/{id:[0-9a-fA-F]{32}}/status")
    public ApiResponse<Void> updateStatus(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @RequestBody StrategyStatusUpdateRequest req) {
        strategyService.updateStatus(id, req);
        return ApiResponse.success("状态已更新", null);
    }

    @Operation(summary = "软删除策略", description = "status 置为 ARCHIVED，不物理删除")
    @DeleteMapping("/{id:[0-9a-fA-F]{32}}")
    public ApiResponse<Void> deleteStrategy(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id) {
        strategyService.deleteStrategy(id);
        return ApiResponse.success("删除成功", null);
    }

    // ==================== 版本管理 ====================

    @Operation(summary = "版本列表", description = "按 version_no 倒序，不含 configJson")
    @GetMapping("/{id:[0-9a-fA-F]{32}}/versions")
    public ApiResponse<List<StrategyVersionDTO>> listVersions(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id) {
        return ApiResponse.success(strategyService.listVersions(id));
    }

    @Operation(summary = "获取指定版本详情", description = "含 configJson")
    @GetMapping("/{id:[0-9a-fA-F]{32}}/versions/{versionNo}")
    public ApiResponse<StrategyVersionDTO> getVersion(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @Parameter(description = "版本号", required = true)
            @PathVariable Integer versionNo) {
        return ApiResponse.success(strategyService.getVersion(id, versionNo));
    }

    @Operation(summary = "版本对比", description = "对 from/to 版本的 configJson 做 JSON Diff，返回字段级差异列表")
    @GetMapping("/{id:[0-9a-fA-F]{32}}/versions/diff")
    public ApiResponse<List<StrategyDiffDTO>> diffVersions(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @Parameter(description = "起始版本号", required = true)
            @RequestParam Integer from,
            @Parameter(description = "目标版本号", required = true)
            @RequestParam Integer to) {
        return ApiResponse.success(strategyService.diffVersions(id, from, to));
    }

    @Operation(summary = "版本回测指标对比", description = "对比 from/to 两个版本的核心回测指标（收益/夏普/回撤/胜率/交易数）。回测数据未打通时返回空 metrics")
    @GetMapping("/{id:[0-9a-fA-F]{32}}/versions/compare")
    public ApiResponse<com.arthur.stock.dto.strategy.StrategyVersionCompareDTO> compareVersions(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @Parameter(description = "起始版本号", required = true)
            @RequestParam Integer from,
            @Parameter(description = "目标版本号", required = true)
            @RequestParam Integer to) {
        return ApiResponse.success(strategyService.compareVersions(id, from, to));
    }

    @Operation(summary = "回滚到指定版本",
            description = "以目标版本 config 写新版本，重新走 engine 校验、乐观锁与状态机；不删除历史版本")
    @PostMapping("/{id:[0-9a-fA-F]{32}}/versions/rollback")
    public ApiResponse<StrategyVersionDTO> rollbackVersion(
            @Parameter(description = "策略ID（业务ID，32位十六进制）", required = true)
            @PathVariable String id,
            @RequestBody StrategyRollbackRequest req) {
        return ApiResponse.success("回滚成功", strategyService.rollbackVersion(id, req));
    }

    // ==================== 模板（FR-1） ====================

    @Operation(summary = "策略模板列表", description = "启动时从 classpath:strategies/templates/*.json 加载，仅作展示，不落库")
    @GetMapping("/templates")
    public ApiResponse<List<StrategyTemplateDTO>> listTemplates() {
        return ApiResponse.success(strategyTemplateLoader.listTemplates());
    }

    @Operation(summary = "策略模板详情", description = "templateId 为模板文件名（如 dual_ma），不存在返回 404")
    @GetMapping("/templates/{templateId}")
    public ApiResponse<StrategyTemplateDTO> getTemplate(
            @Parameter(description = "模板ID（文件名，不含扩展名）", required = true)
            @PathVariable String templateId) {
        return ApiResponse.success(strategyTemplateLoader.getTemplate(templateId));
    }
}
