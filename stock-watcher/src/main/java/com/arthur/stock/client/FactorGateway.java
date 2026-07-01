package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.dto.FactorReferenceDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.vo.factor.FactorComputeResultVO;
import com.arthur.stock.vo.factor.FactorRegistryVO;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 调用 Python 计算服务的因子 API：
 * <pre>
 *   GET  $python.url/api/compute/factors/registry
 *   POST $python.url/api/compute/factors
 * </pre>
 * 注册表结果写入 Caffeine 缓存；因子计算实时调用。
 * 若 Python 服务不可用，{@link #getRegistry()} 回退为一个空注册表并记录 WARN 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorGateway {

    private static final String REGISTRY_CACHE_KEY = "factor-registry";

    @Value("${python.compute.url:http://127.0.0.1:8000}")
    private String pythonUrl;

    private final RestTemplate restTemplate;

    /** 因子注册表缓存；共享 CacheConfig 中注册的 "factorRegistryCache"。 */
    private final Cache<String, Object> factorRegistryCache;

    /**
     * 拉取因子注册表，含缓存。
     */
    public FactorRegistryVO getRegistry() {
        if (factorRegistryCache == null) {
            return fetchRegistry();
        }
        Object cached = factorRegistryCache.getIfPresent(REGISTRY_CACHE_KEY);
        if (cached instanceof FactorRegistryVO vo) {
            return vo;
        }
        FactorRegistryVO fresh = fetchRegistry();
        factorRegistryCache.put(REGISTRY_CACHE_KEY, fresh);
        return fresh;
    }

    /**
     * 强制刷新缓存。通常由管理员触发。
     */
    public FactorRegistryVO refreshRegistry() {
        FactorRegistryVO fresh = fetchRegistry();
        if (factorRegistryCache != null) {
            factorRegistryCache.put(REGISTRY_CACHE_KEY, fresh);
        }
        return fresh;
    }

    private FactorRegistryVO fetchRegistry() {
        String url = registryUrl();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                FactorRegistryVO vo = JSON.parseObject(resp.getBody(), FactorRegistryVO.class);
                if (vo != null) {
                    return vo;
                }
            }
            log.warn("拉取因子注册表失败: status={}", resp.getStatusCode());
        } catch (Exception ex) {
            log.warn("拉取因子注册表异常: {}", ex.getMessage());
        }

        // 回退：返回空注册表，避免阻塞主应用启动 / 前端页面。
        return FactorRegistryVO.builder()
                .factors(Collections.emptyList())
                .count(0)
                .categories(Collections.emptyList())
                .build();
    }

    /**
     * 单股多因子批量计算。
     *
     * @param ohlcv   OHLCV 数据（由 Java 侧从 daily_quote 中读取）
     * @param factors 待计算因子列表
     * @return 计算结果（包含 taskId / dates / results 等）
     */
    public FactorComputeResultVO computeFactors(List<DailyQuoteDO> ohlcv,
                                                List<FactorReferenceDTO> factors) {
        if (factors == null || factors.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "factors 数组不能为空");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        JSONObject body = new JSONObject();
        body.put("taskId", UUID.randomUUID().toString());

        List<Map<String, Object>> ohlcvJson = ohlcv.stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", q.getTradeDate());
            m.put("open", toDouble(q.getOpen()));
            m.put("high", toDouble(q.getHigh()));
            m.put("low", toDouble(q.getLow()));
            m.put("close", toDouble(q.getClose()));
            m.put("volume", toDouble(q.getVol()));
            return m;
        }).collect(Collectors.toList());
        body.put("ohlcv", ohlcvJson);

        List<Map<String, Object>> factorsJson = factors.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factor", f.getFactor());
            m.put("params", (f.getParams() == null) ? Collections.emptyMap() : f.getParams());
            if (f.getOutputIndex() != null) {
                m.put("outputIndex", f.getOutputIndex());
            }
            return m;
        }).collect(Collectors.toList());
        body.put("factors", factorsJson);

        String url = computeUrl();
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("调用 Python 因子计算失败: status={}", resp.getStatusCode());
                throw new BusinessException(ErrorCode.BAD_REQUEST, "计算服务不可用");
            }
            FactorComputeResultVO vo = JSON.parseObject(resp.getBody(), FactorComputeResultVO.class);
            if (vo == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "计算服务响应解析失败");
            }
            return vo;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("调用 Python 因子计算异常: {}", ex.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计算服务不可用: " + ex.getMessage());
        }
    }

    // ---- private helpers ----

    private String registryUrl() {
        return baseUrl() + "/api/compute/factors/registry";
    }

    private String computeUrl() {
        return baseUrl() + "/api/compute/factors";
    }

    private String baseUrl() {
        String url = (pythonUrl == null) ? "" : pythonUrl.trim();
        if (url.isEmpty()) {
            return "http://127.0.0.1:8000";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static Double toDouble(BigDecimal v) {
        if (v == null) {
            return 0.0;
        }
        return v.doubleValue();
    }
}
