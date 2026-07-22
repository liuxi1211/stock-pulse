package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.service.DailyBasicService;
import com.arthur.stock.vo.DailyBasicVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "每日基本面", description = "每日基本面数据查询（估值/换手率/市值）")
@RestController
@RequestMapping("/daily-basic")
@RequiredArgsConstructor
public class DailyBasicController {

    private final DailyBasicService dailyBasicService;

    @Operation(summary = "查询每日基本面数据",
            description = "按股票代码和交易日查询每日基本面数据；tsCode 不传则返回空列表，tradeDate 不传则取最新交易日")
    @GetMapping
    public ApiResponse<List<DailyBasicVO>> getDailyBasic(
            @Parameter(description = "股票代码，如 000001.SZ")
            @RequestParam(required = false) String tsCode,
            @Parameter(description = "交易日期，格式 yyyyMMdd，默认最新交易日")
            @RequestParam(required = false) String tradeDate) {
        if (tsCode == null || tsCode.isBlank()) {
            return ApiResponse.success(Collections.emptyList());
        }
        DailyBasicDO data = dailyBasicService.getByCodeAndDate(tsCode, tradeDate);
        if (data == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        return ApiResponse.success(List.of(toVO(data)));
    }

    @Operation(summary = "批量查询每日基本面数据",
            description = "按 tsCode 列表和交易日批量查询每日基本面数据；tradeDate 不传则取最新交易日")
    @GetMapping("/batch")
    public ApiResponse<List<DailyBasicVO>> getDailyBasicBatch(
            @Parameter(description = "股票代码列表，逗号分隔（如 000001.SZ,600000.SH）", required = true)
            @RequestParam String tsCodes,
            @Parameter(description = "交易日期，格式 yyyyMMdd，默认最新交易日")
            @RequestParam(required = false) String tradeDate) {
        if (tsCodes == null || tsCodes.isBlank()) {
            return ApiResponse.success(Collections.emptyList());
        }
        List<String> codeList = java.util.Arrays.stream(tsCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (codeList.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }
        List<DailyBasicDO> dataList = dailyBasicService.listByCodesAndDate(codeList, tradeDate);
        return ApiResponse.success(dataList.stream()
                .map(this::toVO)
                .collect(Collectors.toList()));
    }

    private DailyBasicVO toVO(DailyBasicDO d) {
        return DailyBasicVO.builder()
                .tsCode(d.getTsCode())
                .tradeDate(d.getTradeDate())
                .close(d.getClose())
                .totalMv(d.getTotalMv())
                .peTtm(d.getPeTtm())
                .pb(d.getPb())
                .turnoverRate(d.getTurnoverRate())
                .volumeRatio(d.getVolumeRatio())
                .build();
    }
}
