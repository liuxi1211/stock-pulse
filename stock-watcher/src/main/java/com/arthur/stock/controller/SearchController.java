package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.service.SearchService;
import com.arthur.stock.vo.StockVO;
import com.arthur.stock.vo.SuggestItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 股票搜索控制器
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ApiResponse<PageResult<StockVO>> searchStocks(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(searchService.searchStocks(keyword, page, size));
    }

    @GetMapping("/suggest")
    public ApiResponse<List<SuggestItemVO>> suggestStocks(@RequestParam String keyword) {
        return ApiResponse.success(searchService.suggestStocks(keyword));
    }
}