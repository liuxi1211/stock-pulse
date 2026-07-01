package com.arthur.stock.controller;

import com.arthur.stock.annotation.RequireAdmin;
import com.arthur.stock.client.PythonComputeClient;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.FactorPreviewRequestDTO;
import com.arthur.stock.dto.FactorReferenceDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.FactorDefinitionService;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.util.FactorKeyUtil;
import com.arthur.stock.vo.factor.FactorComputeParamVO;
import com.arthur.stock.vo.factor.FactorComputeResultVO;
import com.arthur.stock.vo.factor.FactorDefVO;
import com.arthur.stock.vo.factor.FactorPreviewVO;
import com.arthur.stock.vo.factor.FactorRegistryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/factors")
@RequiredArgsConstructor
public class FactorController {

    private static final int SIZE_LIMIT = 500;

    private final FactorDefinitionService factorDefinitionService;
    private final DailyQuoteService dailyQuoteService;
    private final StockBasicService stockBasicService;
    private final PythonComputeClient pythonComputeClient;

    @GetMapping("/registry")
    public ApiResponse<FactorRegistryVO> registry() {
        return ApiResponse.success(factorDefinitionService.getRegistry());
    }

    @RequireAdmin
    @PostMapping("/registry/refresh")
    public ApiResponse<FactorRegistryVO> refreshRegistry() {
        return ApiResponse.success(factorDefinitionService.reload());
    }

    @PostMapping("/preview")
    public ApiResponse<FactorPreviewVO> preview(@Valid @RequestBody FactorPreviewRequestDTO body) {
        if (body.getTsCode() == null || body.getTsCode().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "tsCode 不能为空");
        }
        List<DailyQuoteDO> quotes = dailyQuoteService.queryLocalByTsCode(body.getTsCode());
        if (quotes == null || quotes.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无该股票的日线数据: " + body.getTsCode());
        }

        factorDefinitionService.validateRefs(body.getFactors());

        List<DailyQuoteDO> sorted = new ArrayList<>(quotes);
        sorted.sort(Comparator.comparing(DailyQuoteDO::getTradeDate));

        int size = body.getSize();
        if (size <= 0) size = 120;
        if (size > SIZE_LIMIT) size = SIZE_LIMIT;
        if (sorted.size() > size) {
            sorted = sorted.subList(sorted.size() - size, sorted.size());
        }

        List<Map<String, Object>> ohlcv = new ArrayList<>(sorted.size());
        List<Double> opens = new ArrayList<>(sorted.size());
        List<Double> highs = new ArrayList<>(sorted.size());
        List<Double> lows = new ArrayList<>(sorted.size());
        List<Double> closes = new ArrayList<>(sorted.size());
        List<Double> volumes = new ArrayList<>(sorted.size());
        List<String> dates = new ArrayList<>(sorted.size());

        for (DailyQuoteDO q : sorted) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", q.getTradeDate());
            bar.put("open", toDouble(q.getOpen()));
            bar.put("high", toDouble(q.getHigh()));
            bar.put("low", toDouble(q.getLow()));
            bar.put("close", toDouble(q.getClose()));
            bar.put("volume", toDouble(q.getVol()));
            ohlcv.add(bar);

            dates.add(q.getTradeDate());
            opens.add(toDouble(q.getOpen()));
            highs.add(toDouble(q.getHigh()));
            lows.add(toDouble(q.getLow()));
            closes.add(toDouble(q.getClose()));
            volumes.add(toDouble(q.getVol()));
        }

        List<FactorComputeParamVO> computeFactors = new ArrayList<>();
        for (FactorReferenceDTO r : body.getFactors()) {
            Map<String, Object> params = r.getParams() == null ? Collections.emptyMap() : r.getParams();
            FactorDefVO def = factorDefinitionService.get(r.getFactor());
            String requestKey = FactorKeyUtil.buildRequestKey(r.getFactor(), params);
            FactorComputeParamVO vo = FactorComputeParamVO.builder()
                    .factorKey(r.getFactor())
                    .params(params)
                    .requestKey(requestKey)
                    .outputLabels(def.getOutputLabels())
                    .build();
            computeFactors.add(vo);
        }

        FactorComputeResultVO result = pythonComputeClient.compute(ohlcv, computeFactors, body.getTsCode());

        Map<String, List<Double>> ohlcvOut = new LinkedHashMap<>();
        ohlcvOut.put("open", opens);
        ohlcvOut.put("high", highs);
        ohlcvOut.put("low", lows);
        ohlcvOut.put("close", closes);
        ohlcvOut.put("volume", volumes);

        String stockName = lookupStockName(body.getTsCode());

        FactorPreviewVO vo = FactorPreviewVO.builder()
                .tsCode(body.getTsCode())
                .stockName(stockName)
                .computeMs(result.getComputeMs())
                .dates(result.getDates() != null ? result.getDates() : dates)
                .results(result.getResults() != null ? result.getResults() : Collections.emptyMap())
                .ohlcv(ohlcvOut)
                .build();

        return ApiResponse.success(vo);
    }

    private String lookupStockName(String tsCode) {
        try {
            List<StockBasicDTO> list = stockBasicService.queryLocal(tsCode, null, null, "L");
            if (list != null && !list.isEmpty()) {
                String name = list.get(0).getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ex) {
            log.warn("查询股票中文名失败: {}", tsCode, ex);
        }
        return tsCode;
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
