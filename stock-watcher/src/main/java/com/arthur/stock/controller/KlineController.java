package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.KlineService;
import com.arthur.stock.vo.KlineDataVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * K线控制器
 */
@RestController
@RequestMapping("/api/kline")
@RequiredArgsConstructor
public class KlineController {

    private final KlineService klineService;

    @GetMapping("/{stockCode}")
    public ApiResponse<List<KlineDataVO>> getKlineData(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "daily") String period) {
        return ApiResponse.success(klineService.getKlineData(stockCode, period));
    }
}