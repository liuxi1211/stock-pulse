package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 个股资金流排行预计算 Job。
 * <p>
 * <b>固定参数</b>：{@code limit=10, sortBy="main_net", order="desc"}。
 * <p>
 * 调用 {@link MoneyflowService#computeQueryTop(String, int, String, String)} 计算 TOP 10 主力净流入排行，
 * 双写缓存 {@code moneyflowRanking}：
 * <ul>
 *   <li>{@code resolveMoneyflowRankingKey(tradeDate, 10, "main_net", "desc")}</li>
 *   <li>{@code "latest_10_main_net_desc"}</li>
 * </ul>
 * <p>
 * <b>数据依赖</b>：stock_moneyflow。完整性校验 stock_moneyflow 当日记录数 &gt; 0。
 */
@Component
@Slf4j
public class MoneyflowRankingPrecomputeJob extends AbstractPrecomputeJob {

    private static final int LIMIT = 10;
    private static final String SORT_BY = "main_net";
    private static final String ORDER = "desc";
    private static final String LATEST_KEY = "latest_10_main_net_desc";

    private final MoneyflowService moneyflowService;
    private final MoneyflowMapper moneyflowMapper;

    public MoneyflowRankingPrecomputeJob(CacheManager cacheManager,
                                         MoneyflowService moneyflowService,
                                         MoneyflowMapper moneyflowMapper) {
        super(cacheManager);
        this.moneyflowService = moneyflowService;
        this.moneyflowMapper = moneyflowMapper;
    }

    @Override
    public String name() {
        return "MoneyflowRanking";
    }

    @Override
    protected boolean isDataReady(String tradeDate) {
        if (tradeDate == null || tradeDate.isEmpty()) {
            return false;
        }
        Long count = moneyflowMapper.selectCount(
                new QueryWrapper<MoneyflowDO>().eq("trade_date", tradeDate));
        return count != null && count > 0;
    }

    @Override
    protected void doPrecompute(String tradeDate) throws Exception {
        List<MoneyflowDO> result = moneyflowService.computeQueryTop(tradeDate, LIMIT, SORT_BY, ORDER);
        String key = CacheKeyResolver.resolveMoneyflowRankingKey(tradeDate, LIMIT, SORT_BY, ORDER);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put(LATEST_KEY, result);
    }

    @Override
    protected String cacheName() {
        return "moneyflowRanking";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        return List.of(
                CacheKeyResolver.resolveMoneyflowRankingKey(tradeDate, LIMIT, SORT_BY, ORDER),
                LATEST_KEY);
    }
}
