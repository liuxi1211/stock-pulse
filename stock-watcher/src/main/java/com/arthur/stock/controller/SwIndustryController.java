package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.vo.SwIndustryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 申万行业分类查询，供前端行业筛选下拉使用。
 */
@Tag(name = "申万行业", description = "申万行业分类列表查询")
@RestController
@RequestMapping("/api/industry")
@RequiredArgsConstructor
public class SwIndustryController {

    private final SwIndustryService swIndustryService;

    @Operation(summary = "按层级查询行业列表", description = "level=1 返回 28 个申万一级行业，level=2/3 返回对应层级")
    @GetMapping("/list")
    public ApiResponse<List<SwIndustryVO>> list(
            @Parameter(description = "行业层级（1/2/3），默认 1")
            @RequestParam(defaultValue = "1") int level) {
        return ApiResponse.success(swIndustryService.listByLevel(level));
    }
}
