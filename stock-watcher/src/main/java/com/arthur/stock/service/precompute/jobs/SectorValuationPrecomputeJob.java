package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.IndustryValuationVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 板块估值预计算 Job。
 * <p>
 * 调用 {@link SwIndustryService#computeIndustryValuation(String)} 计算 28 个申万一级行业估值聚合，
 * 双写缓存 {@code sectorValuation}：{@code resolveSectorKey(tradeDate)} + {@code "latest"}。
 * <p>
 * <b>数据依赖</b>：daily_basic。完整性校验 daily_basic 当日记录数 &gt; 0。
 */
@Component
@Slf4j
public class SectorValuationPrecomputeJob extends AbstractPrecomputeJob {

    private final SwIndustryService swIndustryService;
    private final DailyBasicMapper dailyBasicMapper;

    public SectorValuationPrecomputeJob(CacheManager cacheManager,
                                        SwIndustryService swIndustryService,
                                        DailyBasicMapper dailyBasicMapper) {
        super(cacheManager);
        this.swIndustryService = swIndustryService;
        this.dailyBasicMapper = dailyBasicMapper;
    }

    @Override
    public String name() {
        return "SectorValuation";
    }

    @Override
    protected boolean isDataReady(String tradeDate) {
        if (tradeDate == null || tradeDate.isEmpty()) {
            return false;
        }
        Long count = dailyBasicMapper.selectCount(
                new QueryWrapper<DailyBasicDO>().eq("trade_date", tradeDate));
        return count != null && count > 0;
    }

    @Override
    protected void doPrecompute(String tradeDate) throws Exception {
        List<IndustryValuationVO> result = swIndustryService.computeIndustryValuation(tradeDate);
        String key = CacheKeyResolver.resolveSectorKey(tradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "sectorValuation";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        return List.of(CacheKeyResolver.resolveSectorKey(tradeDate), "latest");
    }
}
