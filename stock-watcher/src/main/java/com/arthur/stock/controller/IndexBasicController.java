package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.IndexBasicDTO;
import com.arthur.stock.service.IndexBasicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 指数基本信息查询接口
 */
@Tag(name = "指数基本信息", description = "指数基础信息查询（全部市场）")
@RestController
@RequestMapping("/api/index-basic")
@RequiredArgsConstructor
public class IndexBasicController {

    private final IndexBasicService indexBasicService;

    @Operation(summary = "查询指数基本信息", description = "支持按市场/类别/关键字筛选，返回指数代码、名称、基期等")
    @GetMapping
    public ApiResponse<List<IndexBasicDTO>> list(
            @Parameter(description = "市场（SSE/SZSE/CSI/SW/MSCI/CICC/SWHK/OTH）")
            @RequestParam(required = false) String market,
            @Parameter(description = "指数类别")
            @RequestParam(required = false) String category,
            @Parameter(description = "搜索关键字（代码或名称）")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(indexBasicService.queryLocal(market, category, keyword));
    }
}
