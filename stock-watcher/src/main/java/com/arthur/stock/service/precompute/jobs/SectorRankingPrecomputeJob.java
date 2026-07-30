package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.IndustryRankingVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 板块排行预计算 Job。
 * <p>
 * 调用 {@link SwIndustryService#computeIndustryRanking(String)} 计算 28 个申万一级行业排行，
 * 双写缓存 {@code sectorRanking}：{@code resolveSectorKey(tradeDate)} + {@code "latest"}。
 * <p>
 * <b>数据依赖</b>：daily_quote + index_daily + sw_industry_member + stock_basic。
 * 完整性校验只检查最关键的 daily_quote 当日记录数 &gt; 0。
 */
@Component
@Slf4j
public class SectorRankingPrecomputeJob extends AbstractPrecomputeJob {

    private final SwIndustryService swIndustryService;
    private final DailyQuoteMapper dailyQuoteMapper;

    public SectorRankingPrecomputeJob(CacheManager cacheManager,
                                      SwIndustryService swIndustryService,
                                      DailyQuoteMapper dailyQuoteMapper) {
        super(cacheManager);
        this.swIndustryService = swIndustryService;
        this.dailyQuoteMapper = dailyQuoteMapper;
    }

    @Override
    public String name() {
        return "SectorRanking";
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
        List<IndustryRankingVO> result = swIndustryService.computeIndustryRanking(tradeDate);
        String key = CacheKeyResolver.resolveSectorKey(tradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "sectorRanking";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        return List.of(CacheKeyResolver.resolveSectorKey(tradeDate), "latest");
    }
}
