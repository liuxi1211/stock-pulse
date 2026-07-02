package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.factor.FactorBatchComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorCreateRequestDTO;
import com.arthur.stock.dto.factor.FactorUpdateRequestDTO;
import com.arthur.stock.service.FactorService;
import com.arthur.stock.vo.FactorCategoryVO;
import com.arthur.stock.vo.FactorVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;

@Tag(name = "因子库管理", description = "因子元数据的增删改查，以及因子计算接口")
@RestController
@RequestMapping("/factors")
@RequiredArgsConstructor
@Validated
public class FactorController {

    private final FactorService factorService;

    @Operation(summary = "获取因子分类列表", description = "获取所有因子分类及其包含的因子数量")
    @GetMapping("/categories")
    public ApiResponse<List<FactorCategoryVO>> categories() {
        return ApiResponse.success(factorService.listCategories());
    }

    @Operation(summary = "查询因子列表", description = "支持按分类和来源筛选因子")
    @GetMapping
    public ApiResponse<List<FactorVO>> list(
            @Parameter(description = "因子分类，如 OVERLAP、MOMENTUM 等")
            @Size(max = 50, message = "category 长度不能超过 50")
            @RequestParam(required = false) String category,
            @Parameter(description = "因子来源，如 akquant、tushare 等")
            @Size(max = 20, message = "source 长度不能超过 20")
            @RequestParam(required = false) String source) {
        return ApiResponse.success(factorService.listFactors(category, source));
    }

    @Operation(summary = "获取单个因子详情", description = "根据 factorKey 获取因子的完整配置信息")
    @GetMapping("/{factorKey}")
    public ApiResponse<FactorVO> get(
            @Parameter(description = "因子唯一标识", required = true)
            @NotBlank(message = "factorKey 不能为空")
            @Size(max = 100, message = "factorKey 长度不能超过 100")
            @PathVariable String factorKey) {
        return ApiResponse.success(factorService.getFactor(factorKey));
    }

    @Operation(summary = "新增因子", description = "创建一个新的因子配置")
    @PostMapping
    public ApiResponse<FactorVO> create(@Valid @RequestBody FactorCreateRequestDTO request) {
        return ApiResponse.success("新增成功", factorService.createFactor(request));
    }

    @Operation(summary = "更新因子", description = "根据 factorKey 更新因子配置信息")
    @PutMapping("/{factorKey}")
    public ApiResponse<FactorVO> update(
            @Parameter(description = "因子唯一标识", required = true)
            @NotBlank(message = "factorKey 不能为空")
            @Size(max = 100, message = "factorKey 长度不能超过 100")
            @PathVariable String factorKey,
            @Valid @RequestBody FactorUpdateRequestDTO request) {
        return ApiResponse.success("修改成功", factorService.updateFactor(factorKey, request));
    }

    @Operation(summary = "删除因子", description = "根据 factorKey 删除指定因子")
    @DeleteMapping("/{factorKey}")
    public ApiResponse<Void> delete(
            @Parameter(description = "因子唯一标识", required = true)
            @NotBlank(message = "factorKey 不能为空")
            @Size(max = 100, message = "factorKey 长度不能超过 100")
            @PathVariable String factorKey) {
        factorService.deleteFactor(factorKey);
        return ApiResponse.success("删除成功", null);
    }

    @Operation(summary = "单标的多因子计算", description = "计算单只股票的多个因子值，返回 {factorKey: [值...]}，预热段为 null")
    @PostMapping("/compute")
    public ApiResponse<Map<String, Object>> compute(@Valid @RequestBody FactorComputeRequestDTO request) {
        return ApiResponse.success(factorService.compute(request));
    }

    @Operation(summary = "多标的批量因子计算", description = "批量计算多只股票的因子值，返回 {symbol: {factorKey: [值...]}}")
    @PostMapping("/batch-compute")
    public ApiResponse<Map<String, Map<String, Object>>> batchCompute(@Valid @RequestBody FactorBatchComputeRequestDTO request) {
        return ApiResponse.success(factorService.batchCompute(request));
    }
}
