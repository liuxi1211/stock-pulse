package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.MarketTemperatureVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 市场温度预计算 Job。
 * <p>
 * 调用 {@link MarketService#computeMarketTemperature(String)} 计算涨/跌/平/涨停/跌停家数，
 * 双写缓存 {@code marketTemperature}：{@code resolveSectorKey(tradeDate)} + {@code "latest"}。
 * <p>
 * <b>数据依赖</b>：daily_quote。完整性校验 daily_quote 当日记录数 &gt; 0。
 */
@Component
@Slf4j
public class MarketTemperaturePrecomputeJob extends AbstractPrecomputeJob {

    private final MarketService marketService;
    private final DailyQuoteMapper dailyQuoteMapper;

    public MarketTemperaturePrecomputeJob(CacheManager cacheManager,
                                          MarketService marketService,
                                          DailyQuoteMapper dailyQuoteMapper) {
        super(cacheManager);
        this.marketService = marketService;
        this.dailyQuoteMapper = dailyQuoteMapper;
    }

    @Override
    public String name() {
        return "MarketTemperature";
    }

    @Override
    protected boolean isDataReady(String tradeDate) {
        if (tradeDate == null || tradeDate.isEmpty()) {
            return false;
        }
        Long count = dailyQuoteMapper.selectCount(
                new QueryWrapper<DailyQuoteDO>().eq("trade_date", tradeDate));
        return count != null && count > 0;
    }

    @Override
    protected void doPrecompute(String tradeDate) throws Exception {
        MarketTemperatureVO result = marketService.computeMarketTemperature(tradeDate);
        String key = CacheKeyResolver.resolveSectorKey(tradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "marketTemperature";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        return List.of(CacheKeyResolver.resolveSectorKey(tradeDate), "latest");
    }
}
