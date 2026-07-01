package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.config.TushareConfig;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.dto.tushare.DailyQueryDTO;
import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.dto.tushare.DividendQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Tushare REST API 客户端，封装所有 Tushare 接口调用。
 * <p>
 * 每个方法严格对应 Tushare 原始 API，参数和返回值均为类型化的 DTO。
 *
 * @see <a href="https://tushare.pro/document/2">Tushare Pro 文档</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TushareClient {

    private final TushareConfig tushareConfig;
    private final RestTemplate restTemplate;
    private final RateLimiter rateLimiter;

    /**
     * 日线行情接口
     *
     * @param param 查询参数，ts_code / trade_date / start_date / end_date 至少传一个
     * @return 日线行情数据列表
     */
    public List<DailyQuoteDTO> daily(DailyQueryDTO param) {
        JSONObject params = buildDailyParams(param);
        return query(TushareApiEnum.DAILY, params, DailyQuoteDTO.class);
    }

    /**
     * 股票基础信息接口
     *
     * @param param 查询参数，所有字段均为可选
     * @return 股票基础信息列表
     */
    public List<StockBasicDTO> stockBasic(StockBasicQueryDTO param) {
        JSONObject params = buildStockBasicParams(param);
        return query(TushareApiEnum.STOCK_BASIC, params, StockBasicDTO.class);
    }

    /**
     * 交易日历接口
     *
     * @param param 查询参数，所有字段均为可选
     * @return 交易日历列表
     */
    public List<TradeCalDTO> tradeCal(TradeCalQueryDTO param) {
        JSONObject params = buildTradeCalParams(param);
        return query(TushareApiEnum.TRADE_CAL, params, TradeCalDTO.class);
    }

    /**
     * 复权因子接口
     *
     * @param param 查询参数，ts_code / trade_date / start_date / end_date 至少传一个
     * @return 复权因子数据列表
     */
    public List<AdjFactorDTO> adjFactor(AdjFactorQueryDTO param) {
        JSONObject params = buildAdjFactorParams(param);
        return query(TushareApiEnum.ADJ_FACTOR, params, AdjFactorDTO.class);
    }

    /**
     * 分红送股接口
     *
     * @param param 查询参数，ts_code / ann_date / record_date / ex_date / imp_ann_date 至少传一个
     * @return 分红送股数据列表
     */
    public List<DividendDTO> dividend(DividendQueryDTO param) {
        JSONObject params = buildDividendParams(param);
        return query(TushareApiEnum.DIVIDEND, params, DividendDTO.class);
    }

    // ==================== 通用请求方法 ====================

    private <T> List<T> query(TushareApiEnum api, JSONObject params, Class<T> clazz) {
        rateLimiter.acquire(api.getApiName());

        JSONObject body = new JSONObject();
        body.put("api_name", api.getApiName());
        body.put("token", tushareConfig.getToken());
        body.put("params", params);
        body.put("fields", api.getFields());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                tushareConfig.getBaseUrl(), HttpMethod.POST, entity, String.class);

        return parseResponse(response.getBody(), clazz);
    }

    /**
     * 解析 Tushare 响应，将 fields + items 转换为 DTO 列表
     */
    private <T> List<T> parseResponse(String responseBody, Class<T> clazz) {
        JSONObject result = JSON.parseObject(responseBody);
        int code = result.getIntValue("code");
        if (code != 0) {
            String msg = result.getString("msg");
            throw new RuntimeException("Tushare API error: api error, code=" + code + ", msg=" + msg);
        }

        JSONObject data = result.getJSONObject("data");
        if (data == null) {
            return Collections.emptyList();
        }

        JSONArray fieldArray = data.getJSONArray("fields");
        JSONArray itemsArray = data.getJSONArray("items");
        if (fieldArray == null || itemsArray == null || itemsArray.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> fields = fieldArray.toJavaList(String.class);
        List<T> list = new ArrayList<>(itemsArray.size());
        for (int i = 0; i < itemsArray.size(); i++) {
            JSONArray item = itemsArray.getJSONArray(i);
            JSONObject row = new JSONObject(fields.size());
            for (int j = 0; j < fields.size(); j++) {
                row.put(fields.get(j), item.get(j));
            }
            list.add(row.toJavaObject(clazz));
        }
        return list;
    }

    /**
     * 构建 daily 接口参数，非空字段才传入
     */
    private JSONObject buildDailyParams(DailyQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getTradeDate() != null) {
            params.put("trade_date", param.getTradeDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getOffset() != null) {
            params.put("offset", String.valueOf(param.getOffset()));
        }
        if (param.getLimit() != null) {
            params.put("limit", String.valueOf(param.getLimit()));
        }
        return params;
    }

    /**
     * 构建 stock_basic 接口参数，非空字段才传入
     */
    private JSONObject buildStockBasicParams(StockBasicQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getName() != null) {
            params.put("name", param.getName());
        }
        if (param.getMarket() != null) {
            params.put("market", param.getMarket());
        }
        if (param.getListStatus() != null) {
            params.put("list_status", param.getListStatus());
        }
        if (param.getExchange() != null) {
            params.put("exchange", param.getExchange());
        }
        if (param.getIsHs() != null) {
            params.put("is_hs", param.getIsHs());
        }
        return params;
    }

    /**
     * 构建 trade_cal 接口参数，非空字段才传入
     */
    private JSONObject buildTradeCalParams(TradeCalQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getExchange() != null) {
            params.put("exchange", param.getExchange());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getIsOpen() != null) {
            params.put("is_open", param.getIsOpen());
        }
        return params;
    }

    /**
     * 构建 adj_factor 接口参数，非空字段才传入
     */
    private JSONObject buildAdjFactorParams(AdjFactorQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getTradeDate() != null) {
            params.put("trade_date", param.getTradeDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        return params;
    }

    /**
     * 构建 dividend 接口参数，非空字段才传入
     */
    private JSONObject buildDividendParams(DividendQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getRecordDate() != null) {
            params.put("record_date", param.getRecordDate());
        }
        if (param.getExDate() != null) {
            params.put("ex_date", param.getExDate());
        }
        if (param.getImpAnnDate() != null) {
            params.put("imp_ann_date", param.getImpAnnDate());
        }
        return params;
    }
}
