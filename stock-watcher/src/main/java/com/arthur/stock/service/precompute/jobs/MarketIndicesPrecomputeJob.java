package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.impl.MarketServiceImpl;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.MarketIndexVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 大盘指数预计算 Job。
 * <p>
 * 调用 {@link MarketServiceImpl#computeMarketIndices()} 计算大盘指数列表（无参，内部自行取最新交易日），
 * 双写缓存 {@code indices}：{@code resolveLatestKey(latestTradeDate)} + {@code "latest"}。
 * <p>
 * <b>数据依赖</b>：index_daily。完整性校验 index_daily 当日记录数 &gt; 0。
 * <p>
 * <b>注</b>：因 {@code MarketService} 接口未暴露 {@code getLatestTradeDate()}，
 * 此处注入 {@link MarketServiceImpl} 以调用该 public 方法（与 {@code @Cacheable} 的 SpEL
 * {@code #root.target.getLatestTradeDate()} 口径一致）。
 */
@Component
@Slf4j
public class MarketIndicesPrecomputeJob extends AbstractPrecomputeJob {

    private final MarketServiceImpl marketService;
    private final IndexDailyMapper indexDailyMapper;

    public MarketIndicesPrecomputeJob(CacheManager cacheManager,
                                      MarketServiceImpl marketService,
                                      IndexDailyMapper indexDailyMapper) {
        super(cacheManager);
        this.marketService = marketService;
        this.indexDailyMapper = indexDailyMapper;
    }

    @Override
    public String name() {
        return "MarketIndices";
    }

    @Override
    protected boolean isDataReady(String tradeDate) {
        if (tradeDate == null || tradeDate.isEmpty()) {
            return false;
        }
        Long count = indexDailyMapper.selectCount(
                new QueryWrapper<IndexDailyDO>().eq("trade_date", tradeDate));
        return count != null && count > 0;
    }

    @Override
    protected void doPrecompute(String tradeDate) throws Exception {
        List<MarketIndexVO> result = marketService.computeMarketIndices();
        String latestTradeDate = marketService.getLatestTradeDate();
        String key = CacheKeyResolver.resolveLatestKey(latestTradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "indices";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        String latestTradeDate = marketService.getLatestTradeDate();
        return List.of(CacheKeyResolver.resolveLatestKey(latestTradeDate), "latest");
    }
}
