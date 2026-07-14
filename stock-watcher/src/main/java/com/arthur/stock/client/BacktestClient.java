package com.arthur.stock.client;

import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.constant.BacktestErrorCodes;
import com.arthur.stock.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * stock-engine（Python :8085）的 <b>回测中心</b> HTTP 客户端（spec 007 T3）。
 * <p>
 * 仅承担 {@code /python/v1/backtest} 命名空间下的接口调用：
 * <ul>
 *   <li>{@code POST /run} —— 单次回测，watcher 已在请求体内拼好 kline_data / benchmark_data。</li>
 *   <li>{@code GET /constants} —— 代理 engine 的常量（broker_profiles / sort_metrics 等）。</li>
 * </ul>
 * <p>
 * 继承 {@link AbstractEngineClient} 以复用 {@code baseUrl/basePath/url/parse/exchangeData} 等能力；
 * engine 返回 {@code {success, data}} 信封，由 {@link #exchangeData} 统一拆解。
 * <p>
 * <b>超时与重试</b>：注入专用 {@code backtestRestTemplate}（connect 5s / read 300s）。
 * 连接异常（{@link ResourceAccessException} 含 {@code ConnectException}）重试 1 次，间隔 500ms；
 * read timeout 不重试（避免重复跑长任务）。
 */
@Slf4j
@Component
public class BacktestClient extends AbstractEngineClient {

    private static final String BACKTEST_BASE_PATH = "/python/v1/backtest";
    private static final long RETRY_INTERVAL_MS = 500L;
    private static final int MAX_ATTEMPTS = 2;

    private final RestTemplate backtestRestTemplate;

    @Value("${python.compute.url}")
    private String engineBaseUrl;

    public BacktestClient(@Qualifier("backtestRestTemplate") RestTemplate backtestRestTemplate) {
        this.backtestRestTemplate = backtestRestTemplate;
    }

    @Override
    protected String baseUrl() {
        return engineBaseUrl;
    }

    @Override
    protected String basePath() {
        return BACKTEST_BASE_PATH;
    }

    @Override
    protected RestTemplate restTemplate() {
        return backtestRestTemplate;
    }

    /**
     * 调用 engine {@code POST /python/v1/backtest/run} 执行单次回测。
     * <p>
     * 请求体由 watcher 侧组装（含 mode/strategyId/versionNo/overrideConfig/benchmark/
     * kline_data/benchmark_data）。响应 {@code data} 节点为回测结果（metrics/equity_curve 等）。
     * <p>
     * 仅对连接异常做 1 次重试；read timeout / 业务级失败不重试。
     *
     * @param request run 请求体（任意可被 fastjson2 序列化的对象，通常是 JSONObject）
     * @return engine data 节点（回测结果）
     */
    public JSONObject runSingle(Object request) {
        String runUrl = url("/run");
        ResourceAccessException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return exchangeData(runUrl, org.springframework.http.HttpMethod.POST, request);
            } catch (ResourceAccessException e) {
                lastException = e;
                // read timeout 形如 SocketTimeoutException，但都被 ResourceAccessException 包装；
                // 仅在首次（attempt==1）重试一次，间隔 500ms。
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("engine POST {} 连接异常（第 {} 次），500ms 后重试: {}", runUrl, attempt, e.toString());
                    sleep(RETRY_INTERVAL_MS);
                }
            } catch (BusinessException e) {
                // 业务级失败（success:false / 4xx / 5xx 已解析）直接向上抛，不重试
                throw e;
            }
        }
        log.error("engine POST {} 重试 {} 次仍不可达", runUrl, MAX_ATTEMPTS, lastException);
        throw new BusinessException(BacktestErrorCodes.ENGINE_SERVICE_UNAVAILABLE, "回测引擎服务不可用");
    }

    /**
     * 调用 engine {@code GET /python/v1/backtest/constants} 取常量字典。
     * <p>
     * 用于前端回测中心常量下拉（broker_profiles / sort_metrics 等）。
     */
    public JSONObject getConstants() {
        String constantsUrl = url("/constants");
        try {
            return unwrapForGet(getForGet(constantsUrl));
        } catch (ResourceAccessException e) {
            log.error("engine GET {} 不可达: {}", constantsUrl, e.toString());
            throw new BusinessException(BacktestErrorCodes.ENGINE_SERVICE_UNAVAILABLE, "回测引擎服务不可用");
        }
    }

    // ==================== 内部 ====================

    /**
     * 直接复用 RestTemplate 发 GET，避免基类 getDto 的强类型反序列化（constants 结构动态）。
     */
    private JSONObject getForGet(String url) {
        return parse(backtestRestTemplate.getForObject(url, String.class));
    }

    private static JSONObject unwrapForGet(JSONObject resp) {
        if (!Boolean.TRUE.equals(resp.getBoolean("success"))) {
            int code = resp.getIntValue("code", 400);
            String message = resp.getString("message");
            throw new BusinessException(code, message == null ? "engine 返回失败" : message);
        }
        return resp.getJSONObject("data");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
