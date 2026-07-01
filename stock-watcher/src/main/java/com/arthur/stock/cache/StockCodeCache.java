package com.arthur.stock.cache;

import com.arthur.stock.constant.MarketEnum;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.StockBasicDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 股票代码映射缓存：从 stock_basic 表加载 symbol ⇌ ts_code 的权威映射
 * <p>
 * 缓存未命中时以 {@link MarketEnum#fromSymbol} 前缀推断作为兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCodeCache {

    private static final String SEPARATOR = ".";

    private final StockBasicMapper stockBasicMapper;

    private volatile Map<String, String> symbolToTsCode = Map.of();
    private volatile Map<String, String> tsCodeToSymbol = Map.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(cron = "0 0 */3 * * ?")
    private void scheduledRefresh() {
        refresh();
    }

    /**
     * 重新加载缓存，应在 stock_basic 数据同步后调用
     */
    public void refresh() {
        try {
            LambdaQueryWrapper<StockBasicDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(StockBasicDO::getSymbol, StockBasicDO::getTsCode);
            var stocks = stockBasicMapper.selectList(wrapper);

            Map<String, String> s2t = new HashMap<>(stocks.size());
            Map<String, String> t2s = new HashMap<>(stocks.size());
            for (StockBasicDO stock : stocks) {
                s2t.put(stock.getSymbol(), stock.getTsCode());
                t2s.put(stock.getTsCode(), stock.getSymbol());
            }
            symbolToTsCode = Collections.unmodifiableMap(s2t);
            tsCodeToSymbol = Collections.unmodifiableMap(t2s);
            log.info("StockCodeCache loaded {} mappings", s2t.size());
        } catch (Exception e) {
            log.warn("StockCodeCache refresh failed (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * symbol 转 ts_code，如 000001 → 000001.SZ；已经是 ts_code 则原样返回
     */
    public String toTsCode(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        if (isTsCode(symbol)) return symbol;

        String cached = symbolToTsCode.get(symbol);
        if (cached != null) return cached;

        MarketEnum market = MarketEnum.fromSymbol(symbol);
        if (market == null) return symbol;
        return symbol + SEPARATOR + market.getCode();
    }

    /**
     * ts_code 转 symbol，如 000001.SZ → 000001
     */
    public String toSymbol(String tsCode) {
        if (tsCode == null) return null;

        String cached = tsCodeToSymbol.get(tsCode);
        if (cached != null) return cached;

        int idx = tsCode.indexOf(SEPARATOR);
        return idx >= 0 ? tsCode.substring(0, idx) : tsCode;
    }

    /**
     * 判断是否为 ts_code 格式（包含市场后缀）
     */
    public boolean isTsCode(String code) {
        return code != null && code.contains(SEPARATOR);
    }
}
