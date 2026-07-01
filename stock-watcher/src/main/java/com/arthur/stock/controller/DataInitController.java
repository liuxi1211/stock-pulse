package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.DataInitProgress;
import com.arthur.stock.service.DataInitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据初始化接口
 */
@RestController
@RequestMapping("/api/tushare/data-init")
@RequiredArgsConstructor
public class DataInitController {

    private final DataInitService dataInitService;

    /**
     * 触发数据初始化（异步执行）。
     * 通过 steps 参数指定要执行的步骤，不传则执行全部。
     * 每步执行前会先清除该步骤对应的表数据。
     *
     * @param steps 步骤列表，可选值：stock_basic, trade_cal, daily, adj_factor, dividend
     */
    @PostMapping
    public ApiResponse<DataInitProgress> initialize(@RequestParam(required = false) List<String> steps) {
        return ApiResponse.success("数据初始化已触发", dataInitService.initialize(steps));
    }

    /**
     * 查询当前初始化进度
     */
    @GetMapping("/status")
    public ApiResponse<DataInitProgress> status() {
        return ApiResponse.success(dataInitService.getStatus());
    }
}
