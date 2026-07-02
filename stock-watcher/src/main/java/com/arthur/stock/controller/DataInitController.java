package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.DataInitProgress;
import com.arthur.stock.service.DataInitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "数据初始化", description = "Tushare 数据初始化任务管理")
@RestController
@RequestMapping("/tushare/data-init")
@RequiredArgsConstructor
public class DataInitController {

    private final DataInitService dataInitService;

    @Operation(summary = "触发数据初始化", description = "异步触发数据初始化任务，可指定步骤。每步执行前会先清除该步骤对应的表数据。" +
            "可选步骤：stock_basic, trade_cal, daily, adj_factor, dividend")
    @PostMapping
    public ApiResponse<DataInitProgress> initialize(
            @Parameter(description = "初始化步骤列表，不传则执行全部步骤") @RequestParam(required = false) List<String> steps) {
        return ApiResponse.success("数据初始化已触发", dataInitService.initialize(steps));
    }

    @Operation(summary = "查询初始化进度", description = "查询当前数据初始化任务的执行进度和状态")
    @GetMapping("/status")
    public ApiResponse<DataInitProgress> status() {
        return ApiResponse.success(dataInitService.getStatus());
    }
}
