package com.arthur.stock.service.precompute.jobs;

import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.util.CacheKeyResolver;
import com.arthur.stock.vo.IndustryMoneyflowVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 板块资金流预计算 Job。
 * <p>
 * 调用 {@link SwIndustryService#computeIndustryMoneyflow(String)} 计算 28 个申万一级行业资金流聚合，
 * 双写缓存 {@code sectorMoneyflow}：{@code resolveSectorKey(tradeDate)} + {@code "latest"}。
 * <p>
 * <b>数据依赖</b>：stock_moneyflow。完整性校验 stock_moneyflow 当日记录数 &gt; 0。
 */
@Component
@Slf4j
public class SectorMoneyflowPrecomputeJob extends AbstractPrecomputeJob {

    private final SwIndustryService swIndustryService;
    private final MoneyflowMapper moneyflowMapper;

    public SectorMoneyflowPrecomputeJob(CacheManager cacheManager,
                                        SwIndustryService swIndustryService,
                                        MoneyflowMapper moneyflowMapper) {
        super(cacheManager);
        this.swIndustryService = swIndustryService;
        this.moneyflowMapper = moneyflowMapper;
    }

    @Override
    public String name() {
        return "SectorMoneyflow";
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
        List<IndustryMoneyflowVO> result = swIndustryService.computeIndustryMoneyflow(tradeDate);
        String key = CacheKeyResolver.resolveSectorKey(tradeDate);
        Cache cache = cacheManager.getCache(cacheName());
        cache.put(key, result);
        cache.put("latest", result);
    }

    @Override
    protected String cacheName() {
        return "sectorMoneyflow";
    }

    @Override
    protected List<String> cacheKeys(String tradeDate) {
        return List.of(CacheKeyResolver.resolveSectorKey(tradeDate), "latest");
    }
}
