package com.arthur.stock.service.impl;

import com.arthur.stock.client.FactorClient;
import com.arthur.stock.dto.factor.FactorBatchComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorCreateRequestDTO;
import com.arthur.stock.dto.factor.FactorUpdateRequestDTO;
import com.arthur.stock.service.FactorService;
import com.arthur.stock.vo.FactorCategoryVO;
import com.arthur.stock.vo.FactorVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 因子服务实现：Caffeine 缓存 engine 元数据，写操作主动失效（spec FR-9 / AC-18）。
 * <p>
 * 缓存名与 {@code CacheConfig} 中 5 分钟兜底 TTL 对应：factorList / factorDetail / factorCategories。
 * <p>
 * 与 engine 的协议解析、错误兜底全部下沉到 {@link FactorClient}，本类只负责缓存策略与对外编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorServiceImpl implements FactorService {

    private final FactorClient factorClient;

    @Override
    @Cacheable(value = "factorList",
            key = "(#category == null ? 'all' : #category) + '_' + (#source == null ? 'all' : #source)")
    public List<FactorVO> listFactors(String category, String source) {
        return factorClient.listFactors(category, source);
    }

    @Override
    @Cacheable(value = "factorDetail", key = "#factorKey")
    public FactorVO getFactor(String factorKey) {
        return factorClient.getFactor(factorKey);
    }

    @Override
    @Cacheable(value = "factorCategories")
    public List<FactorCategoryVO> listCategories() {
        return factorClient.listCategories();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "factorList", allEntries = true),
            @CacheEvict(value = "factorDetail", allEntries = true),
            @CacheEvict(value = "factorCategories", allEntries = true),
    })
    public FactorVO createFactor(FactorCreateRequestDTO request) {
        return factorClient.createFactor(request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "factorList", allEntries = true),
            @CacheEvict(value = "factorDetail", allEntries = true),
            @CacheEvict(value = "factorCategories", allEntries = true),
    })
    public FactorVO updateFactor(String factorKey, FactorUpdateRequestDTO request) {
        return factorClient.updateFactor(factorKey, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "factorList", allEntries = true),
            @CacheEvict(value = "factorDetail", allEntries = true),
            @CacheEvict(value = "factorCategories", allEntries = true),
    })
    public void deleteFactor(String factorKey) {
        factorClient.deleteFactor(factorKey);
    }

    @Override
    public Map<String, Object> compute(FactorComputeRequestDTO request) {
        return factorClient.computeFactor(request);
    }

    @Override
    public Map<String, Map<String, Object>> batchCompute(FactorBatchComputeRequestDTO request) {
        return factorClient.batchComputeFactor(request);
    }
}
