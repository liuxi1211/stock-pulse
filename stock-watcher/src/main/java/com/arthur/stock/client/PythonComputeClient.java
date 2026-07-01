package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.vo.factor.FactorComputeParamVO;
import com.arthur.stock.vo.factor.FactorComputeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class PythonComputeClient {

    @Value("${python.compute.url:http://127.0.0.1:8000}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public PythonComputeClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public FactorComputeResultVO compute(List<Map<String, Object>> ohlcv,
                                          List<FactorComputeParamVO> factors,
                                          String stockCode) {
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stockCode", stockCode);
        body.put("ohlcv", ohlcv == null ? Collections.emptyList() : ohlcv);

        List<Map<String, Object>> factorList = new ArrayList<>();
        if (factors != null) {
            for (FactorComputeParamVO f : factors) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("factorKey", f.getFactorKey());
                m.put("params", f.getParams() == null ? Collections.emptyMap() : f.getParams());
                factorList.add(m);
            }
        }
        body.put("factors", factorList);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

        String url = normalizeUrl(baseUrl) + "/python/v1/compute";

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE,
                        "计算服务响应异常: status=" + resp.getStatusCode());
            }
            JSONObject root = JSON.parseObject(resp.getBody());
            if (root == null) {
                throw new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE, "计算服务响应为空");
            }
            Object codeObj = root.get("code");
            int code = (codeObj instanceof Number n) ? n.intValue() : 0;
            if (code != 0) {
                Object message = root.get("message");
                throw new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE,
                        "计算服务失败: " + (message == null ? "" : message.toString()));
            }
            Object dataObj = root.get("data");
            if (dataObj == null) {
                return FactorComputeResultVO.builder()
                        .requestId(requestId)
                        .dates(Collections.emptyList())
                        .results(Collections.emptyMap())
                        .build();
            }
            FactorComputeResultVO result = JSON.parseObject(JSON.toJSONString(dataObj), FactorComputeResultVO.class);
            if (result.getRequestId() == null && result.getTaskId() != null) {
                result.setRequestId(result.getTaskId());
            }
            return result;
        } catch (BusinessException be) {
            throw be;
        } catch (RestClientException rce) {
            throw new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE,
                    "计算服务不可用: " + rce.getMessage());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PYTHON_SERVICE_UNAVAILABLE,
                    "调用计算服务异常: " + ex.getMessage());
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.endsWith("/")) return u.substring(0, u.length() - 1);
        return u;
    }
}
