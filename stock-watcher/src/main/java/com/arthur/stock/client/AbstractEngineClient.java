package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有 Python engine HTTP 客户端的抽象基类，封装通用的 RestTemplate + fastjson2 能力。
 * <p>
 * 设计目标：领域 {@code Client}（如 {@link FactorClient}）只关心 <b>"调哪个 URL + 透传/解析什么"</b>，
 * 不必每个子类都重复写 header 构造、JSON 解析、错误处理等模板代码。
 * <p>
 * 返回值与错误处理约定（engine 响应统一为 {@code {success, message, data, code, errorCode}} 信封）：
 * <ul>
 *   <li>领域 client 的对外方法 <b>直接返回强类型对象</b>（VO / DTO / Map / List），由本类负责拆信封：
 *       校验 {@code success=true} → 取 {@code data} → 反序列化为目标类型。</li>
 *   <li>engine 业务级失败（{@code success:false}）：记录 warn 日志后抛 {@link BusinessException}，
 *       携带 engine 的 {@code code} 与 {@code message(errorCode)}。</li>
 *   <li>engine 不可达 / 超时 / 返回非 JSON（网络层故障）：记录 error 日志（含原始响应）后
 *       抛 {@link BusinessException}({@link ErrorCode#PYTHON_SERVICE_UNAVAILABLE})，
 *       <b>不把 engine 的原始堆栈/错误体透传给前端</b>。</li>
 * </ul>
 * <p>
 * 子类约定：在构造器中调用 {@link #baseUrl()} / {@link #basePath()} 提供本领域的基址与路径前缀，
 * 例如 {@code /python/v1/factors}。
 */
@Slf4j
public abstract class AbstractEngineClient {

    /**
     * 目标服务根地址，例如 {@code http://127.0.0.1:8085}。由子类从配置注入后回传。
     *
     * @return 不含路径的根地址
     */
    protected abstract String baseUrl();

    /**
     * 本领域接口的路径前缀，例如 {@code /python/v1/factors}。
     *
     * @return 以 {@code /} 开头的路径前缀
     */
    protected abstract String basePath();

    /**
     * 拼接完整 URL：{@code baseUrl + basePath + suffix}。
     *
     * @param suffix 额外路径片段，可为 {@code null} 或空串
     * @return 完整 URL
     */
    protected String url(String suffix) {
        StringBuilder sb = new StringBuilder(baseUrl()).append(basePath());
        if (suffix != null && !suffix.isEmpty()) {
            sb.append(suffix);
        }
        return sb.toString();
    }

    // ==================== 业务模板方法（领域 client 直接复用） ====================

    /**
     * GET 请求并把 {@code data} 反序列化为指定类型；失败抛 {@link BusinessException}。
     *
     * @param url   完整 URL
     * @param clazz data 目标类型（VO/DTO 等）
     * @return data 反序列化后的对象
     */
    protected <T> T getDto(String url, Class<T> clazz) {
        return unwrapData(getJson(url)).toJavaObject(clazz);
    }

    /**
     * GET 请求并把 {@code data}（数组）逐项反序列化为 List；失败抛 {@link BusinessException}。
     */
    protected <T> List<T> getDtoList(String url, Class<T> clazz) {
        JSONArray arr = unwrapData(getJson(url)).getJSONArray("data");
        return toList(arr, clazz);
    }

    /**
     * 带 body 的请求并把 {@code data} 反序列化为指定类型；失败抛 {@link BusinessException}。
     */
    protected <T> T exchangeDto(String url, HttpMethod method, Object body, Class<T> clazz) {
        return unwrapData(exchangeJson(url, method, body)).toJavaObject(clazz);
    }

    /**
     * 带 body 的请求并把 {@code data}（数组）逐项反序列化为 List；失败抛 {@link BusinessException}。
     */
    protected <T> List<T> exchangeDtoList(String url, HttpMethod method, Object body, Class<T> clazz) {
        JSONObject resp = exchangeJson(url, method, body);
        JSONArray arr = unwrapData(resp).getJSONArray("data");
        return toList(arr, clazz);
    }

    /**
     * 带 body 的请求，{@code data} 为动态结构时直接返回 data 节点的 {@link JSONObject}
     * （保留嵌套/数组灵活性）。已校验 {@code success=true}；失败抛 {@link BusinessException}。
     */
    protected JSONObject exchangeData(String url, HttpMethod method, Object body) {
        return unwrapData(exchangeJson(url, method, body)).getJSONObject("data");
    }

    /**
     * 子类提供共享的 {@link RestTemplate}（通常由 Spring 注入单例）。
     *
     * @return RestTemplate 实例
     */
    protected abstract RestTemplate restTemplate();

    // ==================== 内部：HTTP + 信封拆解 ====================

    /**
     * GET 请求并解析为 {@link JSONObject}；4xx/5xx 与网络故障统一转 {@link BusinessException}。
     */
    private JSONObject getJson(String url) {
        try {
            return parse(restTemplate().getForObject(url, String.class));
        } catch (ResourceAccessException e) {
            throw unavailable(url, "GET", e);
        } catch (HttpStatusCodeException e) {
            log.warn("engine GET {} -> {}: {}", url, e.getStatusCode().value(), e.getResponseBodyAsString());
            return parse(e.getResponseBodyAsString());
        }
    }

    /**
     * 发起带 JSON body 的请求并解析为 {@link JSONObject}；4xx/5xx 与网络故障统一转 {@link BusinessException}。
     */
    private JSONObject exchangeJson(String url, HttpMethod method, Object body) {
        HttpEntity<String> entity = new HttpEntity<>(body == null ? null : JSON.toJSONString(body), jsonHeaders());
        try {
            ResponseEntity<String> resp = restTemplate().exchange(url, method, entity, String.class);
            return parse(resp.getBody());
        } catch (ResourceAccessException e) {
            throw unavailable(url, method, e);
        } catch (HttpStatusCodeException e) {
            log.warn("engine {} {} -> {}: {}", method, url, e.getStatusCode().value(), e.getResponseBodyAsString());
            return parse(e.getResponseBodyAsString());
        }
    }

    /**
     * 校验 {@code success=true} 并取出 {@code data}；失败抛 {@link BusinessException}。
     * <p>
     * engine 业务级错误（success:false）携带的 errorCode/message 会带到异常里；
     * parse() 兜底生成的 success:false（空响应/解析失败）也会在此统一抛出。
     */
    private static JSONObject unwrapData(JSONObject resp) {
        if (!Boolean.TRUE.equals(resp.getBoolean("success"))) {
            int code = resp.getIntValue("code", 400);
            String message = resp.getString("message");
            String errorCode = resp.getString("errorCode");
            String text = errorCode != null ? message + " (" + errorCode + ")" : message;
            throw new BusinessException(code, text);
        }
        return resp;
    }

    /**
     * engine 不可达 / 超时统一兜底：记 error 日志（含原始异常），抛 2001 服务不可用，
     * 不向调用方暴露 engine 原始错误体。
     */
    private static BusinessException unavailable(String url, Object method, Exception cause) {
        log.error("engine {} {} 不可达: {}", method, url, cause.toString());
        return new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE);
    }

    private static <T> List<T> toList(JSONArray arr, Class<T> clazz) {
        List<T> list = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            list.add(arr.getJSONObject(i).toJavaObject(clazz));
        }
        return list;
    }

    // ==================== 工具 ====================

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 把响应体解析为 {@link JSONObject}，对空/解析失败做兜底（返回 {@code success:false} 结构）。
     */
    protected static JSONObject parse(String body) {
        if (body == null || body.isBlank()) {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("code", 500);
            obj.put("message", "engine 返回空响应");
            return obj;
        }
        try {
            return JSON.parseObject(body);
        } catch (Exception e) {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("code", 500);
            obj.put("message", "engine 响应解析失败: " + e.getMessage());
            return obj;
        }
    }
}
