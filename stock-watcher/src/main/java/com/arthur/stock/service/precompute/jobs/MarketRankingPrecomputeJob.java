package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.service.impl.MarketServiceImpl;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.MarketRankingVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 市场排行预计算 Job。
 * <p>
 * 调用 {@link MarketServiceImpl#computeMarketRanking()} 计算市场涨幅/跌幅/成交额/换手率 TOP 10。
 * <p>
 * <b>实际数据依赖</b>（仅供查阅，不参与运行时路由）：
 * {@code daily_quote} + {@code daily_basic} + {@code stock_basic} 三张表。
 * 完整性校验只检查最关键的 daily_quote 当日记录数 &gt; 0（已涵盖主要数据）。
 * <p>
 * <b>注</b>：因 {@code MarketService} 接口未暴露 {@code getLatestTradeDate()}，
 * 此处注入 {@link MarketServiceImpl} 以调用该 public 方法（与 {@code @Cacheable} 的 SpEL
 * {@code #root.target.getLatestTradeDate()} 口径一致）。
 */
@Component
@Slf4j
public class MarketRankingPrecomputeJob extends AbstractPrecomputeJob {

    private final MarketServiceImpl marketService;
    private final DailyQuoteMapper dailyQuoteMapper;

    public MarketRankingPrecomputeJob(CacheManager cacheManager,
                                      MarketServiceImpl marketService,
                                      DailyQuoteMapper dailyQuoteMapper) {
        super(cacheManager);
        this.marketService = marketService;
        this.dailyQuoteMapper = dailyQuoteMapper;
    }

    @Override
    public String name() {
        return "MarketRanking";
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
        MarketRankingVO result = marketService.computeMarketRanking();
        String latestTradeDate = marketService.getLatestTradeDate();
        String key = CacheKeyResolver.resolveLatestKey(latestTradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "marketRanking";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        String latestTradeDate = marketService.getLatestTradeDate();
        return List.of(CacheKeyResolver.resolveLatestKey(latestTradeDate), "latest");
    }
}
