package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.IndexDailyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 指数日线行情查询接口
 */
@Tag(name = "指数日线", description = "大盘指数日线行情查询")
@RestController
@RequestMapping("/api/index-daily")
@RequiredArgsConstructor
public class IndexDailyController {

    private final IndexDailyService indexDailyService;

    @Operation(summary = "获取最新交易日指数行情", description = "按指数代码列表（逗号分隔）获取最新交易日的指数日线数据")
    @GetMapping("/latest")
    public ApiResponse<List<IndexDailyDO>> getLatest(
            @Parameter(description = "指数代码列表，逗号分隔，如 000001.SH,399001.SZ", required = true)
            @RequestParam String codes) {
        if (codes == null || codes.isBlank()) {
            return ApiResponse.success(Collections.emptyList());
        }
        List<String> codeList = Arrays.stream(codes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (codeList.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }
        return ApiResponse.success(indexDailyService.getLatestByCodes(codeList));
    }
}
