package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.constant.StrategyErrorCodes;
import com.arthur.stock.dto.strategy.StrategyValidationError;
import com.arthur.stock.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * stock-engine（Python :8085）的 <b>策略管理</b> HTTP 客户端（spec 004 Task 6）。
 * <p>
 * 仅承担 {@code /python/v1/strategies} 命名空间下的接口调用。当前只实现 {@code /validate}。
 * <p>
 * <b>关键架构差异</b>：engine 的 validate 接口返回 {@code {valid, errors}}，<b>没有</b>
 * {@code {success, data}} 信封。因此本类 <b>不复用</b> {@link AbstractEngineClient#exchangeData}（它会因
 * {@code success!=true} 抛异常），而是在 {@link #validate} 中用 {@link #restTemplate} 直接发起请求、
 * 自己解析 {@code valid/errors}。
 * <p>
 * 仍继承 {@link AbstractEngineClient} 以复用 {@code baseUrl/basePath/restTemplate/parse/url} 等基础能力。
 * <p>
 * 重试策略：连接异常（ResourceAccessException 含 ConnectException/SocketTimeoutException 等）重试 1 次，
 * 间隔 500ms；read timeout 抛 {@link org.springframework.web.client.ResourceAccessException} 时也属此类，
 * 仅重试一次后仍失败则抛 {@link StrategyErrorCodes#ENGINE_SERVICE_UNAVAILABLE}。
 * 超时配置见 {@code RestTemplateConfig}：connect 5s / read 30s。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngineClient extends AbstractEngineClient {

    private static final String STRATEGY_BASE_PATH = "/python/v1/strategies";
    private static final long RETRY_INTERVAL_MS = 500L;
    private static final int MAX_ATTEMPTS = 2;

    private final RestTemplate restTemplate;

    @Value("${python.compute.url}")
    private String engineBaseUrl;

    @Override
    protected String baseUrl() {
        return engineBaseUrl;
    }

    @Override
    protected String basePath() {
        return STRATEGY_BASE_PATH;
    }

    @Override
    protected RestTemplate restTemplate() {
        return restTemplate;
    }

    /**
     * 调用 engine {@code POST /python/v1/strategies/validate} 校验策略配置。
     * <p>
     * 请求体：{@code {"config": <parsed configJson>}}（不直接透传字符串，避免 engine 二次解析歧义）。
     * 响应体：{@code {"valid": true|false, "errors": [{"path","code","message"}, ...]}}。
     *
     * @param configJson 策略配置 JSON 字符串
     * @return 校验通过返回空列表；不通过返回 errors 列表
     * @throws BusinessException engine 不可达时抛 {@link StrategyErrorCodes#ENGINE_SERVICE_UNAVAILABLE}
     */
    public List<StrategyValidationError> validate(String configJson) {
        JSONObject parsedConfig = parseConfig(configJson);
        JSONObject requestBody = new JSONObject();
        requestBody.put("config", parsedConfig);

        String url = url("/validate");
        String body = JSON.toJSONString(requestBody);

        JSONObject resp = postWithRetry(url, body);
        if (resp == null) {
            // 不应到达：postWithRetry 在不可达时已抛异常；防御性兜底
            throw new BusinessException(StrategyErrorCodes.ENGINE_SERVICE_UNAVAILABLE, "策略校验服务返回空响应");
        }

        boolean valid = resp.getBooleanValue("valid");
        if (valid) {
            return Collections.emptyList();
        }
        JSONArray errors = resp.getJSONArray("errors");
        if (errors == null || errors.isEmpty()) {
            // valid=false 但未带 errors：返回单项通用错误
            return Collections.singletonList(new StrategyValidationError(
                    "", "VALIDATION_FAILED", resp.getString("message") != null
                    ? resp.getString("message") : "策略配置校验失败"));
        }
        List<StrategyValidationError> result = new ArrayList<>(errors.size());
        for (int i = 0; i < errors.size(); i++) {
            JSONObject e = errors.getJSONObject(i);
            result.add(new StrategyValidationError(
                    e.getString("path"),
                    e.getString("code"),
                    e.getString("message")));
        }
        return result;
    }

    // ==================== 内部 ====================

    /**
     * POST 请求带连接异常重试（重试 1 次，间隔 500ms）。
     * 4xx/5xx 响应仍尝试解析 {valid, errors}；网络层故障最终抛 ENGINE_SERVICE_UNAVAILABLE。
     */
    private JSONObject postWithRetry(String url, String body) {
        ResourceAccessException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> resp = restTemplate().exchange(url, HttpMethod.POST, entity, String.class);
                return parse(resp.getBody());
            } catch (ResourceAccessException e) {
                lastException = e;
                log.warn("engine POST {} 不可达（第 {} 次）: {}", url, attempt, e.toString());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_INTERVAL_MS);
                }
            } catch (HttpStatusCodeException e) {
                // 4xx/5xx：engine 仍可能在 body 里返回 {valid, errors}，尝试解析
                log.warn("engine POST {} -> {}: {}", url, e.getStatusCode().value(), e.getResponseBodyAsString());
                return parse(e.getResponseBodyAsString());
            } catch (Exception e) {
                log.warn("engine POST {} 失败: {}", url, e.toString());
                throw new BusinessException(StrategyErrorCodes.ENGINE_SERVICE_UNAVAILABLE, "策略校验服务异常");
            }
        }
        log.error("engine POST {} 重试 {} 次仍不可达", url, MAX_ATTEMPTS, lastException);
        throw new BusinessException(StrategyErrorCodes.ENGINE_SERVICE_UNAVAILABLE, "策略校验服务不可用");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把 configJson 解析为 JSONObject；解析失败抛 BAD_REQUEST 而非走 engine，避免无效 JSON 打到 engine。
     */
    private static JSONObject parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED, "策略配置不能为空");
        }
        try {
            Object parsed = JSON.parse(configJson);
            if (parsed instanceof JSONObject jo) {
                return jo;
            }
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED,
                    "策略配置必须是 JSON 对象");
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED,
                    "策略配置 JSON 解析失败: " + e.getMessage());
        }
    }
}
