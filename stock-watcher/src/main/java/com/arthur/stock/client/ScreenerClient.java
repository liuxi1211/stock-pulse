package com.arthur.stock.client;

import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.dto.screener.SnapshotRequestDTO;
import com.arthur.stock.vo.SnapshotResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * stock-engine（Python :8085）的 <b>选股中心</b> HTTP 客户端。
 * <p>
 * 仅承担 {@code /python/v1/screener} 命名空间下的接口调用。对外方法 <b>直接返回强类型对象</b>
 * （VO），由 {@link AbstractEngineClient} 负责拆 engine 响应信封、校验 success、反序列化与错误兜底
 * （engine 不可达/返回错误体时抛 {@code BusinessException}，不透传原始堆栈）。
 * <p>
 * 当前仅实现 snapshot 单次选股；range 区间选股待后续补充。
 */
@Component
@RequiredArgsConstructor
public class ScreenerClient extends AbstractEngineClient {

    private static final String SCREENER_BASE_PATH = "/python/v1/screener";

    private final RestTemplate restTemplate;

    @Value("${python.compute.url}")
    private String engineBaseUrl;

    @Override
    protected String baseUrl() {
        return engineBaseUrl;
    }

    @Override
    protected String basePath() {
        return SCREENER_BASE_PATH;
    }

    @Override
    protected RestTemplate restTemplate() {
        return restTemplate;
    }

    /**
     * 调用 engine {@code POST /python/v1/screener/snapshot} 执行单次多因子选股。
     * <p>
     * 用 {@link #exchangeData} 拿 data 节点的 JSONObject，再 {@code toJavaObject} 转强类型 VO。
     * engine 业务级错误（422 SCREEN_TIME_SERIES_FORBIDDEN、400 UNKNOWN_FACTOR 等）由基类解析后
     * 抛 {@code BusinessException}（携带 engine code）。
     *
     * @param body 选股 snapshot 请求
     * @return engine 返回的选股结果（date/totalCount/stocks/excluded）
     */
    public SnapshotResultVO runSnapshot(SnapshotRequestDTO body) {
        JSONObject data = exchangeData(url("/snapshot"), HttpMethod.POST, body);
        return data == null ? new SnapshotResultVO() : data.toJavaObject(SnapshotResultVO.class);
    }
}
