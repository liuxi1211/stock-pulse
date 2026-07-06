package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSON;
import com.arthur.stock.client.FactorClient;
import com.arthur.stock.config.FactorPrecomputeConfig;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.dto.factor.FactorBatchComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorComputeSpecDTO;
import com.arthur.stock.mapper.FactorSnapshotMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.FactorSnapshotDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.FactorSnapshotService;
import com.arthur.stock.service.TradeCalendarService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 因子预计算服务实现。
 * <p>
 * 流程：取最新交易日 + 全市场股票 → 批量取 OHLCV（maxLookback=300）→
 * 对白名单每个 (factorKey, params, outputIndex) 调 engine batch-compute（全市场 × 单 spec）→
 * 取每只股票序列的末值入库 factor_snapshot。
 * <p>
 * 限制：engine batch-compute 用 factorKey 做 key，同 factorKey 不同 params/outputIndex 会冲突，
 * 故每个唯一 spec 单独发一次请求（白名单约 30 个 spec = 30 次 HTTP，每次含全市场股票）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorSnapshotServiceImpl implements FactorSnapshotService {

    /** 预计算所需最大回看窗口（覆盖 MA250） */
    private static final int MAX_LOOKBACK = 300;
    /** 单批入库行数 */
    private static final int BATCH_SIZE = 1000;

    /** 已知多输出因子及其 outputIndex 范围（其余视为单输出 0） */
    private static final Map<String, int[]> MULTI_OUTPUT = Map.of(
            "MACD", new int[]{0, 1, 2},
            "BOLL", new int[]{0, 1, 2},
            "KDJ", new int[]{0, 1, 2}
    );

    private final FactorPrecomputeConfig config;
    private final TradeCalendarService tradeCalendarService;
    private final StockBasicMapper stockBasicMapper;
    private final DailyQuoteService dailyQuoteService;
    private final FactorClient factorClient;
    private final FactorSnapshotMapper factorSnapshotMapper;

    @Override
    public int computeForLatestTradeDate() {
        String tradeDate = tradeCalendarService.getLatestTradeDate();
        if (tradeDate == null || tradeDate.length() != 8) {
            log.warn("因子预计算：最新交易日缺失，跳过");
            return 0;
        }

        // 1. 全市场在市股票
        List<StockBasicDO> stocks = stockBasicMapper.selectList(new LambdaQueryWrapper<StockBasicDO>()
                .eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED));
        if (stocks.isEmpty()) {
            log.warn("因子预计算：无在市股票，跳过");
            return 0;
        }
        List<String> codes = stocks.stream().map(StockBasicDO::getTsCode).collect(Collectors.toList());

        // 2. 批量取 OHLCV（ts_code -> 升序列表）
        Map<String, List<DailyQuoteDO>> ohlcvMap = dailyQuoteService.queryRecentOhlcvByCodes(codes, MAX_LOOKBACK);

        // 3. 展开白名单为 spec 列表（每个唯一 (factorKey, params, outputIndex) 一项）
        List<Spec> specs = expandWhitelist(config.getWhitelist());
        log.info("因子预计算 tradeDate={} stocks={} specs={}", tradeDate, codes.size(), specs.size());

        // 4. 逐 spec 调 engine batch-compute，收集结果行
        List<FactorSnapshotDO> rows = new ArrayList<>();
        String now = LocalDateTime.now().toString();
        for (Spec spec : specs) {
            try {
                Map<String, BigDecimal> lastValues = computeOneSpec(ohlcvMap, spec);
                String paramsJson = canonicalParamsJson(spec.params);
                for (Map.Entry<String, BigDecimal> e : lastValues.entrySet()) {
                    FactorSnapshotDO row = FactorSnapshotDO.builder()
                            .tradeDate(tradeDate)
                            .tsCode(e.getKey())
                            .factorKey(spec.factorKey)
                            .paramsJson(paramsJson)
                            .outputIndex(spec.outputIndex)
                            .factorValue(e.getValue())
                            .updatedAt(now)
                            .build();
                    rows.add(row);
                }
            } catch (Exception ex) {
                log.warn("因子预计算 spec={} 失败: {}", spec, ex.getMessage());
            }
        }

        // 5. 批量 UPSERT 入库（先删后插）
        int saved = upsertAll(rows);
        log.info("因子预计算完成 tradeDate={} 入库 {} 行", tradeDate, saved);
        return saved;
    }

    @Override
    public List<FactorSnapshotDO> queryByDateAndCode(String tradeDate, String tsCode) {
        return factorSnapshotMapper.selectByDateAndCode(tradeDate, tsCode);
    }

    @Override
    public Map<String, BigDecimal> queryByDateAndCodes(String tradeDate, List<String> codes) {
        if (tradeDate == null || codes == null || codes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<FactorSnapshotDO> rows = factorSnapshotMapper.selectByDateAndCodes(tradeDate, codes);
        Map<String, BigDecimal> out = new HashMap<>(rows.size());
        for (FactorSnapshotDO r : rows) {
            String key = r.getTsCode() + "|" + r.getFactorKey() + "|" + r.getParamsJson() + "|" + r.getOutputIndex();
            out.put(key, r.getFactorValue());
        }
        return out;
    }

    // ==================== 内部 ====================

    /** 展开白名单为 spec 列表，多输出因子按 outputIndex 展开。 */
    private List<Spec> expandWhitelist(Map<String, List<Map<String, Object>>> whitelist) {
        List<Spec> out = new ArrayList<>();
        if (whitelist == null) {
            return out;
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : whitelist.entrySet()) {
            String factorKey = e.getKey();
            int[] outputs = MULTI_OUTPUT.getOrDefault(factorKey, new int[]{0});
            for (Map<String, Object> params : e.getValue()) {
                for (int oi : outputs) {
                    out.add(new Spec(factorKey, params, oi));
                }
            }
        }
        return out;
    }

    /** 对单个 spec 调一次 batch-compute，返回 ts_code -> 末值（序列 [-1]）。 */
    private Map<String, BigDecimal> computeOneSpec(Map<String, List<DailyQuoteDO>> ohlcvMap, Spec spec) {
        // 构造请求：data = {symbol: [ohlcv map]}，factors = [spec]
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>(ohlcvMap.size());
        for (Map.Entry<String, List<DailyQuoteDO>> e : ohlcvMap.entrySet()) {
            List<DailyQuoteDO> quotes = e.getValue();
            if (quotes == null || quotes.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> bars = new ArrayList<>(quotes.size());
            for (DailyQuoteDO q : quotes) {
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("date", q.getTradeDate());
                bar.put("open", q.getOpen());
                bar.put("high", q.getHigh());
                bar.put("low", q.getLow());
                bar.put("close", q.getClose());
                bar.put("volume", q.getVol());
                bars.add(bar);
            }
            data.put(e.getKey(), bars);
        }
        if (data.isEmpty()) {
            return Collections.emptyMap();
        }

        FactorBatchComputeRequestDTO req = new FactorBatchComputeRequestDTO();
        req.setData(data);
        FactorComputeSpecDTO specDTO = new FactorComputeSpecDTO();
        specDTO.setFactorKey(spec.factorKey);
        specDTO.setParams(spec.params);
        specDTO.setOutputIndex(spec.outputIndex);
        req.setFactors(List.of(specDTO));

        Map<String, Map<String, Object>> resp = factorClient.batchComputeFactor(req);
        if (resp == null || resp.isEmpty()) {
            return Collections.emptyMap();
        }

        // resp: {symbol: {factorKey: [序列]}}，取末值
        Map<String, BigDecimal> lastValues = new LinkedHashMap<>(resp.size());
        for (Map.Entry<String, Map<String, Object>> e : resp.entrySet()) {
            Object series = e.getValue() == null ? null : e.getValue().get(spec.factorKey);
            BigDecimal last = lastOfSeries(series);
            if (last != null) {
                lastValues.put(e.getKey(), last);
            }
        }
        return lastValues;
    }

    /** 从序列对象（List<Number>）取末值，null/空/NaN 返回 null。 */
    @SuppressWarnings("unchecked")
    private BigDecimal lastOfSeries(Object series) {
        if (!(series instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object v = list.get(list.size() - 1);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** params 规范化为排序后的 JSON（保证唯一键稳定）。 */
    private String canonicalParamsJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sorted = new TreeMap<>(params);
        return JSON.toJSONString(sorted);
    }

    private int upsertAll(List<FactorSnapshotDO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<FactorSnapshotDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            factorSnapshotMapper.deleteBatchByKeys(batch);
            factorSnapshotMapper.insertBatch(batch);
            count += batch.size();
        }
        return count;
    }

    /** 内部 spec 记录 */
    private record Spec(String factorKey, Map<String, Object> params, int outputIndex) {
        @Override
        public String toString() {
            return factorKey + "(" + params + ")#" + outputIndex;
        }
    }
}
