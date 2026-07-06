package com.arthur.stock.cache;

import com.alibaba.fastjson2.JSON;
import com.arthur.stock.vo.ScreenResultVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 选股执行结果幂等缓存（进程内 Caffeine）。
 * <p>
 * Key = planId + "|" + paramsHash（对 universe/date/conditions/ranking/filters/topN 做 SHA-256）。
 * 命中时直接返回已有结果，避免同一天同一方案重复打 engine。
 * <p>
 * 设计为进程内缓存而非 Spring Cache 注解：参数对象是任意 JSON 树，需自定义哈希 key。
 */
@Slf4j
@Component
public class ScreenerResultCache {

    private final Cache<String, ScreenResultVO> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    /**
     * 取缓存。
     *
     * @param planId  方案ID
     * @param params  参数快照（任意可序列化对象）
     * @return 命中的 VO 或 {@code null}
     */
    public ScreenResultVO get(Long planId, Object params) {
        if (planId == null) {
            return null;
        }
        return cache.getIfPresent(buildKey(planId, params));
    }

    /**
     * 写缓存。
     */
    public void put(Long planId, Object params, ScreenResultVO vo) {
        if (planId == null || vo == null) {
            return;
        }
        cache.put(buildKey(planId, params), vo);
    }

    private String buildKey(Long planId, Object params) {
        return planId + "|" + sha256(JSON.toJSONString(params));
    }

    private String sha256(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("SHA-256 不可用，回退为原始字符串哈希: {}", e.getMessage());
            return Integer.toHexString(text.hashCode());
        }
    }
}
