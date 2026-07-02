package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.service.SearchService;
import com.arthur.stock.vo.StockVO;
import com.arthur.stock.vo.SuggestItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "股票搜索", description = "股票搜索和自动补全建议")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "搜索股票", description = "根据关键字分页搜索股票，支持按代码或名称模糊匹配")
    @GetMapping
    public ApiResponse<PageResult<StockVO>> searchStocks(
            @Parameter(description = "搜索关键字（股票代码或名称）") @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(searchService.searchStocks(keyword, page, size));
    }

    @Operation(summary = "搜索建议", description = "根据输入关键字返回股票搜索建议列表，用于自动补全")
    @GetMapping("/suggest")
    public ApiResponse<List<SuggestItemVO>> suggestStocks(
            @Parameter(description = "搜索关键字", required = true) @RequestParam String keyword) {
        return ApiResponse.success(searchService.suggestStocks(keyword));
    }
}